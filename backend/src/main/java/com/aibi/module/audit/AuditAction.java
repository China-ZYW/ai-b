package com.aibi.module.audit;

/**
 * 审计动作码与结果码常量（《角色权限体系与组织协同设计》10.1 必审事件清单）。
 *
 * <p>{@code auth_audit_log.action} 为 {@code VARCHAR(48)} 自由文本、无 DB 约束，故用常量集中管理，
 * 避免各写侧散落魔法串。下列仅收录<b>本轮已埋点</b>会真实产生的动作 + 审计查询自审动作；
 * 完整清单（认证/鉴权/成员/邀请/组织/协助各类）见设计文档 10.1，随对应模块落地增量补齐。
 */
public final class AuditAction {

    private AuditAction() {
    }

    // ==================== 认证（10.1，UserServiceImpl 登录/登出） ====================

    /** 登录成功（{@code UserServiceImpl.login}）。 */
    public static final String LOGIN = "LOGIN";
    /** 登出（{@code UserServiceImpl.logout}，会话被吊销）。 */
    public static final String LOGOUT = "LOGOUT";
    /** 登录失败（口令错误/账号禁用/租户停用或过期）；detail 记原因，<b>绝不记口令</b>。 */
    public static final String LOGIN_FAILED = "LOGIN_FAILED";

    // ==================== 鉴权（10.1，PermissionAspect 拒绝出口） ====================

    /** 功能权限点/角色校验被拒（{@code PermissionAspect.deny}）。 */
    public static final String PERM_CHECK_DENIED = "PERM_CHECK_DENIED";
    /** 数据范围收敛拒绝（团队/可见性越界，P2 数据范围接入后产生）。 */
    public static final String DATA_SCOPE_DENIED = "DATA_SCOPE_DENIED";
    /** 资源行级归属拒绝（{@code CheckResourceAccess}，含 ASSIST 写红线）。 */
    public static final String ACL_DENIED = "ACL_DENIED";

    // ==================== 已埋点（平台域写侧） ====================

    /** 平台角色授予（{@code PlatformRoleService.grant}）。 */
    public static final String PLATFORM_ROLE_GRANT = "PLATFORM_ROLE_GRANT";
    /** 平台角色回收（{@code PlatformRoleService.revoke}）。 */
    public static final String PLATFORM_ROLE_REVOKE = "PLATFORM_ROLE_REVOKE";
    /** 席位/配额变更（{@code TenantAdminService.updateSeats}），detail 记变更前后值。 */
    public static final String SEAT_CHANGE = "SEAT_CHANGE";
    /** 租户停用（{@code TenantAdminService.setStatus(0)}）。 */
    public static final String TENANT_DISABLE = "TENANT_DISABLE";

    // ==================== 审计自审（10.2：看日志的人也要被看） ====================

    /** 审计日志导出（{@code AdminAuditController.export}）。 */
    public static final String AUDIT_EXPORT = "AUDIT_EXPORT";
    /** 审计自然语言查询（T11-3，{@code *AuditNlQueryController} 双域入口）：谁用 AI 查了审计、原句与解析槽位进 detail。 */
    public static final String AUDIT_NL_QUERY = "AUDIT_NL_QUERY";

    // ==================== 成员与组织（10.1，MemberService 写侧） ====================

    /** 移除成员（{@code MemberService.remove/selfLeave}：置 {@code app_user.status=0} 并吊销会话）。 */
    public static final String MEMBER_REMOVE = "MEMBER_REMOVE";
    /** 组织角色授予（{@code MemberService.assignRole}）。 */
    public static final String ROLE_GRANT = "ROLE_GRANT";
    /** 组织角色回收（{@code MemberService.revokeRole}）。 */
    public static final String ROLE_REVOKE = "ROLE_REVOKE";
    /** 重置成员口令（{@code MemberService.resetPassword}）；detail 绝不记口令明文。 */
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    /** 转让组织所有权（{@code MemberService.transferOwnership}，仅 OWNER）。 */
    public static final String OWNER_TRANSFER = "OWNER_TRANSFER";

