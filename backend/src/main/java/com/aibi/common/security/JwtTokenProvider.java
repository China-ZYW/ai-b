package com.aibi.common.security;

import com.aibi.common.tenant.PlanType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 令牌生成与解析（jjwt 0.12.x API，HS256）。
 *
 * <p>claim 清单见《角色权限体系与组织协同设计》6.3：
 * {@code sub, uid, iid, tid, plan, prole, trole, team, scope, sid, pv, tv, iat, exp}。
 * 旧 claim {@code role} 仍继续写入（兼容 {@code aibi.rbac.enabled=false} 的应急回退与旧日志），
 * 但新判定一律读 {@code prole}/{@code trole}。
 *
 * <p><b>权限点不进令牌</b>：一个 OWNER 展开后有近百个权限码，塞进令牌会让每个请求的 Header
 * 膨胀到数 KB，且改一次授权就要等令牌自然过期才生效。令牌只带角色码 + 版本号 {@code pv}，
 * 权限点由服务端按 {@code 角色码集合 + pv} 解析并缓存。
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expireMillis;

    public JwtTokenProvider(@Value("${aibi.jwt.secret}") String secret,
                            @Value("${aibi.jwt.expire-minutes:120}") long expireMinutes) {
        // HS256 要求密钥 >= 256 bit（32 字节）
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60 * 1000;
    }

    /**
     * 签发结果：明文令牌只在响应里出现一次，{@code sid} 与到期时间用于登记 {@code login_session}。
     *
     * @param accessToken 明文访问令牌
     * @param sid         会话 id（写入 {@code login_session.sid} 与 claim {@code sid}）
     * @param expiresAt   到期时间，须与 {@code login_session.expire_at} 一致，否则清理任务会误删活会话
     */
    public record IssuedToken(String accessToken, String sid, Date issuedAt, Date expiresAt) {
    }

    /**
     * 按访问上下文签发常规登录令牌（{@code scope=NORMAL}）。
     *
     * <p>调用方须先给上下文填好 {@code sid}（{@code TokenDigest.newSessionId()}），
     * 因为 {@code sid} 既要进 claim 也要进 {@code login_session}，两处必须是同一个值。
     */
    public IssuedToken issue(AccessContext context) {
        return issue(context, TokenScope.NORMAL, expireMillis);
    }

    /**
     * 签发令牌，可指定作用域与 TTL。
     *
     * <p>代客协助（{@code scope=ASSIST}）走本重载并传更短的 TTL（设计上限 2h），
     * 且必须写入 {@code login_session} 以便随时吊销——协助令牌泄露的影响面是整个组织的数据。
     *
     * @param ttlMillis 存活时长；{@code <=0} 时回退到配置的默认值，避免签出即刻过期的令牌
     */
    public IssuedToken issue(AccessContext context, TokenScope scope, long ttlMillis) {
        long effectiveTtl = ttlMillis > 0 ? ttlMillis : expireMillis;
        Date now = new Date();
        Date expiration = new Date(now.getTime() + effectiveTtl);
        TokenScope effectiveScope = scope == null ? TokenScope.NORMAL : scope;
        String token = Jwts.builder()
                .subject(context.username())
                .claim("uid", context.uid())
                .claim("iid", context.identityId())
                .claim("tid", context.tenantId())
                .claim("plan", context.plan() == null ? PlanType.FREE.name() : context.plan().name())
                .claim("prole", TokenPrincipal.joinRoles(context.platformRoles()))
                .claim("trole", TokenPrincipal.joinRoles(context.orgRoles()))
                .claim("team", context.currentTeamId())
                .claim("scope", effectiveScope.name())
                .claim("sid", context.sid())
                .claim("pv", context.sessionVersion())
                // 格式版本：旧令牌无此 claim，据此区分"旧格式"与"新格式但无角色"，避免回退时误提权
                .claim("tv", TokenPrincipal.CURRENT_TOKEN_FORMAT)
                // 自定义角色 id 集合：请求期零 DB 重建时据此施加 DENY 收窄（见《组织治理专项设计》§2.3）
                .claim("crid", TokenPrincipal.joinLongs(context.customRoleIds()))
                // 兼容 claim：aibi.rbac.enabled=false 时回退到二元角色判定，也供旧日志格式使用
                .claim("role", legacyRole(context))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
        return new IssuedToken(token, context.sid(), now, expiration);
    }

    /**
     * 解析并验签。
     *
     * @throws io.jsonwebtoken.JwtException 令牌无效 / 过期 / 签名不符
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 解析为强类型载荷（验签同 {@link #parse}）。
     *
     * <p>数值 claim 一律经 {@link #toLong} 转换：jjwt 会按 JSON 数字大小返回 {@code Integer} 或
     * {@code Long}，直接强转 {@code (Long) claims.get("uid")} 会在小数值时抛 {@code ClassCastException}，
     * 表现为"某些用户登录即 401"，与权限无关却极难联想到类型。
     *
     * @throws io.jsonwebtoken.JwtException 令牌无效 / 过期 / 签名不符
     */
    public TokenPrincipal parsePrincipal(String token) {
        Claims claims = parse(token);
        return new TokenPrincipal(
                toLong(claims.get("uid")),
                toLong(claims.get("iid")),
                claims.getSubject(),
                toLong(claims.get("tid")),
                PlanType.parse(claims.get("plan", String.class)),
                TokenPrincipal.parseRoles(claims.get("prole", String.class)),
                TokenPrincipal.parseRoles(claims.get("trole", String.class)),
                toLong(claims.get("team")),
                claims.get("sid", String.class),
                TokenScope.parse(claims.get("scope", String.class)),
                toLongOrZero(claims.get("pv")),
                toIntOrZero(claims.get("tv")),
                claims.get("role", String.class),
                TokenPrincipal.parseLongs(claims.get("crid", String.class)),
                claims.getIssuedAt(),
                claims.getExpiration());
    }

    /** 令牌默认存活时长（毫秒），供需要自行换算过期时间的调用方使用。 */
    public long getExpireMillis() {
        return expireMillis;
    }

    /** 兼容投影：平台身份取 {@code SUPER_ADMIN}，其余取 {@code USER}（与升级前的 {@code app_user.role} 同值域）。 */
    private String legacyRole(AccessContext context) {
        return context.isSuperAdmin() ? PlatformRole.SUPER_ADMIN : UserContext.ROLE_USER;
    }

    private Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private long toLongOrZero(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private int toIntOrZero(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
