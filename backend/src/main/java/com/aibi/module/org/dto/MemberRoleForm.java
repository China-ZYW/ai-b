package com.aibi.module.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 组织角色分配/回收表单（{@code member:role:assign}，矩阵 YYNNNN → 仅 OWNER/ADMIN）。
 *
 * <p>{@code roleCode} 必须是 {@link com.aibi.common.security.OrgRole} 之一且<b>可授予</b>：
 * {@code OWNER} 属 {@code OrgRole.NON_GRANTABLE}，只能走转让流程（{@code org:org:transfer}），
 * 直接授予/回收会被服务层拒绝（防「自建角色自我提权」与「绕过转让废掉所有者」）。
 *
 * <p>{@code customRoleId}（可选）：非空表示授予的是一个<b>租户自定义角色</b>
 * （《组织治理专项设计》§2）。此时 {@code roleCode} 实际写该自定义角色的 base 码，
 * {@code user_role.custom_role_id} 写其引用（自定义角色引擎据此施加 GRANT/DENY 覆盖）。
 * 分配受「不可授予超出自身权限的角色」护栏（{@code ROLE_GRANT_ESCALATION}）。
 */
public record MemberRoleForm(
        @NotNull(message = "成员不能为空") Long uid,
        @NotBlank(message = "角色不能为空") String roleCode,
        Long customRoleId) {
}
