package com.aibi.module.admin.dto;

/**
 * 角色字典行（平台后台「权限字典」页的角色矩阵列头，§9.2 治理组）。
 *
 * <p>只读投影自 {@code role} 表。{@code level} 为 PLATFORM/TENANT/TEAM，{@code dataScope}
 * 参与「多角色取最宽」合并；内置角色 {@code builtin=1} 不可删除。
 */
public record RoleVO(
        String code,
        String name,
        String level,
        String dataScope,
        Integer inheritToChild,
        Integer builtin,
        Integer enabled,
        Integer sort,
        String description) {
}
