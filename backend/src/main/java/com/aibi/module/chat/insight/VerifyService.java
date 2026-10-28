package com.aibi.module.chat.insight;

import com.aibi.module.chat.dto.Verify;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.datasource.model.QueryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 质量校验服务（问数准确率与图表能力升级 V3 - 数据完整度）。
 *
 * <p>由 LLM 复核“查询结果是否真正回答了用户问题”及数值合理性，产出 answered（是否回答）、
 * score（0~100 质量分）、issues（问题点）、suggestion（改进建议）。{@code ChatService} 在图表决策后
 * 同步调用（受 {@code ai.verify} 开关控制），并把 score 归一化为 {@code ResultMeta.confidence}。
 *
 * <p>定位为“辅助增强、永不抛错”：无结果、LLM 失败、JSON 解析失败或内容为空一律返回 {@code null}，
 * 绝不阻断问数主链路。
 */
@Component
public class VerifyService {

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);

    /** 喂给 LLM 的样例行上限。 */
    private static final int SAMPLE_ROWS = 20;
    /** 单元格文本截断长度。 */
    private static final int CELL_MAX = 24;

    private final LlmRouter llm;
    private final ObjectMapper objectMapper;

    public VerifyService(LlmRouter llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    /**
     * 复核结果质量。
     *
     * @param question 用户原始问题
     * @param result   查询结果
     * @return 校验结论（answered/score/issues/suggestion）；无结果或失败时为 {@code null}
     */
    public Verify review(String question, QueryResult result) {
        if (result == null || result.columns() == null || result.columns().isEmpty()) {
            return null;
        }
        try {
            Verify verify = parse(llm.chat(buildPrompt(question, result)));
            if (verify != null) {
                log.debug("AI 质量校验：answered={} score={} issues={}",
                        verify.answered(), verify.score(), verify.issues().size());
            }
            return verify;
        } catch (RuntimeException e) {
            log.debug("AI 质量校验失败（降级为无校验）：{}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String question, QueryResult result) {
        List<String> cols = result.columns();
        List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
        StringBuilder sb = new StringBuilder();
        sb.append("你是严谨的数据质量审核专家。请判断下面的查询结果是否真正回答了用户问题，并检查数值是否合理。\n\n");
        sb.append("用户问题：").append(question).append('\n');
        sb.append("执行 SQL：").append(ResultSamples.truncate(result.sql(), 500)).append('\n');
        sb.append("结果总行数：").append(result.rowCount()).append('\n');
        sb.append("列名：").append(String.join(", ", cols)).append('\n');
        sb.append("数据（最多 ").append(SAMPLE_ROWS).append(" 行）：\n");
        int n = Math.min(SAMPLE_ROWS, rows.size());
        for (int r = 0; r < n; r++) {
            sb.append(ResultSamples.rowToString(cols, rows.get(r), CELL_MAX)).append('\n');
        }
        sb.append("\n只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块，格式：\n")
                .append("{\"answered\":<true或false>,\"score\":<0到100的整数>,\"issues\":[\"<问题点，可空>\"],")
                .append("\"suggestion\":\"<改进建议，可空>\"}\n")
                .append("评分标准：完全准确回答且数值合理=90~100；基本回答但有小瑕疵=70~89；部分回答或口径存疑=40~69；")
                .append("未回答或明显错误=0~39。issues/suggestion 用中文，无问题时 issues 为空数组、suggestion 为空字符串。\n");
        return sb.toString();
    }

    private Verify parse(String raw) {
        String json = LlmJson.extract(raw);
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("AI 质量校验 JSON 解析失败：{}", e.getMessage());
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        Boolean answered = null;
        JsonNode an = node.get("answered");
        if (an != null && !an.isNull()) {
            answered = an.asBoolean();
        }
        Integer score = parseIntClamped(node.get("score"));
        List<String> issues = LlmJson.stringList(node, "issues");
        String suggestion = blankToNull(LlmJson.text(node, "suggestion"));
        if (answered == null && score == null && issues.isEmpty() && suggestion == null) {
            return null;
        }
        return new Verify(answered, score, issues, suggestion);
    }

    /** 解析 0~100 的整数评分（容忍数字/数字字符串/小数四舍五入），非法时为 null。 */
    private Integer parseIntClamped(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return clamp(n.asInt());
        }
        try {
            return clamp((int) Math.round(Double.parseDouble(n.asText().trim())));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