    // ==================== 自定义角色（10.1，CustomRoleService 写侧，《组织治理专项设计》§2） ====================

    /** 新建自定义角色（{@code CustomRoleService.create}）。 */
    public static final String CUSTOM_ROLE_CREATE = "CUSTOM_ROLE_CREATE";
    /** 更新自定义角色（改名/描述/启用/停用/data_scope，{@code CustomRoleService.update}）。 */
    public static final String CUSTOM_ROLE_UPDATE = "CUSTOM_ROLE_UPDATE";
    /** 删除自定义角色（{@code CustomRoleService.delete}，在册成员持有时拒）。 */
    public static final String CUSTOM_ROLE_DELETE = "CUSTOM_ROLE_DELETE";
    /** 设置自定义角色权限覆盖（GRANT/DENY，{@code CustomRoleService.setOverrides}）。 */
    public static final String CUSTOM_ROLE_PERM_UPDATE = "CUSTOM_ROLE_PERM_UPDATE";

    // ==================== 数据权限 A（《组织治理专项设计》§3，DataAclService 写侧） ====================

    /** 设置数据授权（成员/角色的数据源可见性 HIDDEN/ALLOW 或字段脱敏 MASK/UNMASK，{@code DataAclService.setAcl}）。 */
    public static final String DATA_ACL_SET = "DATA_ACL_SET";
    /** 清除数据授权（{@code DataAclService.clearAcl}）。 */
    public static final String DATA_ACL_CLEAR = "DATA_ACL_CLEAR";

    // ==================== 邀请（10.1，InvitationService 写侧） ====================

    /** 发起邀请（{@code InvitationService.create}）；token 只存哈希，明文仅返回一次。 */
    public static final String INVITE_CREATE = "INVITE_CREATE";
    /** 撤销邀请（{@code InvitationService.revoke}）。 */
    public static final String INVITE_REVOKE = "INVITE_REVOKE";
    /** 接受邀请（{@code InvitationService.accept}）：被邀请者加入组织、占用席位、授予角色）。 */
    public static final String INVITE_ACCEPT = "INVITE_ACCEPT";

    // ==================== 智能预警（10.1，AlertRuleService / AlertEngineService 写侧） ====================

    /** 预警规则创建（{@code AlertRuleService.create}）。 */
    public static final String ALERT_RULE_CREATE = "ALERT_RULE_CREATE";
    /** 预警规则更新（{@code AlertRuleService.update}）。 */
    public static final String ALERT_RULE_UPDATE = "ALERT_RULE_UPDATE";
    /** 预警规则删除（{@code AlertRuleService.delete}）。 */
    public static final String ALERT_RULE_DELETE = "ALERT_RULE_DELETE";
    /** 预警规则命中触发（{@code AlertEngineService.fire}，落 {@code alert_record} + 通知）。 */
    public static final String ALERT_FIRED = "ALERT_FIRED";

    /** 组织切换（{@code IdentityService.switchTenant}）：统一身份下切换当前生效组织，重新签发令牌。 */
    public static final String ORG_SWITCH = "ORG_SWITCH";

    /** 对外 MCP 工具调用（{@code POST /mcp} 的 tools/call）：谁、哪个组织、用什么凭据、调了什么工具。 */
    public static final String MCP_TOOL_CALL = "MCP_TOOL_CALL";

    // ==================== 嵌入问数（10.1，ChatEmbedService 匿名访问侧） ====================

    /** 嵌入问数触发（{@code ChatEmbedService.query}）：匿名问数，操作人记嵌入创建人，detail 记外部用户/来源。 */
    public static final String EMBED_QUERY = "EMBED_QUERY";

    // ==================== 结果码 ====================

    /** 成功。 */
    public static final String RESULT_OK = "OK";
    /** 被拒绝（鉴权/护栏拦截）。 */
    public static final String RESULT_DENIED = "DENIED";
    /** 失败（业务异常）。 */
    public static final String RESULT_FAILED = "FAILED";
}
