package com.aibi.module.chat.dto;

import java.util.List;

/**
 * 口径澄清提案（T10-2）：反问门判定"该提问存在口径歧义"时随响应返回的一份候选卡。
 *
 * <p>由 {@code ClarifyGate.evaluate(...)} 产出；{@code null} 表示无歧义（fail-open，直接执行）。
 * 命中歧义时 {@code ChatService} <b>不执行、不扣配额</b>，仅把本提案挂在
 * {@link ChatQueryResponse#clarify()} 上返回，前端渲染澄清卡供用户点选。
 *
 * @param reason            歧义成因标识（{@code MULTI_CALIBER}=一词多口径 / {@code SIMILAR_METRIC}=近似指标名），
 *                          供前端展示不同引导文案与后端聚合统计
 * @param options           候选口径列表（已按置信度排序、截断至 max-options）
 * @param defaultOptionCode 推荐项的 {@link ClarifyOption#code()}（用户不选时可直接确认的兜底项，可空）
 */
public record ClarifyProposal(
        String reason,
        List<ClarifyOption> options,
        String defaultOptionCode
) {

    /** 歧义成因：一词多口径（同一口语词命中多个规范口径）。 */
    public static final String REASON_MULTI_CALIBER = "MULTI_CALIBER";

    /** 歧义成因：近似指标名（提问与多个已登记指标名近似，无法唯一定位）。 */
    public static final String REASON_SIMILAR_METRIC = "SIMILAR_METRIC";
}
