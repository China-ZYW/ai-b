package com.aibi.module.admin.dto;

import java.time.LocalDateTime;

/**
 * 账号视图对象（超管后台，绝不含密码哈希）。
 *
 * @param role SUPER_ADMIN / USER
 * @param status 1=正常 0=禁用
 */
public record AdminUserVO(
        Long id,
        Long tenantId,
        String username,
        String nickname,
        String email,
        String role,
        Integer status,
        LocalDateTime createdAt) {
}
