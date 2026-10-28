package com.aibi.module.chat.chart;

import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图表展示样式装配器：为推荐出的 {@link ChartSpec} 补上可自动推断的标注信息（当前为<b>度量单位</b>）。
 *
 * <p>单位取值优先级：语义层 {@code semantic_meta.unit}（管理员维护，一处生效全租户）&gt;
 * 按列名/业务别名关键词推断 &gt; 不设（宁缺勿错）。
 *
 * <p><b>刻意不做的事</b>：
 * <ol>
 *   <li>不写 {@code xAxisName}/{@code yAxisName}——轴名由前端动态回落列展示名（语义别名 → 列名），
 *       一旦固化进 {@code chart_spec}，管理员日后改语义别名，已存指标的轴名就会与表头/图例不一致；</li>
 *   <li>不写数值格式/图例位置等租户级默认——那属于 {@code sys_config} CHART 分类，前端渲染时读取，
 *       改配置即全局生效；写进每个指标会变成改不动的历史快照；</li>
 *   <li>不覆盖用户已设的 {@code style}——结果面板/指标编辑处的手工配置是最高优先级，装配器只填空缺。</li>
 * </ol>
 *
 * <p>推断失败或结果不适用（明细表、无度量）时原样返回，不下发空 style，保持 {@code chart_spec} 精简
 * （存该 JSON 的三列均为 VARCHAR(2048)，分享侧写入前另有列宽保护，超限会降级为明细表）。
 */
@Component
public class ChartStyleResolver {

    private static final Logger log = LoggerFactory.getLogger(ChartStyleResolver.class);

    /**
     * 高置信度单位关键词（按声明顺序匹配，先命中先得）。
     *
     * <p>只收录"关键词本身已含量词"或"业务上无歧义"的条目：金额类统一为元、比例类为 %、
     * 销量/件数为件、人数类为人、次数类为次。泛化的"数量/计数"不推断——它可能是件、次、人、单中的任何一种，
     * 猜错比不猜更糟（错误单位会被读成事实）。
     */
    private static final Map<String, String> UNIT_KEYWORDS = unitKeywords();

    private final SemanticService semanticService;
    private final AibiConfig aibiConfig;

    public ChartStyleResolver(SemanticService semanticService, AibiConfig aibiConfig) {
        this.semanticService = semanticService;
        this.aibiConfig = aibiConfig;
    }

    /**
     * 为图表规格装配展示样式。
     *
     * @param spec         推荐出的图表规格（可空）
     * @param result       查询结果（用于取列展示名参与关键词推断）
     * @param datasourceId 数据源 id（可空；为空时跳过语义层查询，仅按列名推断）
     * @return 装配后的规格；无需/无法装配时返回原对象
     */
    public ChartSpec decorate(ChartSpec spec, QueryResult result, Long datasourceId) {
        if (spec == null || spec.type() == null || spec.type() == ChartType.TABLE) {
            return spec;   // 明细表没有坐标轴与数值标注
        }
        if (spec.style() != null) {
            return spec;   // 已有样式（用户设置或上游透传）：不干预
        }
        if (!aibiConfig.chartAutoUnit()) {
            return spec;   // 租户关闭自动推断：仅用户手配生效
        }
        try {
            String unit = resolveUnit(spec, result, datasourceId);
            return unit == null ? spec
                    : spec.withStyle(new ChartStyle(null, null, unit, null, null, null, null));
        } catch (RuntimeException e) {
            // 标注属于锦上添花：语义层读取异常不得影响图表推荐主流程
            log.warn("图表单位推断失败（按无单位处理）：{}", e.getMessage());
            return spec;
        }
    }

    /**
     * 推断度量单位：多度量时要求单位一致，否则返回 null——一个 {@code valueUnit} 无法同时描述
     * "销售额（元）"与"订单数（单）"，强行标注会让其中一个度量的读数被误读。
     */
    private String resolveUnit(ChartSpec spec, QueryResult result, Long datasourceId) {
        List<String> yFields = spec.yFields();
        if (yFields == null || yFields.isEmpty()) {
            return null;
        }
        List<String> labels = displayLabels(datasourceId, result, yFields);
        String common = null;
        for (int i = 0; i < yFields.size(); i++) {
            String unit = unitOf(datasourceId, yFields.get(i), i < labels.size() ? labels.get(i) : null);
            if (unit == null) {
                return null;   // 有度量推不出单位：不标注，避免"部分度量带单位"的错觉
            }
            if (common == null) {
                common = unit;
            } else if (!common.equals(unit)) {
                return null;   // 单位不一致（元 vs 件）：交由用户手工设置或改用双轴
            }
        }
        return common;
    }

    /** 单列单位：语义层维护值优先，其次按物理列名与业务别名的关键词推断。 */
    private String unitOf(Long datasourceId, String column, String label) {
        String semantic = semanticService.unitOf(datasourceId, column);
        if (semantic != null && !semantic.isBlank()) {
            return semantic.trim();
        }
        String byColumn = matchKeyword(column);
        if (byColumn != null) {
            return byColumn;
        }
        // 物理列名是 total_amount 这类英文、别名是"销售总额"这类中文时，别名往往更能命中关键词
        return label == null || label.equals(column) ? null : matchKeyword(label);
    }

    /** 取度量列的业务别名（无别名时回落列名）；语义层异常时返回空列表，不影响关键词推断。 */
    private List<String> displayLabels(Long datasourceId, QueryResult result, List<String> yFields) {
        if (datasourceId == null || result == null || result.columns() == null) {
            return List.of();
        }
        try {
            List<String> labels = semanticService.displayLabels(datasourceId, result.columns());
            if (labels == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>(yFields.size());
            for (String field : yFields) {
                int idx = result.columns().indexOf(field);
                out.add(idx >= 0 && idx < labels.size() ? labels.get(idx) : field);
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("度量列别名读取跳过：{}", e.getMessage());
            return List.of();
        }
    }

    private static String matchKeyword(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : UNIT_KEYWORDS.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 关键词表用 LinkedHashMap 保序：金额类须先于比例/计数类匹配，使"订单金额"命中元而不是被"订单数"抢走。
     *
     * <p>注意不可用 {@code Map.copyOf}——它不保证迭代顺序，会让匹配结果变得不确定。
     */
    private static Map<String, String> unitKeywords() {
        Map<String, String> m = new LinkedHashMap<>();
        // 金额类 → 元（中英文列名都覆盖）
        for (String k : new String[]{"金额", "营收", "收入", "销售额", "销售总额", "成本", "利润", "费用",
                "单价", "价格", "总额", "客单价", "amount", "revenue", "sales", "price", "cost",
                "profit", "fee", "gmv"}) {
            m.put(k, "元");
        }
        // 比例类 → %（值本身可能已是 0~100 的百分数，也可能 0~1，故只标注符号不做换算）
        for (String k : new String[]{"占比", "比率", "比例", "百分", "转化率", "满意率", "达成率",
                "rate", "ratio", "percent", "pct"}) {
            m.put(k, "%");
        }
        // 量词已内含在关键词里的计数类（泛化的"数量/计数"不推断）
        for (String k : new String[]{"销量", "件数", "销售量", "qty", "quantity"}) {
            m.put(k, "件");
        }
        for (String k : new String[]{"人数", "用户数", "客户数", "员工数", "会员数"}) {
            m.put(k, "人");
        }
        for (String k : new String[]{"次数", "订单数", "单数", "笔数"}) {
            m.put(k, "次");
        }
        return Collections.unmodifiableMap(m);
    }
}
