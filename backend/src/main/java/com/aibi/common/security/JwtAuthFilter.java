package com.aibi.common.security;

import com.aibi.common.exception.ErrorCode;
import com.aibi.module.auth.entity.LoginSession;
import com.aibi.module.auth.mapper.LoginSessionMapper;
import com.aibi.module.rbac.service.PermVersionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JWT 认证过滤器：验签 → 会话吊销校验 → 重建 {@link AccessContext} → 写入 ThreadLocal 与 SecurityContext。
 *
 * <p>令牌缺失或无效时<b>不设置认证也不写响应</b>，由后续授权规则决定放行（公开域）或拦截（401）——
 * 分享页、大屏等匿名接口本就允许不带令牌访问，在此处直接拒绝会打断它们。
 *
 * <p>{@link UserContext} 与 {@code TenantContext} 已改为门面并委派到 {@link AccessContextHolder}，
 * 故此处只需写入一次完整上下文，两个旧门面的调用点自动生效（2.4）。清理仍放在 {@code finally}，
 * 杜绝线程复用导致的串租户/串用户。
 *
 * <p><b>三层校验的分工</b>（见《角色权限体系与组织协同设计》6.1）：本过滤器只做 L0 令牌有效性
 * 与 L1 会话吊销；L2 功能权限点由 {@code PermissionAspect} 判定，L3 数据范围由拦截器收敛。
 * URL 粗粒度门禁在 {@link SecurityConfig}，依赖本类展开的多角色 authority。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    /** 平台角色 authority 前缀：Spring Security 的 {@code hasRole("X")} 匹配 {@code ROLE_X}。 */
    private static final String PLATFORM_PREFIX = "ROLE_";
    /** 组织角色 authority 前缀，与平台角色分域，避免 {@code hasRole} 误命中另一域的同名码。 */
    private static final String ORG_PREFIX = "ROLE_ORG_";
    /** 团队角色 authority 前缀。 */
    private static final String TEAM_PREFIX = "ROLE_TEAM_";

    private final JwtTokenProvider tokenProvider;
    private final AccessContextBuilder contextBuilder;
    private final LoginSessionMapper sessionMapper;
    private final PermVersionService permVersionService;

    /** 应急回退开关（11.3）：{@code false} 时只按升级前的二元角色（{@code SUPER_ADMIN}/{@code USER}）判定。 */
    @Value("${aibi.rbac.enabled:true}")
    private boolean rbacEnabled;

    /**
     * 会话吊销校验开关。默认开启；关闭后 {@code login_session.revoked_at} 不再即时生效，
     * 令牌可用至自然过期。保留此开关是因为校验会给每个请求增加一次按唯一索引的查询，
     * 且会话表出问题时（磁盘满、被误删）不至于连带锁死全站。
     */
    @Value("${aibi.rbac.session-check:true}")
    private boolean sessionCheck;

    /**
     * 版本号（{@code pv}）比对开关（6.3）。默认开启；令牌 {@code pv} 小于当前
     * {@code tenant.perm_version + app_user.token_version} 即判为过期，回 401 + {@code 40311}。
     * 版本号有 30s 缓存（{@link PermVersionService}），故这是"普通授权变更 ≤30s 收敛"的路径；
     * "移除成员/停用账号"的秒级下线由 {@link #sessionCheck} 的 {@code sid} 精确吊销保证。
     */
    @Value("${aibi.rbac.pv-check:true}")
    private boolean pvCheck;

    public JwtAuthFilter(JwtTokenProvider tokenProvider,
                         AccessContextBuilder contextBuilder,
                         LoginSessionMapper sessionMapper,
                         PermVersionService permVersionService) {
        this.tokenProvider = tokenProvider;
        this.contextBuilder = contextBuilder;
        this.sessionMapper = sessionMapper;
        this.permVersionService = permVersionService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                TokenPrincipal principal = parseOrNull(token);
                if (principal != null) {
                    if (isSessionRevoked(principal) || isVersionStale(principal)) {
                        writeRevoked(response);
                        return;
                    }
                    applyAuthentication(principal);
                }
            }
            chain.doFilter(request, response);
        } finally {
            AccessContextHolder.clear();
        }
    }

    /** 验签并投影为强类型载荷；任何异常（过期、签名不符、格式损坏）一律按未认证处理。 */
    private TokenPrincipal parseOrNull(String token) {
        try {
            return tokenProvider.parsePrincipal(token);
        } catch (Exception e) {
            // 只记 debug：过期令牌在正常使用中大量出现，warn 会淹没真实故障
            log.debug("令牌解析失败，按未认证处理: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 会话吊销校验：按 {@code sid} 命中唯一索引查一次 {@code login_session.revoked_at}。
     *
     * <p>这是"移除成员/停用账号/重置口令 ≤1s 生效"的唯一落点（6.3）：{@code pv} 版本号比对需要
     * 额外查租户与账号行，且有 30s 缓存窗口；会话吊销按 {@code sid} 精确寻址，无缓存、即时生效。
     *
     * <p>无 {@code sid} 的存量旧令牌跳过校验（{@link TokenPrincipal#isLegacyToken()}），
     * 否则升级瞬间所有在线用户都会被踢下线。
     */
    private boolean isSessionRevoked(TokenPrincipal principal) {
        if (!sessionCheck || principal.isLegacyToken()) {
            return false;
        }
        try {
            LoginSession session = sessionMapper.selectBySid(principal.sid());
            if (session == null) {
                // 令牌已验签通过却查不到会话行：多为过期清理任务已删行，或 sid 与签名密钥不同源。
                // 此处放行而非拒绝——清理任务与请求的时序竞态一旦命中就会锁死正常用户，
                // 而伪造 sid 需要先拿到签名密钥，届时整个认证体系已失效，不是本分支能防的。
                log.warn("会话 {} 无登记记录，按未吊销放行（uid={}）", principal.sid(), principal.uid());
                return false;
            }
            if (session.isRevoked()) {
                return true;
            }
            if (!Objects.equals(session.getUid(), principal.uid())) {
                // 签名有效但载荷与会话行不匹配：不可能由正常流程产生，按已吊销拒绝并留痕供排查
                log.warn("会话 {} 归属身份与令牌不符（会话 uid={}, 令牌 uid={}），拒绝",
                        principal.sid(), session.getUid(), principal.uid());
                return true;
            }
            return false;
        } catch (Exception e) {
            // 故障域隔离：会话表不可用（迁移未跑、连接池耗尽）时放行，只告警。
            // 反过来 fail-closed 会让一次数据库抖动演变成全站 401，而令牌本身仍然是有效的。
            log.warn("会话吊销校验失败，按未吊销放行: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 版本号（{@code pv}）过期校验：令牌 {@code pv} 小于当前合并版本号即判为过期（6.3）。
     *
     * <p>{@code pv = tenant.perm_version + app_user.token_version}，签发时写入令牌；组织授权变更
     * （{@code perm_version++}）或身份敏感变更（{@code token_version++}）后，旧令牌的 {@code pv} 即偏小。
     * 版本号经 {@link PermVersionService} 缓存 30s，故这是"普通授权变更 ≤30s 收敛"的路径；
     * 秒级精确下线由 {@link #isSessionRevoked} 的 {@code sid} 吊销承担。
     *
     * <p>三处 fail-open，与会话校验口径一致，避免把一次数据库抖动放大成全站 401：
     * 旧格式令牌（无 {@code pv} 语义）跳过、版本号查询失败（{@link PermVersionService#UNKNOWN}）跳过、
     * 开关关闭时跳过。
     */
    private boolean isVersionStale(TokenPrincipal principal) {
        if (!pvCheck || !rbacEnabled || principal.isLegacyFormat()) {
            return false;
        }
        long currentPv = permVersionService.current(principal.tenantId(), principal.uid());
        if (currentPv == PermVersionService.UNKNOWN) {
            return false;
        }
        boolean stale = principal.permVersion() < currentPv;
        if (stale) {
            log.info("令牌权限版本过期，拒绝: uid={}, tid={}, tokenPv={}, currentPv={}",
                    principal.uid(), principal.tenantId(), principal.permVersion(), currentPv);
        }
        return stale;
    }

    /** 重建访问上下文并写入 SecurityContext。 */
    private void applyAuthentication(TokenPrincipal principal) {
        AccessContext context = contextBuilder.buildForRequest(principal);
        // 一次写入即同时喂饱 TenantContext / UserContext 两个门面（二者委派到同一存放点）
        AccessContextHolder.set(context);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal.username(), null, authorities(context, principal)));
    }

    /**
     * 角色集合 → Spring Security authority。
     *
     * <p>必须把 {@code prole} 逐个展开成 authority：升级前只把单个 {@code role} 转成<b>一个</b>
     * {@code SimpleGrantedAuthority}，引入多平台角色后若不展开，
     * {@code SecurityConfig} 的 {@code hasAnyRole(SUPER_ADMIN, OPERATOR, AUDITOR, SUPPORT)} 永远不命中。
     *
     * <p>三个域用不同前缀：平台角色码与组织角色码虽当前无重名，但一旦有人新增同名码，
     * 无前缀的写法会让"组织 ADMIN"命中平台域的 {@code hasRole("ADMIN")} 规则——那是一条极难察觉的提权路径。
     *
     * <p>无任何角色时返回空集而<b>不补 {@code ROLE_USER}</b>：{@code .authenticated()} 只要求认证通过，
     * 与 authority 数量无关；补一个合成角色反而会让"真的没有角色"这一状态在授权层不可见。
     */
    private List<GrantedAuthority> authorities(AccessContext context, TokenPrincipal principal) {
        if (!rbacEnabled) {
            // 应急回退：authority 与升级前完全一致，配合 SecurityConfig 的规则退回二元判定
            String role = principal.legacyRole() == null || principal.legacyRole().isBlank()
                    ? UserContext.ROLE_USER
                    : principal.legacyRole();
            return List.of(new SimpleGrantedAuthority(PLATFORM_PREFIX + role));
        }
        List<GrantedAuthority> authorities = new ArrayList<>(
                context.platformRoles().size() + context.orgRoles().size() + context.teamRoles().size());
        for (String code : context.platformRoles()) {
            authorities.add(new SimpleGrantedAuthority(PLATFORM_PREFIX + code));
        }
        for (String code : context.orgRoles()) {
            authorities.add(new SimpleGrantedAuthority(ORG_PREFIX + code));
        }
        for (String code : context.teamRoles()) {
            authorities.add(new SimpleGrantedAuthority(TEAM_PREFIX + code));
        }
        return authorities;
    }

    /**
     * 会话已吊销：HTTP 401 + {@code 40311}（6.3）。
     *
     * <p>用 401 而非 403 是为了让前端拦截器走既有的"清本地令牌 → 跳登录"分支；
     * 业务码 40311 让前端能区分"登录过期"与"权限被变更"，给出不同文案。
     */
    private void writeRevoked(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + ErrorCode.SESSION_REVOKED.getCode()
                + ",\"message\":\"" + ErrorCode.SESSION_REVOKED.getMessage()
                + "\",\"data\":null}");
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
