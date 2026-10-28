package com.aibi.module.dashboard.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.dashboard.dto.AiLayoutItem;
import com.aibi.module.dashboard.dto.AiLayoutRequest;
import com.aibi.module.dashboard.dto.AiLayoutSlot;
import com.aibi.module.dashboard.dto.AiLayoutVO;
import com.aibi.module.dashboard.dto.DashboardForm;
import com.aibi.module.dashboard.dto.DashboardGroupForm;
import com.aibi.module.dashboard.dto.DashboardGroupVO;
import com.aibi.module.dashboard.dto.DashboardVO;
import com.aibi.module.dashboard.dto.WidgetLayout;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.aibi.common.tenant.TenantContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 驾驶舱 AI 自动化大屏 Skill：基于某数据源的指标，自动编排图表与布局，生成可直接预览的 AI 大屏。
 *
 * <p><b>流程</b>：①收集该数据源的指标 → ②确定性基线布局（适配大屏 8 行预算：KPI 2×2 / 趋势 8×3 / 其余 4×3）
 * → ③先确定性基线落库建大屏 → ④调 {@link AiLayoutAdvisor} 做布局+样式增强（大模型不可用/非法则回退基线，
 * 沿用 {@code source=algorithm} 的确定性方案，绝不抛错）→ ⑤若采用 LLM 方案则按槽位重排并补样式/背景。
 *
 * <p><b>复用而非复制</b>：基线布局尺寸策略与 {@link DashboardService#autoDesign} 同思路，但针对大屏（isScreen=1）
 * 收紧到「8 行预算」规范（与前端 {@code utils/smartLayout.ts} 及 {@link AiLayoutAdvisor} 的硬约束保持一致），
 * 避免两套算法漂移；AI 增强直接复用 {@code AiLayoutAdvisor}，不再另写 Prompt/校验。
 *
 * <p><b>幂等</b>：大屏根分组按「AI 大屏·{数据源名}」复用（不重复建树）；重跑会生成新的大屏（符合"再生成一版预览"语义）。
 *
 * <p><b>开关</b>：由 {@link AibiConfig#cockpitAiDashboardEnabled()}（COCKPIT / ai-dashboard）控制；
 * 本类自身不拦截，入口（驾驶舱页按钮 / init 串联）负责在关闭时隐藏或不串联。
 */
@Service
public class CockpitAutoAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CockpitAutoAnalysisService.class);

    private static final int COLS = 12;
    private static final int SCREEN = 1;

    private final MetricMapper metricMapper;
    private final DashboardService dashboardService;
    private final ResourceGroupService resourceGroupService;
    private final AiLayoutAdvisor aiLayoutAdvisor;
    private final AibiConfig aibiConfig;
    private final DataSourceService dataSourceService;
    private final DashboardJsonSupport json;
    /** 后台异步线程池（提交即返回，实际编排在异步线程跑）。 */
    private final ThreadPoolTaskExecutor analysisExecutor;
    /** 事务模板：异步线程无法依赖 @Transactional 代理（自调用失效），改用模板显式包裹。 */
    private final TransactionTemplate txTemplate;

    public CockpitAutoAnalysisService(MetricMapper metricMapper,
                                      DashboardService dashboardService,
                                      ResourceGroupService resourceGroupService,
                                      AiLayoutAdvisor aiLayoutAdvisor,
                                      AibiConfig aibiConfig,
                                      DataSourceService dataSourceService,
                                      DashboardJsonSupport json,
                                      ThreadPoolTaskExecutor analysisExecutor,
                                      PlatformTransactionManager txManager) {
        this.metricMapper = metricMapper;
        this.dashboardService = dashboardService;
        this.resourceGroupService = resourceGroupService;
        this.aiLayoutAdvisor = aiLayoutAdvisor;
        this.aibiConfig = aibiConfig;
        this.dataSourceService = dataSourceService;
        this.json = json;
        this.analysisExecutor = analysisExecutor;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 对指定数据源自动生成 AI 大屏。
     *
     * @param datasourceId 数据源 id（指标须属该数据源）
     * @param intent       用户的风格指令（可空，透传给布局顾问）
     * @return 生成结果摘要（指标数 / 组件数 / 大屏 id / 方案来源 / 设计摘要）
     */
    /**
     * 同步跑一次大屏编排（事务由模板包裹）。init 串联等少量需要"等结果"的调用方使用。
     */
    public AiDashboardResult analyze(Long datasourceId, String intent) {
        return txTemplate.execute(status -> doAnalyze(datasourceId, intent));
    }

    /**
     * 后台异步版：立即返回"已提交"，实际编排在 {@code analysisExecutor} 线程跑，
     * 并手动把当前完整访问上下文（租户 / 用户 / 团队 / 角色）传播到异步线程——
     * 避免裸 {@code @Async} 丢失 {@code AccessContextHolder} 线程局部导致跨租户或拿不到创建者
     * （参考 {@code AuditService} 的教训）。异常只在异步线程内记录，绝不反压调用方。
     */
    public AiDashboardResult analyzeAsync(Long datasourceId, String intent) {
        analysisExecutor.submit(TenantContext.wrap(() -> {
            try {
                txTemplate.execute(status -> doAnalyze(datasourceId, intent));
            } catch (Exception e) {
                log.error("驾驶舱 AI 自动化大屏生成失败 ds={}: {}", datasourceId, e.getMessage(), e);
            }
        }));
        return new AiDashboardResult(datasourceId, 0, 0, null, null, null, true);
    }

    /** 同步核心（收集指标 → 基线布局 → 落库 → AI 布局增强），事务由调用方包裹。 */
    private AiDashboardResult doAnalyze(Long datasourceId, String intent) {
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "数据源 id 不能为空");
        }
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        String dsName = config == null ? null : config.name();

        List<Metric> metrics = metricMapper.selectList(new LambdaQueryWrapper<Metric>()
                .eq(Metric::getDatasourceId, datasourceId)
                .orderByAsc(Metric::getId));
        if (metrics.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该数据源暂无指标，请先运行指标自动分析");
        }

        // 1) 大屏预算化精选：KPI ≤6 + 趋势 ≤2 + 其余图表 ≤4-趋势数，合计 ≤10（9-12 区间）、maxRow=8
        metrics = selectScreenMetrics(metrics);

        // 2) 确定性基线布局（适配大屏 8 行预算）
        List<WidgetLayout> baseline = planScreenBaseline(metrics);

        // 3) 建大屏根分组（幂等复用）+ 落库基线大屏
        Long groupId = ensureCockpitRootGroup(dsName);
        String name = "AI 大屏·" + (dsName == null ? "数据源" : dsName);
        String desc = "AI 自动化大屏：基于「" + (dsName == null ? "数据源" : dsName) + "」指标自动编排（先确定性基线，再 AI 布局增强）";
        DashboardForm createForm = new DashboardForm(name, desc, "dark", COLS, 0, SCREEN,
                null, null, baseline, groupId);
        DashboardVO created = dashboardService.create(createForm);

        // 4) AI 布局增强（绝不抛错；不可用时返回 source=algorithm 且 slots 为空）
        //    本方法跑在 analysisExecutor 后台线程 → 用长思考模式（无延迟约束，换更好的布局质量）
        AiLayoutVO advice = aiLayoutAdvisor.advise(buildRequest(metrics, baseline, intent), true);
        if (AiLayoutVO.SRC_LLM.equals(advice.source()) && advice.slots() != null && !advice.slots().isEmpty()) {
            List<WidgetLayout> arranged = applySlots(baseline, advice.slots());
            DashboardForm updateForm = new DashboardForm(name, desc, "dark", COLS, 0, SCREEN,
                    advice.screenBg(), advice.style(), arranged, groupId);
            dashboardService.update(created.id(), updateForm);
            log.info("驾驶舱 AI 自动化大屏完成(LLM): ds={} 指标={} 组件={} 大屏={}",
                    datasourceId, metrics.size(), arranged.size(), created.id());
            return new AiDashboardResult(datasourceId, metrics.size(), arranged.size(),
                    created.id(), AiLayoutVO.SRC_LLM, advice.summary());
        }

        log.info("驾驶舱 AI 自动化大屏完成(algorithm): ds={} 指标={} 组件={} 大屏={}",
                datasourceId, metrics.size(), baseline.size(), created.id());
        return new AiDashboardResult(datasourceId, metrics.size(), baseline.size(),
                created.id(), AiLayoutVO.SRC_ALGORITHM, advice.message());
    }

    // ==================== 指标精选（大屏预算：KPI 一行 + 图表两行，≤10 个）====================

    /** 大屏行数预算铁律：KPI 行 h=2 + 图表 2 行 h=3 → maxRow=8。行数一超，视口被等分压扁、KPI 大读数裁切。 */
    private static final int MAX_KPIS = 6;
    private static final int MAX_LINES = 2;
    private static final int MAX_CHARTS = 4;   // 图表槽位总数 = 2 行 ×（趋势 8 宽 + 4 宽）

    /**
     * 大屏预算化精选：KPI ≤6（占满一行 2×2）、趋势 ≤2（8×3）、其余图表 ≤(4-趋势数)（4×3），合计 ≤10 个。
     * 顺序为 KPI → 趋势 → 其余，保证顶部一排大读数、中部趋势、底部排名/占比，整体撑满不超行。
     * 指标不足 9 个时不强行补足（依赖指标自动分析产出足够丰富的指标）。
     */
    private List<Metric> selectScreenMetrics(List<Metric> all) {
        List<Metric> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(this::roleRank));
        List<Metric> kpis = new ArrayList<>();
        List<Metric> lines = new ArrayList<>();
        List<Metric> others = new ArrayList<>();
        for (Metric m : sorted) {
            switch (chartTypeOf(m.getChartSpec())) {
                case "NUMBER" -> { if (kpis.size() < MAX_KPIS) kpis.add(m); }
                case "LINE" -> { if (lines.size() < MAX_LINES) lines.add(m); }
                default -> { if (others.size() < MAX_CHARTS) others.add(m); }
            }
        }
        int otherCap = MAX_CHARTS - lines.size();
        while (others.size() > otherCap) {
            others.remove(others.size() - 1);
        }
        List<Metric> out = new ArrayList<>(kpis);
        out.addAll(lines);
        out.addAll(others);
        return out;
    }

    /** 图表类型 → 大屏视觉优先级（KPI 最前，撑满顶部）。 */
    private int roleRank(Metric m) {
        return switch (chartTypeOf(m.getChartSpec())) {
            case "NUMBER" -> 0;
            case "LINE" -> 1;
            case "BAR" -> 2;
            default -> 3;
        };
    }

    // ==================== 确定性基线布局（大屏 8 行预算）====================

    /**
     * 显式铺满布局（不再逐个换行，杜绝超行压扁）：
     * 第 0 行 KPI ≤6 个 2×2 撑满 12 列；第 2、5 行为图表行——每行「趋势 8×3 + 其余 4×3」成对补齐，
     * 无趋势的行放最多 3 个 4×3。最终 maxRow=8，任何指标组合都不超 8 行预算。
     * 输出与入参 metrics 顺序一一对应（{@link #buildRequest} 按 index 与基线配对）。
     */
    private List<WidgetLayout> planScreenBaseline(List<Metric> metrics) {
        List<Metric> kpis = new ArrayList<>();
        List<Metric> lines = new ArrayList<>();
        List<Metric> others = new ArrayList<>();
        for (Metric m : metrics) {
            switch (chartTypeOf(m.getChartSpec())) {
                case "NUMBER" -> kpis.add(m);
                case "LINE" -> lines.add(m);
                default -> others.add(m);
            }
        }
        Map<Long, WidgetLayout> placed = new java.util.LinkedHashMap<>();
        int x = 0;
        for (Metric k : kpis) {
            placed.put(k.getId(), slot(k, x, 0, 2, 2));
            x += 2;
        }
        int y = 2, oi = 0;
        for (int row = 0; row < 2; row++) {
            if (row < lines.size()) {
                placed.put(lines.get(row).getId(), slot(lines.get(row), 0, y, 8, 3));
                if (oi < others.size()) {
                    placed.put(others.get(oi).getId(), slot(others.get(oi), 8, y, 4, 3));
                    oi++;
                }
            } else {
                int bx = 0;
                while (oi < others.size() && bx + 4 <= COLS) {
                    placed.put(others.get(oi).getId(), slot(others.get(oi), bx, y, 4, 3));
                    bx += 4;
                    oi++;
                }
            }
            y += 3;
        }
        List<WidgetLayout> out = new ArrayList<>(metrics.size());
        for (Metric m : metrics) {
            WidgetLayout w = placed.get(m.getId());
            out.add(w != null ? w : slot(m, 0, y, 4, 3));   // 兜底：预算内组件不会被落到这里
        }
        return out;
    }

    /** 单个 widget 槽位（绑定指标、标题，图表类型由指标自身 chart_spec 决定）。 */
    private WidgetLayout slot(Metric m, int x, int y, int w, int h) {
        return new WidgetLayout(String.valueOf(m.getId()), m.getId(), x, y, w, h, m.getName(),
                null, null, "chart", null, null, null, null, null, null, null);
    }

    private String chartTypeOf(String chartSpecJson) {
        Object spec = json.read(chartSpecJson);
        if (spec instanceof Map<?, ?> map) {
            Object t = map.get("type");
            if (t != null) {
                return t.toString().toUpperCase(Locale.ROOT);
            }
        }
        return "TABLE";
    }

    // ==================== AI 布局顾问请求 ====================

    private AiLayoutRequest buildRequest(List<Metric> metrics, List<WidgetLayout> baseline, String intent) {
        List<AiLayoutItem> items = new ArrayList<>(metrics.size());
        List<AiLayoutSlot> base = new ArrayList<>(metrics.size());
        for (int i = 0; i < metrics.size(); i++) {
            Metric m = metrics.get(i);
            String type = chartTypeOf(m.getChartSpec());
            String ikey = String.valueOf(m.getId());
            items.add(new AiLayoutItem(ikey, m.getName(), type, roleHintOf(type),
                    null, null, null, null, null, "NUMBER".equals(type)));
            WidgetLayout wl = baseline.get(i);
            base.add(new AiLayoutSlot(ikey, wl.x(), wl.y(), wl.w(), wl.h(), null));
        }
        return new AiLayoutRequest(COLS, SCREEN, intent, items, base);
    }

    /** 图表类型 → 给布局顾问的视觉角色提示（仅作参考，不强制）。 */
    private static String roleHintOf(String type) {
        return switch (type) {
            case "NUMBER" -> "KPI";
            case "LINE" -> "TREND";
            case "BAR" -> "RANKING";
            case "PIE" -> "PROPORTION";
            case "TABLE" -> "DETAIL";
            default -> "CHART";
        };
    }

    /** 按 LLM 槽位重排基线布局（保留各 widget 的指标绑定/标题/类型；找不到槽位的沿用基线）。 */
    private List<WidgetLayout> applySlots(List<WidgetLayout> baseline, List<AiLayoutSlot> slots) {
        Map<String, AiLayoutSlot> byI = new HashMap<>();
        for (AiLayoutSlot s : slots) {
            byI.put(s.i(), s);
        }
        List<WidgetLayout> out = new ArrayList<>(baseline.size());
        for (WidgetLayout w : baseline) {
            AiLayoutSlot s = byI.get(w.i());
            if (s != null) {
                out.add(new WidgetLayout(w.i(), w.metricId(), s.x(), s.y(), s.w(), s.h(),
                        w.title(), w.chartStyle(), w.style(), w.kind(), w.infographic(),
                        null, null, null, null, w.image(), w.list()));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    // ==================== 大屏根分组（幂等复用）====================

    private Long ensureCockpitRootGroup(String dsName) {
        String rootName = "AI 大屏·" + (dsName == null ? "数据源" : dsName);
        for (DashboardGroupVO g : resourceGroupService.list(ResourceGroupService.TYPE_DASHBOARD)) {
            if (rootName.equals(g.name()) && g.parentId() == null) {
                return g.id();
            }
        }
        DashboardGroupVO vo = resourceGroupService.create(
                ResourceGroupService.TYPE_DASHBOARD, new DashboardGroupForm(rootName, null));
        return vo.id();
    }

    /** 生成结果摘要。{@code submitted=true} 表示已提交后台异步执行，计数字段 / dashboardId 此时为 0/null。 */
    public record AiDashboardResult(Long datasourceId, int metrics, int widgets,
                                    Long dashboardId, String source, String summary, boolean submitted) {
        public AiDashboardResult(Long datasourceId, int metrics, int widgets,
                                 Long dashboardId, String source, String summary) {
            this(datasourceId, metrics, widgets, dashboardId, source, summary, false);
        }
    }
}
