package com.aibi.module.config.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 配置 upsert 表单（配置化升级 V2）。scopeType 缺省 TENANT（租户个性化覆盖）；
 * GLOBAL scope 仅超管经 /api/admin/config 写入。
 *
 * <p>{@code managed}（平台管控，仅 GLOBAL 有意义）：{@code true}=把该键锁死为平台值，所有租户不可覆盖；
 * {@code false}=解除管控；{@code null}=不改变现有标记（缺省，老前端行为不变）。
 */
public record ConfigForm(
        String scopeType,
        @NotBlank String category,
        @NotBlank String configKey,
        String valueJson,
        String description,
        Boolean managed
) {
}
