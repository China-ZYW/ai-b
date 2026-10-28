package com.aibi.module.chat.nl2sql;

import com.aibi.module.chat.llm.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 追问改写器（Query Rewriting）：把依赖上下文的省略式追问补全成独立完整的问句。
 *
 * <p><b>问题背景</b>：多轮对话里用户几乎不会重复完整问法——"各月销售额趋势""那华东呢""再只看线上"
 * 才是常态。以往做法是<b>把历史问答塞进 Prompt 让大模型在生成 SQL 时顺带理解</b>，
 * 这对 NL2SQL 有效，但对"指标直达 / 样例复用"这类<b>免 LLM 的确定性技能完全无效</b>：
 * 它们只能拿"那华东呢"去和指标名做文本匹配，必然匹配不上，于是每一轮追问都被迫走大模型——
 * 用户沉淀的指标在多轮场景下形同虚设，既慢又烧配额。
 *
 * <p><b>解法</b>：把指代消解前置成独立的一步。改写后得到"华东地区的月度销售额趋势"这种完整问句，
 * 确定性技能的字符串匹配就能正常工作，多轮追问同样能秒回且口径稳定。
 * 这也是 ThoughtSpot / Power BI Copilot 等产品的标准做法（先 rewrite，再 plan/retrieve）。
 *
 * <p><b>成本控制</b>：只有判定为"省略式追问"时才调用大模型（见 {@link #looksElliptical}），
 * 首轮提问与独立完整问句零开销；改写失败或结果可疑时一律回落原句，绝不因改写把链路搞坏。
 *
 * <p><b>安全约束</b>：改写结果会被当作普通提问重新进入技能路由。若大模型（或被注入的上下文）
 * 把结果改成了 SQL 语句，就会误落到"直连 SQL"技能并被原样执行——这是必须堵住的注入路径。
 * 故 {@link #sanitize} 会丢弃一切看起来像 SQL 或过长的改写结果。
 */
@Component
public class QuestionRewriter {

    private static final Logger log = LoggerFactory.getLogger(QuestionRewriter.class);

    /** 指代/省略标志词：命中即说明这句很可能脱离上下文就讲不通。 */
    private static final String[] ELLIPSIS_HINTS = {
            "那", "那个", "这里面", "其中", "同上", "其它", "其他", "其余", "换成", "改看", "只看",
            "去掉", "再加上", "还有呢", "另一种", "反过来", "它们", "它们的", "他呢", "它呢"
    };

    /** 短追问闸门：短到不像完整问句时，只要存在历史就应尝试补全。 */
    private static final int SHORT_QUESTION = 12;

    /** 改写结果长度上限：正常问句远短于此，超长说明模型跑偏了（在解释而不是改写）。 */
    private static final int MAX_REWRITE_LENGTH = 120;

    private final LlmRouter llmRouter;

    public QuestionRewriter(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    /**
     * 是否疑似省略式追问（决定是否值得为此多花一次轻量 LLM 调用）。
     *
     * @param turns    同一会话的历史问答（为空说明是首轮，不存在追问）
     * @param question 本轮原始提问
     */
    public boolean looksElliptical(List<ConversationTurn> turns, String question) {
        if (turns == null || turns.isEmpty() || question == null || question.isBlank()) {
            return false;
        }
        String q = question.trim();
        if (q.length() <= SHORT_QUESTION) {
            return true;
        }
        String nano = q.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
        for (String hint : ELLIPSIS_HINTS) {
            if (nano.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把追问改写为独立完整的问句。
     *
     * @param turns    历史问答（取最后若干轮作为上下文）
     * @param question 本轮原始提问
     * @return 改写后的问句；改写失败/结果不可信时返回原始提问（保证链路永不因改写而中断）
     */
    public String rewrite(List<ConversationTurn> turns, String question) {
        String original = question == null ? "" : question.trim();
        try {
            String prompt = buildPrompt(turns, original);
            String raw = llmRouter.chat(prompt);
            String rewritten = sanitize(raw, original);
            if (!rewritten.equals(original)) {
                log.info("追问已改写：\"{}\" -> \"{}\"", original, rewritten);
            }
            return rewritten;
        } catch (RuntimeException e) {
            // 改写是"增强"而非"必需"：模型不可用/超时都回落原句，由 NL2SQL 自己结合历史上下文处理
            log.warn("追问改写失败，回落原始提问：{}", e.getMessage());
            return original;
        }
    }

    private String buildPrompt(List<ConversationTurn> turns, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据分析助手的「追问改写器」。下面是用户在一个 BI 问数会话里的连续提问。\n\n");
        sb.append("历史问答（按时间顺序）：\n");
        int from = Math.max(0, turns.size() - 4);
        for (int i = from; i < turns.size(); i++) {
            ConversationTurn t = turns.get(i);
            sb.append(i - from + 1).append(". 用户问：").append(nullSafe(t.question())).append("\n");
            if (t.sql() != null && !t.sql().isBlank()) {
                sb.append("   已执行 SQL：").append(t.sql()).append("\n");
            }
        }
        sb.append("\n最新追问：").append(question).append("\n\n");
        sb.append("请把这条追问改写为一个独立完整的中文问句，要求：\n");
        sb.append("1) 补全追问里省略的主语、时间范围、维度过滤，使其脱离上下文也能直接被执行；\n");
        sb.append("2) 保留追问中新引入或改变的限定条件（如换了地区、改成只看某类）；\n");
        sb.append("3) 保持原本的分析意图与业务口径，不要臆造不存在的字段或条件；\n");
        sb.append("4) 只输出改写后的问句本身，不要解释、不要引号、不要 SQL、不要序号。");
        return sb.toString();
    }

    /** 清洗改写结果：去掉模型常见的包装，并对可疑结果（像 SQL / 过长 / 空）回落原句。 */
    private String sanitize(String raw, String original) {
        if (raw == null || raw.isBlank()) {
            return original;
        }
        String s = raw.trim()
                .replaceAll("^[\\s\"'“”`>*-]+", "")
                .replaceAll("[\\s\"'“”`]+$", "")
                .replaceAll("(?i)^(改写结果|改写后|结果|答案)\\s*[:：]\\s*", "");
        // 只取第一行：模型偶尔会先给答案再补一段解释
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).trim();
        }
        if (s.isBlank() || s.length() > MAX_REWRITE_LENGTH) {
            return original;
        }
        // 关键安全闸门：改写结果不得变成 SQL（否则会误触发"直连 SQL"技能被原样执行）
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("select") || lower.startsWith("with") || lower.startsWith("insert")
                || lower.startsWith("update") || lower.startsWith("delete") || lower.startsWith("drop")
                || lower.startsWith("--") || lower.contains(" from ")) {
            log.warn("追问改写结果疑似 SQL，已丢弃并回落原句：{}", s);
            return original;
        }
        return s;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
