package com.aibi.module.chat.nl2sql;

/**
 * 少样本示例（§3.6 FewShotStore 的元素）：一对"问法 → 已验证 SQL"。
 *
 * <p>配置化升级 V2：增加 {@code exampleId}，命中 curated 样例（few_shot_example）时回写 use_count；
 * 来自历史的样本为 null。
 *
 * <p>Phase-1 意图对齐：增加 {@code intent}，携带该样例标注的意图枚举名（{@code Intent} 的 6 个值之一，
 * 可空）。运行时识别的意图与其做双向对齐——同意图提权、异意图降权（仅微调、不替代词面主信号）；
 * {@code intent == null} 表示旧样例/历史未标注，双向对齐对其恒为 0 调整，完全向后兼容。
 *
 * @param question  自然语言问题
 * @param sql       对应且执行成功的 SQL
 * @param exampleId curated 样例行 id（可空）
 * @param intent    样本标注意图枚举名（可空，见上）
 */
public record FewShot(String question, String sql, Long exampleId, String intent) {

    /** 仅含问法 + SQL（intent 与 exampleId 均空），用于历史候选。 */
    public FewShot(String question, String sql) {
        this(question, sql, null, null);
    }

    /** 含 exampleId、intent 留空（兼容旧调用）。 */
    public FewShot(String question, String sql, Long exampleId) {
        this(question, sql, exampleId, null);
    }
}
