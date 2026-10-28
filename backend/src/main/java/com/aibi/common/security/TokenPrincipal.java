package com.aibi.common.security;

import com.aibi.common.tenant.PlanType;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JWT 载荷的强类型投影（见《角色权限体系与组织协同设计》6.3 的 claim 清单）。
 *
 * <p>由 {@code JwtTokenProvider.parsePrincipal} 从 {@code Claims} 转换而来，
 * 让 {@code JwtAuthFilter} 与 {@code AccessContextBuilder} 面对具名字段而非
 * {@code claims.get("tid")} 这类字符串魔法值——claim 名写错在编译期无感，运行期表现为
 * "字段恒为 null"，是最难查的一类认证故障。
 *
 * <p><b>刻意不放进 JWT 的东西</b>：权限点集合（一个 OWNER 可展开出近百个码，塞进令牌会让
 * 每个请求的 Header 膨胀到数 KB，且改一次权限就要等令牌过期）、团队成员关系（变更需即时生效）、
 * 任何口令或凭证。权限点在服务端按 {@code 角色码集合 + perm_version} 解析并缓存。
 *
 * @param platformRoles 平台角色码集合（claim {@code prole}，逗号分隔）；租户身份必为空集
 * @param orgRoles      组织角色码集合（claim {@code trole}，逗号分隔）
 * @param currentTeamId 签发时选定的团队（claim {@code team}），可空
 * @param sid           会话 id（claim {@code sid}）；存量旧令牌无此 claim，为 {@code null}
 * @param tokenScope    令牌作用域（claim {@code scope}）；缺省 NORMAL
 * @param permVersion   合并版本号（claim {@code pv}）= {@code tenant.perm_version + app_user.token_version}
 * @param tokenFormat   令牌格式版本（claim {@code tv}）：新签发为 {@link #CURRENT_TOKEN_FORMAT}，
 *                      升级前的存量令牌无此 claim 而为 {@code 0}。它是"角色 claim 是否可信"的<b>唯一判据</b>：
 *                      不能用 {@code prole/trole 为空} 来判定旧令牌，因为"新令牌 + 真的没有任何角色"
 *                      也是空集，两者一旦混淆就会把无角色用户回退成 OWNER（静默提权）
 * @param legacyRole    兼容字段（claim {@code role}）：升级前的 {@code SUPER_ADMIN}/{@code USER} 二元值，
 *                      仅用于 {@code aibi.rbac.enabled=false} 的应急回退与旧日志格式，不参与新判定
 * @param customRoleIds 用户持有的自定义角色 id 集合（claim {@code crid}，逗号分隔的 Long）；
 *                      用于请求期零 DB 重建时施加自定义角色的 DENY 收窄（见《组织治理专项设计》§2.3）。
 *                      旧格式令牌无此 claim，为空集。
 */
public record TokenPrincipal(
        Long uid,
        Long identityId,
        String username,
        Long tenantId,
        PlanType plan,
        Set<String> platformRoles,
        Set<String> orgRoles,
        Long currentTeamId,
        String sid,
        TokenScope tokenScope,
        long permVersion,
        int tokenFormat,
        String legacyRole,
        Set<Long> customRoleIds,
        Date issuedAt,
        Date expiresAt
) {

    /** 当前令牌格式版本：带齐 {@code prole/trole/team/scope/sid/pv} 的签发均为此值。 */
    public static final int CURRENT_TOKEN_FORMAT = 2;

    public TokenPrincipal {
        platformRoles = immutableOrEmpty(platformRoles);
        orgRoles = immutableOrEmpty(orgRoles);
        customRoleIds = immutableOrEmptyIds(customRoleIds);
        plan = plan == null ? PlanType.FREE : plan;
        tokenScope = tokenScope == null ? TokenScope.NORMAL : tokenScope;
    }

    private static Set<String> immutableOrEmpty(Set<String> src) {
        return src == null || src.isEmpty() ? Set.of() : Set.copyOf(src);
    }

    private static Set<Long> immutableOrEmptyIds(Set<Long> src) {
        return src == null || src.isEmpty() ? Set.of() : Set.copyOf(src);
    }

    /**
     * 逗号分隔的角色码串 → 集合（保序去重，忽略空段）。
     *
     * <p>容错而非抛错：令牌里出现空串或多余逗号时按"该段无效"处理，
     * 因为此处抛异常会让整个请求变成 401，而实际只是某个角色码写空了。
     */
    public static Set<String> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String code = part.trim();
            if (!code.isEmpty()) {
                roles.add(code);
            }
        }
        return roles.isEmpty() ? Set.of() : Set.copyOf(roles);
    }

    /** 集合 → 逗号分隔串（写入 claim；空集写空串而非 null，避免 claim 缺失与"空角色"不可区分）。 */
    public static String joinRoles(Set<String> roles) {
        return roles == null || roles.isEmpty() ? "" : String.join(",", roles);
    }

    /** 逗号分隔的 Long 串 → 集合（保序去重，忽略空段与非法值）。容错而非抛错：令牌里出现脏值不应让请求 401。 */
    public static Set<Long> parseLongs(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                try {
                    ids.add(Long.valueOf(s));
                } catch (NumberFormatException ignored) {
                    // 脏值忽略，不阻断解析
                }
            }
        }
        return ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    /** Long 集合 → 逗号分隔串（写入 {@code crid} claim；空集写空串）。 */
    public static String joinLongs(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    /** 是否为平台域身份（{@code tid=0}）。 */
    public boolean isPlatform() {
        return tenantId != null && tenantId == 0L;
    }

    /** 是否为代客协助影子令牌。 */
    public boolean isAssist() {
        return tokenScope == TokenScope.ASSIST;
    }

    /**
     * 是否为升级前签发的旧格式令牌（无 {@code tv} claim）：角色集合不可信，
     * 需按 {@link #legacyRole()} 回退解析（见 {@code AccessContextBuilder.buildForRequest}）。
     */
    public boolean isLegacyFormat() {
        return tokenFormat < CURRENT_TOKEN_FORMAT;
    }

    /** 是否为无会话登记的令牌（无 {@code sid} claim）：这类令牌跳过会话吊销校验。 */
    public boolean isLegacyToken() {
        return sid == null || sid.isBlank();
    }
}
