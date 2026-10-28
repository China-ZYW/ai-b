package com.aibi.module.auth.dto;

/**
 * 当前自然人身份可切换的组织视图（多组织身份 / 组织切换器用）。
 *
 * @param tenantId   组织 id（即租户 id）
 * @param tenantName 组织名称
 * @param orgRoles   在该组织的组织角色码（逗号分隔，如 {@code OWNER}/{@code ADMIN}/{@code ANALYST}）
 * @param current    是否为当前生效组织
 */
public record MyTenantVO(Long tenantId, String tenantName, String orgRoles, boolean current) {
}
