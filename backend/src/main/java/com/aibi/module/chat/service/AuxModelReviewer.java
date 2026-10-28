package com.aibi.module.chat.service;

import com.aibi.module.chat.nl2sql.ConversationTurn;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkillRouter;
import com.aibi.module.chat.skill.SkillVO;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.datasource.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 辅助模型交叉复核协作类（T8.3 自 ChatService 抽出，V5 提准确率系列，全部配置门控、永不抛错）。
 *
 * <p>三处复核共用辅助模型（{@link #resolveAuxModel} 与引擎侧同口径）：
 * <ul>
 *   <li><b>V5-A 技能路由复核</b>：辅助模型独立判定技能，分歧且其选"更保守的免 LLM 确定性技能"才回落；</li>
 *   <li><b>V5-D 结果接地复核</b>：结果返回后判定"是否真回答问题"，判否给出修正方向；</li>
 *   <li><b>V5-E 追问改写复核</b>：校验改写是否真正消解指代、未臆造字段，判否回落原始提问。</li>
 * </ul>
 * 任何异常 / 无辅助模型 / 调用失败 / 解析失败一律"保留原状"返回（主链路零侵入）。
 */
@Component
public class AuxModelReviewer {

    private static final Logger log = LoggerFactory.getLogger(AuxModelReviewer.class);

    private final AibiConfig aibiConfig;
    private final LlmRouter llm;
    private final AskSkillRouter askSkillRouter;

    public AuxModelReviewer(AibiConfig aibiConfig, LlmRouter llm, AskSkillRouter askSkillRouter) {
        this.aibiConfig = aibiConfig;
        this.llm = llm;
        this.askSkillRouter = askSkillRouter;
    }

    /** 接地复核结论：是否真回答问题 + 未回答时的修正方向。 */
    public record Groundedness(boolean answered, String direction) {
    }

    /** 解析辅助模型名（与引擎侧 {@code resolveAuxModel} 同口径）：优先 cross-check-model，留空复用 analysis-model。 */
    public String resolveAuxModel() {
        String m = aibiConfig.crossCheckModel();
        if (m == null || m.isBlank()) {
            m = aibiConfig.aiAnalysisModel();
        }
        return m == null ? null : m;
    }

    /**
     * 技能路由交叉复核（V5-A）：用辅助模型独立判定该走哪条技能，与路由分歧、且辅助模型选的是更保守的
     * 免 LLM 确定性技能时，回落该技能（避免本可免大模型的确定性问法被路由到大模型而答错或烧配额）。
     * 永不抛错——任何异常 / 无辅助模型 / 分歧不满足"更保守"条件 / 辅助技能不可执行一律返回 {@code null}（保留原路由）。
     */
    public AskSkillRouter.SkillDecision routeCrossCheck(AskContext askCtx,
                                                        AskSkillRouter.SkillDecision decision,
                                                        ProgressListener listener) {
        try {
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return null;
            }
            // 用技能清单（编码 + 名称 + 说明）作为选项，要求辅助模型只回技能编码
            StringBuilder catalog = new StringBuilder();
            for (SkillVO v : askSkillRouter.descriptors()) {
                catalog.append("- ").append(v.code()).append("：").append(v.name())
                        .append("（").append(v.llmFree() ? "免大模型" : "需大模型").append("）")
                        .append(v.description() == null ? "" : " " + v.description()).append('\n');
            }
            String prompt = "你是数据分析问数路由专家。下面是可用的答问技能清单。\n" + catalog
                    + "\n用户问题：" + askCtx.question() + "\n"
                    + "请判断这个问题最适合由哪条技能来回答。只回复该技能的编码（如 direct_sql / metric / fewshot / "
                    + "schema_qa / nl2sql），不要任何解释。\n";
            String answer = llm.chatWithModel(prompt, model);
            if (answer == null || answer.isBlank()) {
                return null;
            }
            String code = parseSkillCode(answer);
            if (code == null || code.equalsIgnoreCase(decision.skill().code())) {
                return null; // 与路由一致，无需干预
            }
            // 仅当辅助模型选的是"更保守的免 LLM 确定性技能"、而当前路由落到了需大模型技能时，才回落——
            // 这是"保守护栏"：宁可多耗一次确定性匹配，也不让本可确定的问法误入大模型。
            AskSkillRouter.SkillDecision forced = askSkillRouter.route(askCtx, code);
            if (forced != null && forced.skill().llmFree() && !decision.skill().llmFree()) {
                log.info("路由复核：辅助模型建议 [{}] 替代 [{}]（更保守/免大模型）",
                        forced.skill().code(), decision.skill().code());
                return forced;
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("路由交叉复核失败，保留原路由：{}", e.getMessage());
            return null;
        }
    }

    /** 从辅助模型回复里提取技能编码（取最后一个"单词"并匹配已知清单，容错中英文标点）。 */
    private String parseSkillCode(String answer) {
        String lower = answer.toLowerCase(Locale.ROOT);
        for (SkillVO v : askSkillRouter.descriptors()) {
            String code = v.code().toLowerCase(Locale.ROOT);
            if (lower.contains(code)) {
                return v.code();
            }
        }
        // 兜底：取最后一段非空白 token（去掉"编码："等前缀与代码围栏）
        for (String tok : answer.replace("`", "").split("[\\s,，;；:：]+")) {
            String t = tok.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && t.matches("[a-z0-9_]+")) {
                for (SkillVO v : askSkillRouter.descriptors()) {
                    if (v.code().toLowerCase(Locale.ROOT).equals(t)) {
                        return v.code();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 答非所问结果接地复核（V5-D）：用辅助模型判定"结果是否真回答问题"，判否则给出重生成修正方向。
     * 永不抛错——任何异常 / 无辅助模型 / 解析失败一律返回 {@code null}（调用方跳过）。
     */
    public Groundedness groundednessCheck(String question, QueryResult result, ProgressListener listener) {
        try {
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return null;
            }
            String sample = resultSampleText(result);
            if (sample == null) {
                return null;
            }
            listener.onStage(ProgressListener.STAGE_VERIFY, "辅助模型正在复核结果是否回答问题…");
            String prompt = "你是数据分析结果评审专家。请根据用户问题与实际返回的数据结果，判断结果是否真正回答了问题。\n"
                    + "用户问题：" + question + "\n"
                    + "执行的 SQL：\n" + result.sql() + "\n"
                    + "返回结果（前若干行）：\n" + sample + "\n"
                    + "请判断：① 结果是否真回答了用户问题（true/false）；② 若 false，给出重新生成 SQL 时应遵循的"
                    + "修正方向（一句话，如：应改按省份分组 / 应统计近 30 天而非全部 / 应去掉某过滤条件）。\n"
                    + "严格按 JSON 输出：{\"answered\": true 或 false, \"direction\": \"...\"}，不要其他文字。\n";
            String ans = llm.chatWithModel(prompt, model);
            if (ans == null) {
                return null;
            }
            return parseGroundedness(ans);
        } catch (RuntimeException e) {
            log.warn("辅助接地复核失败，跳过：{}", e.getMessage());
            return null;
        }
    }

    /** 把查询结果渲染为紧凑文本（列名 + 前若干行 + 总行数），供辅助模型接地复核。 */
    private String resultSampleText(QueryResult result) {
        if (result == null || result.columns() == null || result.columns().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", result.columns())).append('\n');
        List<List<Object>> rows = result.rows() == null ? List.of() : result.rows();
        int n = Math.min(rows.size(), 10);
        for (int i = 0; i < n; i++) {
            List<Object> row = rows.get(i);
            sb.append(row.stream().map(o -> o == null ? "" : o.toString()).collect(Collectors.joining("\t")))
                    .append('\n');
        }
        sb.append("(共 ").append(result.rowCount()).append(" 行)").append('\n');
        return sb.toString();
    }

    /** 轻量解析接地复核的 JSON：提取 answered（默认 true）与 direction（可能为空）。绝不抛异常。 */
    private Groundedness parseGroundedness(String ans) {
        String a = ans.trim();
        String low = a.toLowerCase(Locale.ROOT);
        boolean answered = !low.contains("\"answered\" : false") && !low.contains("\"answered\":false")
                && !low.contains("\"answered\": false");
        String direction = "";
        int di = low.indexOf("\"direction\"");
        if (di >= 0) {
            int colon = a.indexOf(':', di);
            if (colon >= 0) {
                int q1 = a.indexOf('"', colon);
                if (q1 >= 0) {
                    int q2 = a.indexOf('"', q1 + 1);
                    if (q2 > q1) {
                        direction = a.substring(q1 + 1, q2);
                    }
                }
            }
        }
        return new Groundedness(answered, direction);
    }

    /**
     * 追问改写交叉复核（V5-E）：用辅助模型校验改写是否真正消解指代、未臆造字段，判否则回落原始提问。
     * 永不抛错——任何异常 / 无辅助模型 / 判为正确一律返回 {@code rewritten}（保留改写结果）。
     */
    public String rewriteCrossCheck(String question, List<ConversationTurn> turns, String rewritten,
                                    ProgressListener listener) {
        try {
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return rewritten;
            }
            String prompt = "你是追问指代消解复核专家。用户最新追问：\"" + question + "\"\n"
                    + "参考历史问答：\n" + turnsText(turns)
                    + "改写器给出的完整问句：\"" + rewritten + "\"\n"
                    + "判断：① 该改写是否完整且正确消解了指代、未臆造不存在的字段或条件（true/false）；"
                    + "② 若 false，简述原因。\n严格 JSON 输出：{\"ok\": true 或 false, \"reason\": \"...\"}。\n";
            String ans = llm.chatWithModel(prompt, model);
            if (ans == null) {
                return rewritten;
            }
            String low = ans.toLowerCase(Locale.ROOT);
            boolean ok = !low.contains("\"ok\" : false") && !low.contains("\"ok\":false")
                    && !low.contains("\"ok\": false");
            if (ok) {
                return rewritten;
            }
            log.info("追问改写复核：辅助模型判定改写可能有误，回落原始提问：{} -> {}", rewritten, question);
            return question; // 保守回落原句（通用生成会结合多轮上下文处理）
        } catch (RuntimeException e) {
            log.warn("追问改写复核失败，保留改写结果：{}", e.getMessage());
            return rewritten;
        }
    }

    /** 把多轮历史拼成复核用的文本（与 QuestionRewriter 同源，但只取问句 + SQL 摘要）。 */
    private String turnsText(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "（无历史）\n";
        }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, turns.size() - 4);
        for (int i = from; i < turns.size(); i++) {
            ConversationTurn t = turns.get(i);
            sb.append(i - from + 1).append(". 用户问：").append(t.question() == null ? "" : t.question()).append('\n');
            if (t.sql() != null && !t.sql().isBlank()) {
                sb.append("   已执行 SQL：").append(t.sql()).append('\n');
            }
        }
        return sb.toString();
    }
}
