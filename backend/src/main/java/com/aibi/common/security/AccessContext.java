package com.aibi.common.security;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.DataScope;
import com.aibi.common.tenant.IsolationMode;
import com.aibi.common.tenant.PlanType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 全链路访问上下文：四级主体（平台运营 / 组织租户 / 团队 / 个人）在单次请求内的合并视图，
 * 见《角色权限体系与组织协同设计》2.4。
 *
 * <p>由 {@code JwtAuthFilter} 在请求进入时写入 {@link AccessContextHolder}、请求结束时清理
 * （沿用现有 {@code TenantContext}/{@code UserContext} 的 ThreadLocal 生命周期，杜绝线程复用串上下文）。
 *
 * <p><b>兼容约定</b>：{@code TenantContext} 与 {@code UserContext} 保留为门面并委派到本类，
 * 故 {@code TenantContext.tenantId()} / {@code UserContext.userId()} 等既有调用点<b>零改动</b>。
 *
 * <p><b>相对 §2.4 记录体的扩展</b>：额外携带 {@code isolationMode} 与 {@code sid}/{@code tokenScope}/{@code tokenVersion}。
 * 前者是为了让 {@code TenantContext.get() → set(TenantInfo)} 的快照往返（{@code ChatStreamController} 的 SSE 工作线程）
 * 完全无损；后三者是因为 §6.7 的"移除成员即时生效"要求按会话 id 精确吊销、按 {@code scope=ASSIST}
 * 硬拒绝写操作，二者都必须在过滤器阶段随上下文一起建立。
 */
