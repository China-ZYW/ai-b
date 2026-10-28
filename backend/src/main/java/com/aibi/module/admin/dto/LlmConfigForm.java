package com.aibi.module.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 大模型配置创建 / 更新表单（超管后台）。
 *
 * <p>{@code tenantId} 留空表示全局默认；{@code apiKey} 在更新时留空表示保持原 Key 不变（只写不读）。
 * {@code provider} 取值 DEEPSEEK / QWEN / OPENAI_COMPATIBLE；{@code priority} 越小越优先。
 */
public record LlmConfigForm(
        Long tenantId,
        @NotBlank(message = "供应商标识不能为空") String provider,
        @NotBlank(message = "配置名称不能为空") String name,
        @NotBlank(message = "base-url 不能为空") String baseUrl,
        String apiKey,
        @NotBlank(message = "模型名不能为空") String model,
        Integer timeoutSeconds,
        Integer priority,
        Integer enabled) {
}
