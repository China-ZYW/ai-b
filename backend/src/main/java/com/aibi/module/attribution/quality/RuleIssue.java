package com.aibi.module.attribution.quality;

/**
 * 归因质量校验问题项。供 {@link AttributionQualityRule} 产出，最终随归因结果返回前端展示。
 *
 * @param level   严重级别：{@code WARN}=风险提示（仍可执行）、{@code ERROR}=硬阻断（非可加度量等）
 * @param code    规则编码（如 {@code BASELINE_MISSING} / {@code DIMENSION_SPARSITY} / {@code ADDITIVITY}）
 * @param message 中文说明
 */
public record RuleIssue(String level, String code, String message) {

    public static RuleIssue warn(String code, String message) {
        return new RuleIssue("WARN", code, message);
    }

    public static RuleIssue error(String code, String message) {
        return new RuleIssue("ERROR", code, message);
    }
}
