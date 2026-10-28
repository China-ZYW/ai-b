package com.aibi.module.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 账号创建 / 更新表单（超管后台）。
 *
 * <p>创建时 {@code password} 必填、{@code tenantId} 必填；更新时 {@code password} 留空表示不改密码，
 * {@code username}/{@code tenantId} 不可变更（服务层忽略）。{@code role} 留空默认 {@code USER}。
 */
public record AdminUserForm(
        @NotNull(message = "所属租户不能为空") Long tenantId,
        @NotBlank(message = "用户名不能为空") String username,
        String password,
        String nickname,
        String email,
        String role,
        Integer status) {
}
