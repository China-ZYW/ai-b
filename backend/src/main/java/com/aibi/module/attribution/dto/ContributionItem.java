package com.aibi.module.attribution.dto;

import java.math.BigDecimal;

/**
 * 单维度归因的**一个维度成员**的贡献明细。
 *
 * <p><b>MOM 单维贡献分解</b>：对维度 D 的每个成员 i，其拉动贡献 = 本期值 − 基期值
 * （{@code cur - base}）。所有成员贡献之和在数学上等于总变化 {@code Σcur − Σbase}——
 * 这正是"单维度"成立的原因：同一维度的成员互斥且完备，Δ 可被无损拆分。
 * 多维度交叉会打破这一性质（成员不再完备），故 MVP 只支持单维（见需求 §3.1）。
 *
 * @param member 维度成员值（如"华南"、"线上渠道"）；NULL 维度统一渲染为"(空)"
 * @param label  成员展示名（语义层维护的业务别名，无别名时回落成员值）
 * @param cur    本期该成员的度量值
 * @param base   基期该成员的度量值
 * @param delta  拉动贡献 = cur − base（正=正向拉动，负=负向拖累）
 * @param share  贡献占总变化的比例（0~1）；总变化为 0 时为 null，前端不展示占比
 * @param curShare   该成员本期占本期总量的比例（0~1）
 * @param baseShare  该成员基期占基期总量的比例（0~1）
 */
public record ContributionItem(
        String member,
        String label,
        BigDecimal cur,
        BigDecimal base,
        BigDecimal delta,
        Double share,
        Double curShare,
        Double baseShare
) {
}
