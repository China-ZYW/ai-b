package com.aibi.module.chat.chart;

import com.aibi.common.analysis.ColumnRoleClassifier;
import com.aibi.module.chart.entity.ChartRule;
import com.aibi.module.chart.service.ChartRuleService;
import com.aibi.module.chat.insight.AiChartAdvisor;
import com.aibi.module.chat.nl2sql.Intent;
import com.aibi.module.chat.nl2sql.IntentClassifier;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图表推荐规则引擎（M3 / 开发计划 T4.3）：据"结果结构 + 问数意图"零依赖地推荐最合适的图表。
 *
 * <p>规则优先级（自上而下，命中即返回）：
 * <ol>
 *   <li>无列 / 无数据 / 无数值度量 → 明细表；</li>
 *   <li>用户显式指定图表类型（"折线图/柱状图/饼图/表格"关键词）→ 优先尊重，仅在结果无法成图时回退；</li>
 *   <li>单行结果（KPI 汇总）→ 明细表（前端可做指标卡）；</li>
 *   <li>含时间维度 + 度量 → 折线图（趋势）；</li>
 *   <li>占比/构成类问题 + 单度量 + 类别数适中 → 饼图；</li>
 *   <li>分类维度 + 度量 → 柱状图（排名/对比/多维分组）；</li>
 *   <li>其余 → 明细表兜底。</li>
 * </ol>
 *
 * <p>列的角色（时间/类别/度量）判定顺序：语义层人工确认的 column_role 优先采信，
 * 其次"取样值的运行时类型 + 列名关键词"双重启发式，无需依赖数据库元数据。
 *
 * <p>配置化升级 V2：显式图型关键词规则改读 {@code chart_rule} 表（{@link ChartRuleService}，
 * 内置常量降为种子兜底）；饼图切片/度量数阈值同样可配置；新增 AREA（面积图）与 HBAR（横向条形图），
 * 并修正"条形图"误映射为竖柱的问题。
 */
@Component
public class ChartRecommender {

    private static final Logger log = LoggerFactory.getLogger(ChartRecommender.class);

    /** 时间/类别/主键特征常量与角色判定已下沉至 {@link ColumnRoleClassifier}（T10-4b K1，
     *  图表推荐与环比异常标记共用同一份判定防漂移），此处不再重复定义。 */

    private final IntentClassifier intentClassifier;
    private final ChartRuleService chartRuleService;
    private final SemanticService semanticService;
    private final AiChartAdvisor aiChartAdvisor;
    private final AibiConfig aibiConfig;
    private final ChartStyleResolver chartStyleResolver;
    private final ColumnRoleClassifier columnRoleClassifier;

    public ChartRecommender(IntentClassifier intentClassifier,
                            ChartRuleService chartRuleService,
                            SemanticService semanticService,
                            AiChartAdvisor aiChartAdvisor,
                            AibiConfig aibiConfig,
                            ChartStyleResolver chartStyleResolver,
                            ColumnRoleClassifier columnRoleClassifier) {
        this.intentClassifier = intentClassifier;
        this.chartRuleService = chartRuleService;
        this.semanticService = semanticService;
        this.aiChartAdvisor = aiChartAdvisor;
        this.aibiConfig = aibiConfig;
        this.chartStyleResolver = chartStyleResolver;
        this.columnRoleClassifier = columnRoleClassifier;
    }

    /**
     * 为一次查询结果推荐图表（无语义层信息的兼容入口）。
     */
    public ChartSpec recommend(QueryResult result, String question) {
        return recommend(result, question, null, ProgressListener.NOOP);
    }

    /**
     * 为一次查询结果推荐图表（无进度回调的兼容入口，大屏/指标/分享复用）。
     */
    public ChartSpec recommend(QueryResult result, String question, Long datasourceId) {
        return recommend(result, question, datasourceId, ProgressListener.NOOP);
    }

