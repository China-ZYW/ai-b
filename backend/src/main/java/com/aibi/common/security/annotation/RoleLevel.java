package com.aibi.common.security.annotation;

/**
 * 角色校验的层级（见《角色权限体系与组织协同设计》3.1 / 6.2）。
 *
 * <p>三层角色<b>互不覆盖</b>：{@code @RequiresRole} 按 {@code level} 只比对对应层的角色集
 * （PLATFORM→{@code ctx.platformRoles()}、TENANT→{@code ctx.orgRoles()}、TEAM→{@code ctx.teamRoles()}），
 * 避免"组织 ADMIN"误命中平台域的 {@code hasRole("ADMIN")} 这类跨层提权。
 */
public enum RoleLevel {

    /** 平台域角色（{@code tenant_id=0} 的运营身份）。 */
    PLATFORM,

    /** 组织域角色（OWNER/ADMIN/DATA_STEWARD/ANALYST/VIEWER/BILLING）。 */
    TENANT,

    /** 团队域角色（TEAM_OWNER/TEAM_EDITOR/TEAM_VIEWER）。 */
    TEAM
}
