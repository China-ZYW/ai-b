package com.aibi.module.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        /** 目标组织（多组织身份登录时指定；为空则按默认/首个组织，向后兼容单组织登录）。 */
        Long tenantId) {
}
