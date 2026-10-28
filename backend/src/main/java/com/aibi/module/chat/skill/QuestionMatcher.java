package com.aibi.module.chat.skill;

import java.util.Locale;

/**
 * 技能匹配的文本工具（零依赖）：供"指标直达 / 样例复用"等确定性技能做低成本的问法归一与相等判定。
 *
 * <p><b>刻意只做"归一化后的相等判定"，不做模糊相似度打分</b>：确定性技能一旦误命中，
 * 会用一个口径不同的已存 SQL 去回答用户的新问题，属于"看起来很快、答案是错的"——
 * 比慢一点但走大模型更糟。故阈值定在"归一后完全相等"，宁可少命中也不错答。
 */
public final class QuestionMatcher {

    /** 归一化时剥除的尾部疑问/祈使词（"销售额是多少" ≡ "销售额"）。 */
    private static final String[] TAIL_NOISE = {
            "是多少", "是什么", "有哪些", "有多少", "怎么算", "查一下", "看一下", "查询", "统计", "帮我", "请问"
    };

    private QuestionMatcher() {
    }

    /**
     * 问法归一化：小写 + 去除所有空白与标点 + 剥除尾部疑问词，用于"同一问法"的判定。
     * 中文无空格，故只按标点与疑问词裁切，不做分词。
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "");
        for (String noise : TAIL_NOISE) {
            if (s.length() > noise.length() && s.endsWith(noise)) {
                s = s.substring(0, s.length() - noise.length());
                break;
            }
        }
        return s;
    }

    /** 归一化后是否相等（空串视为不相等，避免"两个都没填"误判命中）。 */
    public static boolean sameQuestion(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        return !na.isEmpty() && na.equals(nb);
    }

    /** 归一化后 a 是否完全包含 b（"看一下月度GMV" ⊇ "月度GMV"）；b 过短时不参与，防止单字误命中。 */
    public static boolean containsPhrase(String question, String phrase) {
        String nq = normalize(question);
        String np = normalize(phrase);
        return np.length() >= 2 && nq.contains(np);
    }
}