    /**
     * 为一次查询结果推荐图表（配置化升级 V2 + AI 图表决策 V3）。
     *
     * <p>编排优先级：① 无列/无数据/无数值度量 → 明细表；② 用户显式图型指令 → 优先尊重；
     * ③ AI 图表决策（{@code ai.chart-advisor} 开关开）→ 结构兼容性校验通过才采纳；④ 规则引擎兜底。
     *
     * @param result       查询结果（列 + 数据行）
     * @param question     用户原始问题（用于识别"占比/构成"等语义与显式图型指令）
     * @param datasourceId 数据源 id（可空；非空时语义层人工确认的列角色优先于启发式）
     * @param listener     进度监听器（同步/大屏等无进度场景传 {@link ProgressListener#NOOP}）
     */
    public ChartSpec recommend(QueryResult result, String question, Long datasourceId, ProgressListener listener) {
        return doRecommend(result, question, datasourceId, listener, true);
    }

    /**
     * 回放/回看场景推荐：仅走规则引擎，<b>跳过 AI 图表决策</b>——历史详情点击重建图表时
     * 不应再付 LLM 延迟；显式图型指令与结构规则均为确定性逻辑，可离线复算。
     */
    public ChartSpec recommendNoAi(QueryResult result, String question, Long datasourceId) {
        return doRecommend(result, question, datasourceId, ProgressListener.NOOP, false);
    }

    /**
     * 推荐统一出口：先定“画什么”（图型与字段绑定），再由 {@link ChartStyleResolver} 补“怎么标注”（度量单位）。
     *
     * <p>放在这一层而不是各个 return 点，是为了覆盖规则引擎、显式图型指令与 AI 图表决策三条分支；
     * 回放场景（{@code aiEnabled=false}）同样装配——单位推断是确定性逻辑，不付 LLM 延迟。
     */
    private ChartSpec doRecommend(QueryResult result, String question, Long datasourceId,
                                  ProgressListener listener, boolean aiEnabled) {
        ChartSpec spec = recommendSpec(result, question, datasourceId, listener, aiEnabled);
        return chartStyleResolver.decorate(spec, result, datasourceId);
    }

