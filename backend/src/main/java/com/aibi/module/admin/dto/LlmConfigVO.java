package com.aibi.module.admin.dto;

import java.time.LocalDateTime;

/**
 * 大模型配置视图对象（超管后台，绝不含 API Key 明文或密文）。
 *
 * @param tenantId  {@code null}=全局默认；非空=租户专属
 * @param hasApiKey 是否已配置 API Key（前端展示“已设置/未设置”，不回填明文）
 */
public record LlmConfigVO(
        Long id,
        Long tenantId,
        String provider,
        String name,
        String baseUrl,
        String model,
        Integer timeoutSeconds,
        Integer priority,
        Integer enabled,
        boolean hasApiKey,
        LocalDateTime createdAt) {
}
