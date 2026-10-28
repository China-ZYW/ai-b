package com.aibi.module.rbac.dto;

import java.util.List;

/**
 * 自定义角色权限覆盖整体替换表单（《组织治理专项设计》§2.5 {@code setOverrides}）。
 *
 * <p>{@code grant} 为「在 base 权限基础上放开」的权限点列表（服务层校验每条都落在 base 权限集内，
 * 否则 {@code ROLE_GRANT_ESCALATION}，保证窄化不变式）；{@code deny} 为「从 base 权限中移除」的列表
 * （DENY 优先于 base 的授予，且不可被反转）。同一权限点不会同时出现在两列表
 * （{@code role_permission_override.uk_role_perm} 唯一约束保证 GRANT/DENY 互斥）。
 *
 * <p>语义为「整体替换」：提交即删除该角色旧覆盖、按本表单重建。
 */
public record CustomRolePermissionForm(
        List<String> grant,
        List<String> deny) {
}
