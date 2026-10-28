package com.aibi.module.admin.dto;

import java.time.LocalDateTime;

/**
 * 平台角色持有者视图对象（§9.2 治理组）。
 *
 * <p>{@code user_role}（{@code tenant_id=0 / scope_type=PLATFORM}）与 {@code app_user} 的联接投影，
 * 供平台角色授予/回收页渲染。绝不含密码哈希。
 *
 * @param id        {@code user_role} 行主键，回收时按此精确定位
 * @param uid       被授权账号 {@code app_user.id}
 * @param username  账号登录名
 * @param nickname  账号昵称
 * @param email     账号邮箱
 * @param roleCode  平台角色码（SUPER_ADMIN/OPERATOR/AUDITOR/SUPPORT）
 * @param grantedAt 授予时间（{@code user_role.created_at}）
 * @param expireAt  到期时间（平台角色恒为 {@code null} 表示永久）
 */
public record PlatformRoleHolderVO(
        Long id,
        Long uid,
        String username,
        String nickname,
        String email,
        String roleCode,
        LocalDateTime grantedAt,
        LocalDateTime expireAt) {
}