public record AccessContext(
        /** 组织内成员身份 id（{@code app_user.id}）；方案 A 下 {@code uid} 语义与升级前完全一致。 */
        Long uid,
        /** 自然人身份 id（{@code user_identity.id}）；跨组织归并口令与联系方式。 */
        Long identityId,
        String username,
        /** 租户 id；{@code 0} 表示平台域身份，{@code null} 表示尚无租户上下文（匿名）。 */
        Long tenantId,
        PlanType plan,
        /** 隔离模式；默认由套餐推导，但允许显式指定（企业版可降级为 SCHEMA）。 */
        IsolationMode isolationMode,
        /** 平台角色码集合；仅 {@code tenantId=0} 时非空（见 3.6①，防跨域提权）。 */
        Set<String> platformRoles,
        /** 组织角色码集合（OWNER/ADMIN/...，含自定义角色展开后的 base 码）。 */
        Set<String> orgRoles,
        /** 当前团队及其祖先链上生效的团队角色码集合。 */
        Set<String> teamRoles,
        /** 当前团队上下文（JWT claim {@code team}），可空。 */
        Long currentTeamId,
        /** 该身份所属的全部团队 id（数据范围与 ACL 判定用）。 */
        Set<Long> teamIds,
        /** {@code currentTeamId} 的全部子团队 id（{@code TEAM_AND_CHILD} 范围用）。 */
        Set<Long> childTeamIds,
        /** 有效数据范围：多角色取最宽（见 3.6⑦）。 */
        DataScope dataScope,
        /** 组织权限版本（{@code tenant.perm_version}），角色/权限/成员变更时 +1。 */
        long permVersion,
        /** 身份令牌版本（{@code app_user.token_version}），停用/降级/重置口令时 +1。 */
        long tokenVersion,
        /** 会话 id（{@code login_session.sid}），用于精确吊销。 */
        String sid,
        /** 令牌作用域：NORMAL / ASSIST（代客协助影子令牌）。 */
        TokenScope tokenScope,
        /** 用户持有的全部有效自定义角色 id（来自 {@code user_role.custom_role_id} 去重）；请求期从 JWT claim 取。 */
        Set<Long> customRoleIds
) {

    /** 紧凑构造器：把可空集合统一收敛为不可变空集，避免调用方到处判 null。 */
    public AccessContext {
        platformRoles = immutableOrEmpty(platformRoles);
        orgRoles = immutableOrEmpty(orgRoles);
        teamRoles = immutableOrEmpty(teamRoles);
        teamIds = immutableOrEmptyIds(teamIds);
        childTeamIds = immutableOrEmptyIds(childTeamIds);
        plan = plan == null ? PlanType.FREE : plan;
        isolationMode = isolationMode == null ? plan.defaultIsolationMode() : isolationMode;
        dataScope = dataScope == null ? DataScope.SELF : dataScope;
        tokenScope = tokenScope == null ? TokenScope.NORMAL : tokenScope;
        customRoleIds = immutableOrEmptyIds(customRoleIds);
    }

    private static Set<String> immutableOrEmpty(Set<String> src) {
        return src == null || src.isEmpty() ? Set.of() : Set.copyOf(src);
    }

    /**
     * id 集合的同语义版本。
     *
     * <p>不能与 {@link #immutableOrEmpty(Set)} 同名重载：泛型擦除后两者签名完全相同
     * （都是 {@code immutableOrEmpty(Set)}），javac 会直接拒绝编译。
     */
    private static Set<Long> immutableOrEmptyIds(Set<Long> src) {
        return src == null || src.isEmpty() ? Set.of() : Set.copyOf(src);
    }

    // ==================== 主体判定 ====================

    /** 是否为平台域身份（{@code tenant_id=0}），即运营用户工作台。 */
    public boolean isPlatform() {
        return tenantId != null && tenantId == 0L;
    }

    /** 是否为租户域身份（有真实租户上下文）。 */
    public boolean isTenantScope() {
        return tenantId != null && tenantId > 0L;
    }

    /** 是否为平台超级管理员（保留既有语义，供 {@code UserContext.isSuperAdmin()} 委派）。 */
    public boolean isSuperAdmin() {
        return platformRoles.contains(PlatformRole.SUPER_ADMIN);
    }

    /** 是否持有任一平台角色（前端据此显隐平台管理入口，替代原先的 {@code role=='SUPER_ADMIN'}）。 */
    public boolean hasAnyPlatformRole() {
        return !platformRoles.isEmpty();
    }

    public boolean hasPlatformRole(String code) {
        return code != null && platformRoles.contains(code);
    }

    public boolean hasOrgRole(String code) {
        return code != null && orgRoles.contains(code);
    }

    public boolean hasTeamRole(String code) {
        return code != null && teamRoles.contains(code);
    }

    /** 是否为组织所有者（唯一可转让/解散组织、可无限制分配角色者）。 */
    public boolean isOwner() {
        return orgRoles.contains(OrgRole.OWNER);
    }

    /** 是否具备成员管理能力。 */
    public boolean canManageMembers() {
        return isOwner() || orgRoles.contains(OrgRole.ADMIN);
    }

    /** 是否为代客协助影子令牌（只读 + 硬拒绝清单）。 */
    public boolean isAssist() {
        return tokenScope == TokenScope.ASSIST;
    }

    // ==================== 版本与范围 ====================

    /**
     * 签发/校验用的合并版本号（JWT claim {@code pv}）= {@code tenant.perm_version + app_user.token_version}。
     *
     * <p>两者相加即可覆盖"组织级授权变更"与"身份级敏感变更"两类吊销场景；
     * 单调递增由各自 {@code ++} 保证，无需额外的全局序列。
     */
    public long sessionVersion() {
        return permVersion + tokenVersion;
    }

    /** 数据范围可覆盖的团队集合（{@code TEAM_AND_CHILD} 时含子团队），供 SQL 改写与 ACL 判定复用。 */
    public Set<Long> scopeTeamIds() {
        if (dataScope == DataScope.TEAM_AND_CHILD) {
            Set<Long> merged = new LinkedHashSet<>(teamIds);
            if (currentTeamId != null) {
                merged.add(currentTeamId);
            }
            merged.addAll(childTeamIds);
            return Set.copyOf(merged);
        }
        if (dataScope == DataScope.TEAM && currentTeamId != null) {
            return Set.of(currentTeamId);
        }
        return teamIds;
    }

    // ==================== 强约束取值（缺失即拒绝，而非放行） ====================

    public Long requireUid() {
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少用户上下文");
        }
        return uid;
    }

    /** 强隔离路径必须存在租户上下文，否则直接抛出，避免"无租户"越权访问。 */
    public Long requireTenantId() {
        if (tenantId == null) {
            throw new BizException(ErrorCode.TENANT_MISSING, "缺少租户上下文，禁止访问租户数据");
        }
        return tenantId;
    }

    /** 团队级接口必须已选定团队，否则返回 40306（前端据此弹团队选择器）。 */
    public Long requireTeamId() {
        if (currentTeamId == null) {
            throw new BizException(ErrorCode.TEAM_MISSING, "请先选择团队后再执行该操作");
        }
        return currentTeamId;
    }

    // ==================== 派生副本（切换团队等场景，避免重建整个上下文） ====================

    /** 切换当前团队：同时清空按旧团队算出的子团队集合（由 {@code AccessContextBuilder} 重新填充）。 */
    public AccessContext withCurrentTeam(Long newTeamId) {
        return new AccessContext(uid, identityId, username, tenantId, plan, isolationMode, platformRoles,
                orgRoles, teamRoles, newTeamId, teamIds, Set.of(), dataScope, permVersion, tokenVersion,
                sid, tokenScope, customRoleIds);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 以当前值为起点构建副本：门面类（{@code TenantContext}/{@code UserContext}）的增量写入靠它合并。 */
    public Builder toBuilder() {
        return new Builder().uid(uid).identityId(identityId).username(username).tenantId(tenantId)
                .plan(plan).isolationMode(isolationMode).platformRoles(platformRoles).orgRoles(orgRoles)
                .teamRoles(teamRoles).currentTeamId(currentTeamId).teamIds(teamIds).childTeamIds(childTeamIds)
                .dataScope(dataScope)                .permVersion(permVersion).tokenVersion(tokenVersion)
                .sid(sid).tokenScope(tokenScope).customRoleIds(customRoleIds);
    }

    /**
     * 可变构建器：本记录有 16 个组件，直接调用规范构造器既易错也难读，
     * 故登录链路与 {@code AccessContextBuilder} 统一走 builder，未设置的集合默认为空集。
     */
    public static final class Builder {

        private Long uid;
        private Long identityId;
        private String username;
        private Long tenantId;
        private PlanType plan;
        private IsolationMode isolationMode;
        private Set<String> platformRoles;
        private Set<String> orgRoles;
        private Set<String> teamRoles;
        private Long currentTeamId;
        private Set<Long> teamIds;
        private Set<Long> childTeamIds;
        private DataScope dataScope;
        private long permVersion;
        private long tokenVersion;
        private String sid;
        private TokenScope tokenScope;
        private Set<Long> customRoleIds;

        private Builder() {
        }

        public Builder uid(Long v) {
            this.uid = v;
            return this;
        }

        public Builder identityId(Long v) {
            this.identityId = v;
            return this;
        }

        public Builder username(String v) {
            this.username = v;
            return this;
        }

        public Builder tenantId(Long v) {
            this.tenantId = v;
            return this;
        }

        public Builder plan(PlanType v) {
            this.plan = v;
            return this;
        }

        public Builder isolationMode(IsolationMode v) {
            this.isolationMode = v;
            return this;
        }

        public Builder platformRoles(Set<String> v) {
            this.platformRoles = v;
            return this;
        }

        public Builder orgRoles(Set<String> v) {
            this.orgRoles = v;
            return this;
        }

        public Builder teamRoles(Set<String> v) {
            this.teamRoles = v;
            return this;
        }

        public Builder currentTeamId(Long v) {
            this.currentTeamId = v;
            return this;
        }

        public Builder teamIds(Set<Long> v) {
            this.teamIds = v;
            return this;
        }

        public Builder childTeamIds(Set<Long> v) {
            this.childTeamIds = v;
            return this;
        }

        public Builder dataScope(DataScope v) {
            this.dataScope = v;
            return this;
        }

        public Builder permVersion(long v) {
            this.permVersion = v;
            return this;
        }

        public Builder tokenVersion(long v) {
            this.tokenVersion = v;
            return this;
        }

        public Builder sid(String v) {
            this.sid = v;
            return this;
        }

        public Builder tokenScope(TokenScope v) {
            this.tokenScope = v;
            return this;
        }

        public Builder customRoleIds(Set<Long> v) {
            this.customRoleIds = v;
            return this;
        }

        public AccessContext build() {
            return new AccessContext(uid, identityId, username, tenantId, plan, isolationMode, platformRoles,
                    orgRoles, teamRoles, currentTeamId, immutableOrEmptyIds(teamIds),
                    immutableOrEmptyIds(childTeamIds), dataScope, permVersion, tokenVersion, sid, tokenScope,
                    immutableOrEmptyIds(customRoleIds));
        }
    }
}
