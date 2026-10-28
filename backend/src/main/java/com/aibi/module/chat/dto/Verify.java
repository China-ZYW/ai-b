package com.aibi.module.chat.dto;

import java.util.List;

/**
 * AI 质量校验（问数准确率与图表能力升级 V3 - 数据完整度）：LLM 复核“结果是否真正回答了问题”
 * 及数值合理性。由 {@code VerifyService} 产出，失败时为 {@code null}；{@code score} 归一化后
 * 回填 {@link ResultMeta#confidence()}。
 *
 * @param answered   结果是否回答了用户问题
 * @param score      质量评分（0~100）
 * @param issues     发现的问题（口径偏差 / 数值异常 / 未覆盖问题等，可空）
 * @param suggestion 改进建议（可空）
 */
public record Verify(
        Boolean answered,
        Integer score,
        List<String> issues,
        String suggestion
) {
}
