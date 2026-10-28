package com.aibi.module.chat.insight;

import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.datasource.model.QueryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 结果解读服务（问数准确率与图表能力升级 V3 - 数据完整度）。
 *
 * <p>在返回结果之上，由 LLM 基于“问题 + SQL + 结果样例行”生成自然语言洞察：一句话结论（summary）、
 * 关键要点（keyPoints，趋势/对比/Top 项/占比）、异常提示（anomalies，离群值/空值/口径存疑）。
 * 由 {@code ChatService} 在图表决策后同步调用（受 {@code ai.insight} 开关控制）。
 *
 * <p>定位为“辅助增强、永不抛错”：无结果、LLM 失败、JSON 解析失败或内容为空一律返回 {@code null}，
 * 前端据 null 不展示解读卡片，绝不阻断问数主链路。
 */
@Component
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    /** 喂给 LLM 的样例行上限（够识别趋势/对比即可，避免超长 Prompt）。 */
    private static final int SAMPLE_ROWS = 20;
    /** 单元格文本截断长度。 */
    private static final int CELL_MAX = 24;

    private final LlmRouter llm;
    private final ObjectMapper objectMapper;

    public InsightService(LlmRouter llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成结果解读。
     *
     * @param question 用户原始问题
     * @param result   查询结果
     * @return 解读（summary/keyPoints/anomalies）；无结果或失败时为 {@code null}
     */
    public Insight analyze(String question, QueryResult result) {
        if (result == null || result.columns() == null || result.columns().isEmpty()) {
            return null;
        }
        try {
            Insight insight = parse(llm.chat(buildPrompt(question, result)));
            if (insight != null) {
                log.debug("AI 结果解读：summary={} points={} anomalies={}",
                        insight.summary(), insight.keyPoints().size(), insight.anomalies().size());
            }
            return insight;
        } catch (RuntimeException e) {
            log.debug("AI 结果解读失败（降级为无解读）：{}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String question, QueryResult result) {
        List<String> cols = result.columns();
        List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据分析专家。请基于用户问题与查询结果，用中文给出简洁、准确的数据解读。\n\n");
        sb.append("用户问题：").append(question).append('\n');
        sb.append("执行 SQL：").append(truncate(result.sql(), 500)).append('\n');
        sb.append("结果总行数：").append(result.rowCount()).append('\n');
        sb.append("列名：").append(String.join(", ", cols)).append('\n');
        sb.append("数据（最多 ").append(SAMPLE_ROWS).append(" 行）：\n");
        int n = Math.min(SAMPLE_ROWS, rows.size());
        for (int r = 0; r < n; r++) {
            sb.append(ResultSamples.rowToString(cols, rows.get(r), CELL_MAX)).append('\n');
        }
        sb.append("\n只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块，格式：\n")
                .append("{\"summary\":\"<一句话核心结论>\",\"keyPoints\":[\"<要点1>\",\"<要点2>\"],")
                .append("\"anomalies\":[\"<异常或口径存疑，可空>\"]}\n")
                .append("要求：summary 不超过 60 字；keyPoints 给 2~4 条，聚焦趋势/对比/Top 项/占比等最有价值的信息；")
                .append("anomalies 仅在发现离群值/大量空值/明显不合理时给出，否则为空数组。所有内容用中文。\n");
        return sb.toString();
    }

    private Insight parse(String raw) {
        String json = LlmJson.extract(raw);
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("AI 结果解读 JSON 解析失败：{}", e.getMessage());
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        String summary = blankToNull(LlmJson.text(node, "summary"));
        List<String> keyPoints = LlmJson.stringList(node, "keyPoints");
        List<String> anomalies = LlmJson.stringList(node, "anomalies");
        if (summary == null && keyPoints.isEmpty() && anomalies.isEmpty()) {
            return null;
        }
        return new Insight(summary, keyPoints, anomalies);
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
