package com.aibi.module.rbac.dto;

import java.time.LocalDateTime;

/**
 * 自定义角色视图（《组织治理专项设计》§2.7 列表/明细）。
 *
 * <p>{@code overrideCount} = 该角色的 GRANT + DENY 覆盖行总数，供前端快速展示「已收窄 N 项」；
 * 详细权限拆分走 {@code GET /{id}/effective}。{@code enabled} 为软开关，停用不影响已授予成员
 * （重新启用即恢复，见设计文档 §2.1 不变式④）。
 */
public record CustomRoleVO(
        Long id,
        String name,
        String baseRoleCode,
        String dataScope,
        String description,
        boolean enabled,
        int overrideCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
