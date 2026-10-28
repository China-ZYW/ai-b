package com.aibi.module.chat.dto;

/**
 * 口径澄清候选项（T10-2）：一个可点选的口径解释，携带"选中后应重发的完整问句"。
 *
 * <p>用户在澄清卡里点选某项后，前端把 {@link #refineQuestion} 回填输入框并以 {@code clarified=true} 重发，
 * 从而锁定这一口径、跳过反问门直接执行（最多 1 轮契约的落点）。
 *
 * @param code           候选标识（指标 code / 术语规范名等，用于 {@code defaultOptionCode} 指向推荐项）
 * @param label          展示文案（如"营收（口径A：含税）"），面向用户、需自解释
 * @param refineQuestion 选中后回填重发的完整问句（已把歧义词替换为该口径的明确表述）
 */
public record ClarifyOption(
        String code,
        String label,
        String refineQuestion
) {
}
