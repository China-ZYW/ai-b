package com.aibi.module.chat.insight;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.chat.chart.ChartType;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.semantic.service.SemanticService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AI 图表决策顾问（问数准确率与图表能力升级 V3 - 图表质量）。
 *
 * <p>在规则引擎之上引入 LLM 决策：把“用户问题 + 结果列结构（列名/推断类型/样例值）+ 语义列角色 +
 * 12 种图表适用场景”喂给模型，要求其只输出纯 JSON（type/xField/yFields/seriesField/reason/confidence）。
 * 由 {@code ChartRecommender} 编排调用，并对其输出做结构兼容性校验后决定采纳或回落规则引擎。
 *
 * <p>定位为“辅助增强、永不抛错”：LLM 失败、JSON 解析失败、图型非法等一律返回 {@code null}，交回调用方
 * 降级。所有字段名一律要求取自真实结果列，字段幻觉由调用方的结构校验兜底拦截；开关由 {@code ChartRecommender}
 * 依 {@code AibiConfig#aiChartAdvisor()} 控制，本类不感知开关，只负责“给出建议”。
 */
@Component
public class AiChartAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AiChartAdvisor.class);

    /** 12 种图表的适用场景清单（喂给 LLM，作为选图依据）。 */
    private static final String CHART_GUIDE = """
            - TABLE 明细表：无合适可视化、纯明细罗列、或无数值度量时。
            - LINE 折线图：时间序列趋势（xField 为时间或有序维度）。
            - AREA 面积图：时间序列趋势且强调量感/累积（同折线，填充面积）。
            - BAR 柱状图：分类对比、排名（xField 为类别，竖直柱）。
            - HBAR 条形图：类别名较长或 Top-N 排名的横向对比。
            - PIE 饼图：占比/构成，单度量且类别数不宜过多（≤8）。
            - HEATMAP 热力图：两个维度交叉的矩阵强度（xField=维度1，seriesField=维度2，yFields=[值]）。
            - MAP 地图：含地理维度（省/市/区县/地区）的区域分布（xField=地理列，yFields=[值]）。
            - RADAR 雷达图：单个对象在多个指标上的画像（xField=指标名，yFields=[值]）。
            - FUNNEL 漏斗图：有序阶段的转化（xField=阶段，yFields=[值]）。
            - GAUGE 仪表盘：单一指标相对目标/上限的达成率（yFields=[值]，通常单行）。
            - SCATTER 散点图：两个数值变量的相关性（xField=数值1，yFields=[数值2]）。
            """;

    /** 结果样例行数上限（喂给 LLM 判断数据形态，够识别趋势/分布即可）。 */
    private static final int SAMPLE_ROWS = 3;

    private final LlmRouter llm;
    private final ObjectMapper objectMapper;
    private final SemanticService semanticService;

    public AiChartAdvisor(LlmRouter llm, ObjectMapper objectMapper, SemanticService semanticService) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.semanticService = semanticService;
    }

    /**
     * 为一次查询结果给出 AI 图表建议。
     *
     * @param question     用户原始问题
     * @param result       查询结果（列 + 数据行）
     * @param datasourceId 数据源 id（可空；非空时附带语义列角色提示）
     * @return 建议的图表规格；无结果列、LLM/解析失败或图型非法时为 {@code null}（调用方降级规则引擎）
     */
    public ChartSpec advise(String question, QueryResult result, Long datasourceId) {
        if (result == null || result.columns() == null || result.columns().isEmpty()) {
            return null;
        }
        try {
            String raw = llm.chat(buildPrompt(question, result, datasourceId));
            ChartSpec spec = parse(raw);
            if (spec != null) {
                log.debug("AI 图表决策：type={} x={} y={} series={} reason={}",
                        spec.type(), spec.xField(), spec.yFields(), spec.seriesField(), spec.reason());
            }
            return spec;
        } catch (RuntimeException e) {
            log.debug("AI 图表决策失败（回落规则引擎）：{}", e.getMessage());
            return null;
        }
    }

    // ==================== Prompt ====================

    private String buildPrompt(String question, QueryResult result, Long datasourceId) {
        List<String> cols = result.columns();
        List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据可视化专家。请为下面的查询结果选择最合适的一种图表，并给出字段映射。\n\n");
        sb.append("用户问题：").append(question).append('\n');
        sb.append("结果行数：").append(rows.size()).append('\n');
        sb.append("列结构（列名 | 推断类型 | 语义角色 | 示例值）：\n");
        for (int i = 0; i < cols.size(); i++) {
            String name = cols.get(i);
            Object sample = firstNonNull(rows, i);
            sb.append("- ").append(name).append(" | ").append(inferKind(sample));
            String role = semanticRole(datasourceId, name);
            if (role != null) {
                sb.append(" | ").append(role);
            }
            if (sample != null) {
                sb.append(" | 例:").append(truncate(String.valueOf(sample), 30));
            }
            sb.append('\n');
        }
        if (!rows.isEmpty()) {
            sb.append("\n数据样例（最多 ").append(SAMPLE_ROWS).append(" 行）：\n");
            int n = Math.min(SAMPLE_ROWS, rows.size());
            for (int r = 0; r < n; r++) {
                sb.append(rowToString(cols, rows.get(r))).append('\n');
            }
        }
        sb.append("\n可选图表类型与适用场景：\n").append(CHART_GUIDE);
        sb.append("\n字段映射约定：xField=维度/类别/时间/地理列名；yFields=数值度量列名数组；")
                .append("seriesField=分组列名（无则留空）。所有字段名必须严格取自上面的列名，禁止臆造。\n");
        sb.append("\n只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块，格式如下：\n")
                .append("{\"type\":\"<类型枚举名>\",\"xField\":\"<列名或空字符串>\",\"yFields\":[\"<列名>\"],")
                .append("\"seriesField\":\"<列名或空字符串>\",\"reason\":\"<一句话中文理由>\",\"confidence\":<0到1的小数>}\n");
        return sb.toString();
    }

    // ==================== 解析（容错，失败返回 null） ====================

    private ChartSpec parse(String raw) {
        String json = LlmJson.extract(raw);
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("AI 图表决策 JSON 解析失败：{}", e.getMessage());
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        ChartType type = parseType(text(node, "type"));
        if (type == null) {
            return null;
        }
        String xField = blankToNull(text(node, "xField"));
        List<String> yFields = new ArrayList<>();
        JsonNode ys = node.get("yFields");
        if (ys != null && ys.isArray()) {
            for (JsonNode y : ys) {
                String f = blankToNull(y.asText(null));
                if (f != null && !yFields.contains(f)) {
                    yFields.add(f);
                }
            }
        }
        String seriesField = blankToNull(text(node, "seriesField"));
        String reason = blankToNull(text(node, "reason"));
        return new ChartSpec(type, null, xField, yFields, seriesField,
                reason != null ? reason : "AI 图表决策推荐" + type.displayName());
    }

    /** 图型枚举解析（容忍常见别名/大小写）；无法识别时返回 null 交由调用方降级。 */
    private ChartType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        switch (t) {
            case "BARCHART":
            case "COLUMN":
                return ChartType.BAR;
            case "LINECHART":
                return ChartType.LINE;
            case "AREACHART":
                return ChartType.AREA;
            case "BARH":
            case "HORIZONTALBAR":
                return ChartType.HBAR;
            case "DONUT":
            case "DOUGHNUT":
                return ChartType.PIE;
            case "HEAT":
                return ChartType.HEATMAP;
            case "DASHBOARD":
                return ChartType.GAUGE;
            case "BUBBLE":
                return ChartType.SCATTER;
            case "GRID":
            case "DETAIL":
                return ChartType.TABLE;
            default:
                break;
        }
        try {
            return ChartType.valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== 工具 ====================

    private String semanticRole(Long datasourceId, String column) {
        if (datasourceId == null || column == null) {
            return null;
        }
        try {
            return semanticService.columnRole(datasourceId, column);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 依样例值的运行时类型推断列的语义类型（数值/时间/布尔/文本），供 LLM 判断数据形态。 */
    private String inferKind(Object sample) {
        if (sample == null) {
            return "未知";
        }
        if (sample instanceof Number) {
            return "数值";
        }
        if (sample instanceof Temporal || sample instanceof Date) {
            return "时间";
        }
        if (sample instanceof Boolean) {
            return "布尔";
        }
        String s = String.valueOf(sample).trim();
        if (s.matches("\\d{4}[-/年.]\\d{1,2}([-/月.]\\d{1,2}日?)?.*")) {
            return "时间";
        }
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            return "数值";
        }
        return "文本";
    }

    private Object firstNonNull(List<List<Object>> rows, int col) {
        for (List<Object> row : rows) {
            if (row != null && col < row.size() && row.get(col) != null) {
                return row.get(col);
            }
        }
        return null;
    }

    private String rowToString(List<String> cols, List<Object> row) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cols.get(i)).append('=');
            Object v = (row != null && i < row.size()) ? row.get(i) : null;
            sb.append(v == null ? "null" : truncate(String.valueOf(v), 24));
        }
        return sb.append('}').toString();
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
