package com.aibi.module.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 平台角色授予表单（§9.2 治理组 · 平台角色授予/回收）。
 *
 * <p>{@code uid} 必须是<b>平台账号</b>（{@code app_user.tenant_id=0}）：平台角色只在平台身份上生效，
 * 授予给租户账号是空操作（{@code AccessContextBuilder.resolvePlatformRoles} 仅在 {@code tenantId==0} 时调用），
 * 故服务层会显式拒绝。{@code roleCode} 必须是 {@link com.aibi.common.security.PlatformRole} 之一。
 *
 * <p>平台角色一律永久授予（无到期），故本表单不含 {@code expireAt}。
 */
public record PlatformRoleGrantForm(
        @NotNull(message = "被授权账号不能为空") Long uid,
        @NotBlank(message = "平台角色不能为空") String roleCode) {
}
