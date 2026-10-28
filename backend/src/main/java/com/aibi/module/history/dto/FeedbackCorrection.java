package com.aibi.module.history.dto;

/**
 * 反馈修正采集（T11-2）：用户「点踩」某条问数结果时，可选附带的"我认为正确的口径"。
 *
 * <p>三字段皆可空——用户留空/取消即退化为纯点踩（仅记 {@code feedback=-1}），绝不阻断反馈。
 * 采集到的建议<b>不</b>自动进少样本/指标（延续"只建议不自动改"红线），只作为治理转写抽屉的高价值预填草稿，
 * 经管理员人工复核后才走既有 {@code create} 落库。
 *
 * @param expectedSql 用户填的期望正确 SQL（可空）
 * @param note        口径说明 / 期望结果描述（可空）
 * @param reason      错因分类：WRONG_NUMBER / WRONG_METRIC / WRONG_FILTER / OTHER（可空）
 */
public record FeedbackCorrection(String expectedSql, String note, String reason) {

    /** 是否携带任何实质内容（三字段全空白视为无建议，走纯点踩）。 */
    public boolean hasContent() {
        return notBlank(expectedSql) || notBlank(note) || notBlank(reason);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