    private ChartSpec recommendSpec(QueryResult result, String question, Long datasourceId,
                                    ProgressListener listener, boolean aiEnabled) {
        if (result == null || result.columns() == null || result.columns().isEmpty()) {
            return ChartSpec.table("无结果列，使用明细表");
        }
        List<String> columns = result.columns();
        List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
        if (rows.isEmpty()) {
            return ChartSpec.table("查询无数据，使用明细表");
        }

        List<Integer> temporals = new ArrayList<>();
        List<Integer> categories = new ArrayList<>();
        List<Integer> measures = new ArrayList<>();
        // 列角色判定委派公共组件（T10-4b K1）：与环比异常标记共用同一份判定，分桶语义与从前一致
        List<ColumnRoleClassifier.ColumnRole> roles = columnRoleClassifier.classifyAll(result, datasourceId);
        for (int i = 0; i < roles.size(); i++) {
            switch (roles.get(i)) {
                case TEMPORAL -> temporals.add(i);
                case CATEGORY -> categories.add(i);
                case MEASURE -> measures.add(i);
            }
        }

        // ① 无数值度量 → 明细表
        if (measures.isEmpty()) {
            return ChartSpec.table("结果没有数值度量列，适合明细表展示");
        }

        List<String> yFields = measureNames(columns, measures);

        // ② 用户显式指定图表类型（"做成折线图"等，规则来自 chart_rule 配置）→ 优先尊重（问题识别），仅在结果确实无法成图时回退
        ChartType requested = detectRequestedChart(question);
        if (requested == ChartType.TABLE) {
            return ChartSpec.table("按您的要求使用明细表展示");
        }
        if (requested != null) {
            String x = !temporals.isEmpty() ? columns.get(temporals.get(0))
                    : !categories.isEmpty() ? columns.get(categories.get(0)) : null;
            if (x != null) {
                String series = requested != ChartType.PIE && categories.size() >= 2
                        ? columns.get(categories.get(1)) : null;
                return new ChartSpec(requested, null, x, yFields, series,
                        "按您的要求使用" + requested.displayName());
            }
            // 无维度单值（KPI）：不下发 xField，由前端以"度量名作类别"转置成图，仍尊重指定图型
            if (rows.size() == 1) {
                return new ChartSpec(requested, null, null, yFields, null,
                        "按您的要求使用" + requested.displayName() + "展示该单值指标");
            }
            return ChartSpec.table("结果缺少维度列，无法绘制" + requested.displayName() + "，改用明细表");
        }

        // ③ AI 图表决策（V3，开关开）：LLM 依"问题 + 结果结构 + 语义列角色 + 12 图型场景"选图；
        //    仅当输出通过结构兼容性校验（字段真实存在、有有效度量、图型与数据形态不冲突）才采纳，否则回落规则引擎
        if (aiEnabled && aibiConfig.aiChartAdvisor()) {
            listener.onStage(ProgressListener.STAGE_CHART_AI, "AI 正在选择最合适的图表…");
            ChartSpec adopted = adoptAiSpec(result, aiChartAdvisor.advise(question, result, datasourceId),
                    temporals, categories);
            if (adopted != null) {
                listener.onStage(ProgressListener.STAGE_CHART_AI, "AI 图表决策：" + adopted.type().displayName());
                return adopted;
            }
            log.debug("AI 图表决策未采纳（失败或结构不兼容），回落规则引擎");
        }

        // ④ 单行汇总（KPI）→ 明细表 / 指标卡
        if (rows.size() == 1) {
            return ChartSpec.table("单行汇总结果，适合指标卡或明细表展示");
        }

        // ⑤ 时间维度 + 度量 → 折线图
        if (!temporals.isEmpty()) {
            String x = columns.get(temporals.get(0));
            return new ChartSpec(ChartType.LINE, null, x, yFields, null,
                    "含时间维度，折线图适合展示随时间的趋势变化");
        }

        // ⑥ 分类维度 + 度量（含地理分布 → 地图兜底候选）
        if (!categories.isEmpty()) {
            String x = columns.get(categories.get(0));
            // 地理维度 + 地域分布类问法 → 地图（V3 兜底候选；与地理列双重门控，避免劫持普通柱状/饼图）
            if (isGeoQuestion(question) && isGeoColumn(x)) {
                return new ChartSpec(ChartType.MAP, null, x, yFields, null,
                        "含地理维度且询问地域分布，地图适合展示区域差异");
            }
            boolean proportion = isProportion(question);
            // 占比/构成 + 单度量 + 类别数适中 → 饼图（切片阈值可配置）
            if (proportion && measures.size() == 1 && rows.size() <= chartRuleService.maxPieSlices()) {
                return new ChartSpec(ChartType.PIE, null, x, yFields, null,
                        "占比/构成类问题，饼图适合展示部分与整体的关系");
            }
            // 两个及以上类别维度 → 分组柱状图（第二维度作系列）
            String series = categories.size() >= 2 ? columns.get(categories.get(1)) : null;
            Intent intent = intentClassifier.classify(question);
            String reason;
            if (intent == Intent.RANKING) {
                reason = "排名类问题，柱状图便于对比各项高低";
            } else if (series != null) {
                reason = "多维度对比，分组柱状图便于横向比较";
            } else {
                reason = proportion && rows.size() > chartRuleService.maxPieSlices()
                        ? "占比类问题但类别较多，柱状图比饼图更清晰"
                        : "分类对比，柱状图适合展示各类别的数值";
            }
            return new ChartSpec(ChartType.BAR, null, x, yFields, series, reason);
        }

        // ⑦ 仅有度量、无明确维度 → 明细表兜底
        return ChartSpec.table("无明确维度列，使用明细表展示");
    }

    // ==================== AI 图表决策采纳（V3） ====================

