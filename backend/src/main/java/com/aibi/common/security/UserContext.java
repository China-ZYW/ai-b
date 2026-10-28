package com.aibi.common.security;

import java.util.Set;

/**
 * 当前登录用户上下文门面。
 *
 * <p><b>已升级为委派</b>：真实存放点是 {@link AccessContextHolder} 中的 {@link AccessContext}。
 * 本类保留全部既有方法签名（{@code userId()} / {@code username()} / {@code isSuperAdmin()} /
 * {@link CurrentUser} / {@link #ROLE_SUPER_ADMIN}），故既有调用点<b>零改动</b>。
 *
 * <p>{@link #role()} 是<b>兼容投影</b>：升级前 {@code app_user.role} 只有 {@code SUPER_ADMIN}/{@code USER}
 * 二元值，而 RBAC 下一个身份可同时持有多个平台/组织/团队角色。此处按"平台 &gt; 组织 &gt; 团队"的优先级
 * 返回单个主要角色码，仅供日志与旧判定分支使用；<b>新代码一律改用 {@link AccessContextHolder#require()}
 * 取完整角色集合</b>，不要依赖单值 {@code role()} 做权限判定。
 *
 * <p>由 {@code JwtAuthFilter} 在请求进入时写入、请求结束时清理。
 */
public final class UserContext {

    /** 平台超级管理员角色标识（既有常量，等价于 {@link PlatformRole#SUPER_ADMIN}）。 */
    public static final String ROLE_SUPER_ADMIN = PlatformRole.SUPER_ADMIN;

    /** 升级前既有的普通用户角色标识；RBAC 下不再是权限判据，仅作为兼容投影的兜底值。 */
    public static final String ROLE_USER = "USER";

    /** 当前用户最小信息（门面投影）。 */
    public record CurrentUser(Long id, String username, String role) {

        /** 是否为平台超级管理员（可跨租户管理）。 */
        public boolean isSuperAdmin() {
            return ROLE_SUPER_ADMIN.equals(role);
        }
    }

    private UserContext() {
    }

    /**
     * 增量写入用户维度（保留已建立的租户/团队信息）。
     *
     * <p>{@code role} 按兼容规则还原为角色集合：平台角色码归 {@code platformRoles}，
     * 其余非空且非 {@code USER} 的码归 {@code orgRoles}。多角色场景请改用
     * {@link AccessContextHolder#set(AccessContext)} 写入完整上下文，避免丢失角色。
     */
    public static void set(CurrentUser user) {
        if (user == null) {
            AccessContextHolder.clear();
            return;
        }
        String role = user.role();
        Set<String> platformRoles = PlatformRole.isPlatform(role) ? Set.of(role) : Set.of();
        Set<String> orgRoles = platformRoles.isEmpty() && role != null && !role.isBlank()
                && !ROLE_USER.equals(role) ? Set.of(role) : Set.of();
        AccessContextHolder.mergeUser(user.id(), user.username(), null, platformRoles, orgRoles);
    }

    /** 当前用户投影；无用户上下文（匿名，或仅建立了租户维度）时返回 {@code null}。 */
    public static CurrentUser get() {
        AccessContext ctx = AccessContextHolder.get();
        if (ctx == null || ctx.uid() == null) {
            return null;
        }
        return new CurrentUser(ctx.uid(), ctx.username(), primaryRole(ctx));
    }

    public static Long userId() {
        return AccessContextHolder.getOrDefault().uid();
    }

    public static String username() {
        return AccessContextHolder.getOrDefault().username();
    }

    /** 当前自然人身份 id（{@code user_identity.id}）；存量上下文可能为空。 */
    public static Long identityId() {
        return AccessContextHolder.getOrDefault().identityId();
    }

    /** 兼容投影：单个主要角色码，无角色时返回 {@link #ROLE_USER}。 */
    public static String role() {
        return primaryRole(AccessContextHolder.getOrDefault());
    }

    /** 当前登录者是否为平台超级管理员。 */
    public static boolean isSuperAdmin() {
        return AccessContextHolder.getOrDefault().isSuperAdmin();
    }

    /**
     * 清理当前线程的访问上下文（含租户/团队维度）。
     *
     * <p>与 {@code TenantContext.clear()} 指向同一存放点，重复调用无害。
     */
    public static void clear() {
        AccessContextHolder.clear();
    }

    /** 按"平台 &gt; 组织 &gt; 团队"优先级取单个主要角色码，用于兼容投影与日志。 */
    private static String primaryRole(AccessContext ctx) {
        String hit = firstByPriority(ctx.platformRoles(), PlatformRole.ALL);
        if (hit != null) {
            return hit;
        }
        hit = firstByPriority(ctx.orgRoles(), OrgRole.ALL);
        if (hit != null) {
            return hit;
        }
        hit = firstByPriority(ctx.teamRoles(), TeamRole.ALL);
        return hit == null ? ROLE_USER : hit;
    }

    /** 按 {@code priority} 数组顺序返回集合中命中的第一个角色码（数组顺序即权限宽窄降序）。 */
    private static String firstByPriority(Set<String> owned, String[] priority) {
        if (owned == null || owned.isEmpty()) {
            return null;
        }
        for (String code : priority) {
            if (owned.contains(code)) {
                return code;
            }
        }
        return owned.iterator().next();
    }
}
