package com.aibi.module.admin.dto;

/**
 * 权限点字典行（平台后台「权限字典」页，§9.2 治理组）。
 *
 * <p>只读投影自 {@code permission} 表，不含自增 id 之外的持久化细节。{@code level} 区分
 * PLATFORM/TENANT/TEAM/RESOURCE，{@code type} 区分 MENU/BUTTON/API/DATA，前端据此分组展示。
 */
public record PermissionVO(
        String code,
        String name,
        String module,
        String level,
        String type,
        String menuCode,
        String apiMethod,
        String apiPattern,
        Integer builtin,
        Integer enabled,
        Integer sort,
        String description) {
}