    /**
     * 结构兼容性校验并采纳 AI 图表建议：AI 输出可能引用不存在的列（幻觉）或给出与数据形态冲突的映射，
     * 逐条校验——图型非法/无有效度量/度量或维度字段幻觉 → 拒绝（返回 null 回落规则）；系列字段幻觉 → 忽略该字段；
     * 多行却缺维度 → 用规则检测到的时间/类别维补全，补不出则拒绝。TABLE 建议直接采纳。
     */
    private ChartSpec adoptAiSpec(QueryResult result, ChartSpec ai,
                                  List<Integer> temporals, List<Integer> categories) {
        if (ai == null || ai.type() == null) {
            return null;
        }
        List<String> cols = result.columns();
        ChartType type = ai.type();
        if (type == ChartType.TABLE) {
            return ChartSpec.table(notBlank(ai.reason()) ? ai.reason() : "AI 建议使用明细表展示");
        }
        // 度量：必须均为真实列且至少一个（无度量的图无意义）；度量列幻觉直接拒绝
        List<String> ys = new ArrayList<>();
        if (ai.yFields() != null) {
            for (String f : ai.yFields()) {
                if (f == null) {
                    continue;
                }
                if (!cols.contains(f)) {
                    return null;
                }
                if (!ys.contains(f)) {
                    ys.add(f);
                }
            }
        }
        if (ys.isEmpty()) {
            return null;
        }
        // 维度：AI 给的必须是真实列（幻觉则拒绝）；多行却缺维度时用规则检测到的维度补全
        String x = ai.xField();
        if (x != null && !cols.contains(x)) {
            return null;
        }
        int rowCount = result.rows() == null ? 0 : result.rows().size();
        if (x == null && rowCount > 1) {
            x = !temporals.isEmpty() ? cols.get(temporals.get(0))
                    : !categories.isEmpty() ? cols.get(categories.get(0)) : null;
            if (x == null) {
                return null;   // 多行且无任何可用维度 → 画不出，回落规则
            }
        }
        // 系列字段幻觉 → 忽略（不整体拒绝）
        String series = ai.seriesField();
        if (series != null && !cols.contains(series)) {
            series = null;
        }
        String reason = notBlank(ai.reason()) ? ai.reason() : "AI 图表决策推荐" + type.displayName();
        return new ChartSpec(type, ai.title(), x, ys, series, reason);
    }

    // ==================== 地理维度识别（V3：地图兜底候选） ====================

    /** 地理列名特征（中英文）：命中即视为潜在地理维度。 */
    private static final String[] GEO_SUBSTR = {
            "省", "市", "区", "县", "地区", "区域", "地点", "位置", "城市", "省份",
            "city", "province", "region", "geo", "area", "district", "state", "country", "location"};

    /** 问法是否明确涉及"地域分布"（与地理列双重门控，避免劫持普通分类图）。 */
    private boolean isGeoQuestion(String question) {
        if (question == null) {
            return false;
        }
        return containsAny(question.toLowerCase(),
                "地图", "地域", "地区分布", "区域分布", "地理分布", "各省", "各城市", "各地区", "省份分布");
    }

    /** 列名是否命中地理维度特征。 */
    private boolean isGeoColumn(String columnName) {
        return columnName != null && containsAny(columnName.toLowerCase(), GEO_SUBSTR);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ==================== 列角色判定 ====================
    // T10-4b K1：classify/semanticRole 及特征常量已原样下沉至 com.aibi.common.analysis.ColumnRoleClassifier
    // （图表推荐与环比异常标记共用同一份判定防漂移），本类不再自行判定。

    // ==================== 工具方法 ====================

    private List<String> measureNames(List<String> columns, List<Integer> measures) {
        List<String> names = new ArrayList<>();
        int max = chartRuleService.maxMeasures();
        for (int i : measures) {
            if (names.size() >= max) {
                break;
            }
            names.add(columns.get(i));
        }
        return names;
    }

    /**
     * 识别用户在问题中显式指定的图表类型（"做成折线图 / 用柱状图 / 画个饼图 / 条形图 / 面积图 / 给我表格"）。
     * 关键词规则来自 {@code chart_rule} 配置（priority 升序，pattern 支持逗号分隔同义词组，
     * 表空时内置种子兜底）。未指定时返回 null，交由结构规则自动推荐。
     */
    public ChartType detectRequestedChart(String question) {
        if (question == null) {
            return null;
        }
        String q = question.toLowerCase();
        for (ChartRule rule : chartRuleService.keywordRules()) {
            if (matchesPattern(q, rule.getPattern())) {
                try {
                    return ChartType.valueOf(rule.getChartType().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("chart_rule 配置了未知图型，已跳过：pattern={} chartType={}",
                            rule.getPattern(), rule.getChartType());
                }
            }
        }
        return null;
    }

    /** pattern 命中：逗号/顿号/分号分隔的同义词组中任一片段被问题包含即命中。 */
    private boolean matchesPattern(String q, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        for (String token : pattern.split("[,\uff0c;\uff1b\u3001]")) {
            String tk = token.trim().toLowerCase();
            if (tk.length() >= 2 && q.contains(tk)) {
                return true;
            }
        }
        return false;
    }

    /** 识别"占比/构成/分布"等适合饼图的语义。 */
    private boolean isProportion(String question) {
        if (question == null) {
            return false;
        }
        return containsAny(question.toLowerCase(),
                "占比", "比例", "构成", "分布", "份额", "百分比", "占", "pie");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
