package com.aibi.module.mcp.service;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextBuilder;
import com.aibi.common.security.JwtTokenProvider;
import com.aibi.common.security.TokenPrincipal;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.mcp.config.McpProperties;
import com.aibi.module.mcp.entity.McpPat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * MCP 令牌解析：把 {@code Authorization: Bearer} 里的凭据映射成本系统的 {@link AccessContext}。
 *
 * <p>《对外 MCP 能力方案》4.1 把这一步定为"最重要的一节"——它决定了 MCP 调用的安全语义：
 * <b>以调用者的真实身份与真实角色执行</b>，因此权限点校验、行级范围、字段脱敏、配额、审计
 * 全部自动生效，MCP 不会成为绕过治理的旁路。刻意<b>不做"MCP 专用超级权限"</b>。
 *
 * <p>支持的凭据（按序尝试）：
 * <ol>
 *   <li><b>PAT</b>：以 {@value McpPatService#PREFIX} 开头，按 SHA-256 哈希查库（可即时吊销）；</li>
 *   <li><b>OAuth 2.1 Bearer JWT</b>：配置了 {@code aibi.mcp.jwks-url} 时按 JWKS 验 RS256，
 *       并校验 {@code iss} / {@code aud}（RFC 8707 resource）/ {@code exp}；</li>
 *   <li><b>本系统登录 JWT</b>：{@code allow-self-jwt=true} 时接受（HS256，复用既有身份）。</li>
 * </ol>
 *
 * <p>账号侧状态每次都重新读（停用/改密后立即失效），不缓存已解析的身份。
 */
@Service
public class McpTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(McpTokenResolver.class);

    /** 认证方式（审计留痕用）。 */
    public enum Method {
        PAT, JWT
    }

    /** 解析结果：访问上下文 + 认证方式 + 审计用主体标识。 */
    public record Identity(AccessContext context, Method method, String subject, Long patId) {
    }

    private final McpProperties props;
    private final McpPatService patService;
    private final McpJwtVerifier jwtVerifier;
    private final JwtTokenProvider tokenProvider;
    private final AccessContextBuilder contextBuilder;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;

    public McpTokenResolver(McpProperties props,
                            McpPatService patService,
                            McpJwtVerifier jwtVerifier,
                            JwtTokenProvider tokenProvider,
                            AccessContextBuilder contextBuilder,
                            UserMapper userMapper,
                            TenantMapper tenantMapper) {
        this.props = props;
        this.patService = patService;
        this.jwtVerifier = jwtVerifier;
        this.tokenProvider = tokenProvider;
        this.contextBuilder = contextBuilder;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
    }

    /**
     * 解析 {@code Authorization} 头。
     *
     * @param header 原始头值（可为 null）
     * @return 已绑定到真实用户的身份
     * @throws McpAuthException 凭据缺失/无效/过期/账号不可用
     */
    public Identity resolve(String header) {
        String token = bearer(header);
        if (token == null) {
            throw new McpAuthException("缺少 Authorization: Bearer 凭据");
        }
        if (token.startsWith(McpPatService.PREFIX)) {
            return fromPat(token);
        }
        return fromJwt(token);
    }

    // ==================== PAT ====================

    private Identity fromPat(String token) {
        McpPat pat = patService.verify(token);
        if (pat == null) {
            throw new McpAuthException("令牌无效、已吊销或已过期");
        }
        User user = userMapper.selectById(pat.getUid());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new McpAuthException("令牌所属账号不存在或已停用");
        }
        AccessContext ctx = buildForUser(user);
        patService.touchUsed(pat.getId());
        return new Identity(ctx, Method.PAT, "pat:" + pat.getId(), pat.getId());
    }

    // ==================== Bearer JWT ====================

    private Identity fromJwt(String token) {
        Map<String, Object> claims = null;
        if (props.getJwksUrl() != null && !props.getJwksUrl().isBlank()) {
            claims = jwtVerifier.verify(token, props.getJwksUrl());
        }
        if (claims != null) {
            return fromExternalClaims(claims);
        }
        if (props.isAllowSelfJwt()) {
            try {
                TokenPrincipal principal = tokenProvider.parsePrincipal(token);
                if (principal != null) {
                    User user = userMapper.selectById(principal.uid());
                    if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                        throw new McpAuthException("令牌所属账号不存在或已停用");
                    }
                    return new Identity(contextBuilder.buildForRequest(principal), Method.JWT,
                            "jwt:" + principal.uid(), null);
                }
            } catch (McpAuthException e) {
                throw e;
            } catch (Exception e) {
                log.debug("本系统 JWT 解析失败，按无效凭据处理: {}", e.getMessage());
            }
        }
        throw new McpAuthException("Bearer 令牌无效：签名校验未通过，或未配置受信任的授权服务器");
    }

    /** 外部 IdP 签发的令牌：先做规范要求的声明校验，再映射到本系统账号。 */
    private Identity fromExternalClaims(Map<String, Object> claims) {
        // exp：RFC 7519 要求 —— 无 exp 的令牌永不过期，资源服务器必须拒绝
        Object exp = claims.get("exp");
        if (!(exp instanceof Number expNum)) {
            throw new McpAuthException("令牌缺少 exp 声明，拒绝（不接受永不过期的凭据）");
        }
        if (Instant.now().getEpochSecond() >= expNum.longValue()) {
            throw new McpAuthException("令牌已过期");
        }
        // iss：RFC 9207 —— 配置了才校验，未配置时不臆造期望值
        String issuer = props.getIssuer();
        if (issuer != null && !issuer.isBlank()) {
            Object iss = claims.get("iss");
            if (!(iss instanceof String s) || !issuer.equals(s)) {
                throw new McpAuthException("令牌签发者（iss）不受信任");
            }
        }
        // aud / resource：RFC 8707 —— 防止"给别的服务签的令牌"被拿来调本服务（混淆代理攻击）
        if (props.isRequireAudience()) {
            if (!audienceMatches(claims.get("aud"))) {
                throw new McpAuthException("令牌 audience 与本服务不匹配（需绑定 " + props.getResourceUrl() + "）");
            }
        }
        Long uid = extractUid(claims);
        if (uid == null) {
            throw new McpAuthException("令牌无法映射到本系统用户：请让 IdP 在 uid 或 sub 声明中给出 aibi 用户 id");
        }
        User user = userMapper.selectById(uid);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new McpAuthException("令牌所属账号不存在或已停用");
        }
        return new Identity(buildForUser(user), Method.JWT, "jwt:" + uid, null);
    }

    // ==================== 辅助 ====================

    /** 以用户行完整重建上下文（角色/数据范围全部按库里事实解析，非"MCP 专用权限"）。 */
    private AccessContext buildForUser(User user) {
        Tenant tenant = null;
        if (user.getTenantId() != null && user.getTenantId() > 0L) {
            tenant = tenantMapper.selectById(user.getTenantId());
        }
        return contextBuilder.buildForLogin(user, tenant, null);
    }

    private boolean audienceMatches(Object aud) {
        String resource = props.getResourceUrl();
        if (resource == null || resource.isBlank()) {
            return false;
        }
        if (aud instanceof String s) {
            return resource.equals(s);
        }
        if (aud instanceof Collection<?> list) {
            for (Object o : list) {
                if (resource.equals(o)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Long extractUid(Map<String, Object> claims) {
        Object uid = claims.get("uid");
        Long parsed = toLong(uid);
        if (parsed != null) {
            return parsed;
        }
        return toLong(claims.get("sub"));
    }

    private Long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 提取凭据：{@code Bearer } 前缀<b>可选</b>（大小写不敏感）。
     * <p>实测豆包等客户端的自定义 Header 只填裸令牌值（{@code aibi_pat_...}），
     * 强制要求 Bearer 前缀会导致 401（2026-09-24「豆包连不上」根因之二）。
     * 凭据本身仍必填，只是不再挑剔前缀格式。
     */
    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String t = header.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        return t.isEmpty() ? null : t;
    }
}
