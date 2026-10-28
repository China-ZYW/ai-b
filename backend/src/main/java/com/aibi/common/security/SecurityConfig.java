package com.aibi.common.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 配置：无状态 JWT 认证 + URL 粗粒度域门禁（L1，见设计文档 6.1）。
 *
 * <p>放行认证接口与健康检查，其余 {@code /api/**} 需携带有效令牌。
 *
 * <p><b>L1 只做域级门禁</b>：判定"能不能进这一类接口"；"能不能做这个动作"由 {@code PermissionAspect}
 * 按权限点判定（L2）。把具体组织角色写进 URL 规则会形成第二处需同步维护的权限定义，
 * 新增角色时极易漏改，故组织治理域一律只要求 {@code authenticated()}。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 对话问数嵌入（/embed/chat/{token}）必须由第三方站点以跨域 iframe 嵌套，
                // 故关闭 Spring Security 默认的 X-Frame-Options: DENY（静态 HTML 由 nginx 服务本就无此头，
                // 仅 /api 响应带）。其余默认安全头（HSTS / XSS / nosniff 等）保留。
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
                .authorizeHttpRequests(auth -> auth
                        // 公开域：认证页、分享与大屏公开访问、健康检查。
                        // 邀请预览/匿名接受等匿名接口属 P3，届时随 Controller 一同加进来——
                        // 故意不提前放行：不存在的端点挂 permitAll 只会留下一条无人记得的公开规则
                        // 对外 MCP：/mcp 自带鉴权（PAT 或外部 IdP 签发的 Bearer JWT，都不是本系统登录令牌），
                        // 交给 JwtAuthFilter 只会被判为未认证；且 401 必须带 WWW-Authenticate（RFC 6750）。
                        // /.well-known/** 是 OAuth 2.1 资源服务器元数据（RFC 9728）的固定路径，
                        // 客户端在"尚未持有任何凭据"时发现阶段就要访问，必须放行。
                        .requestMatchers("/api/auth/login", "/api/auth/register",
                                "/api/public/**", "/actuator/health", "/error",
                                "/mcp", "/.well-known/**").permitAll()
                        // 平台运营域：必须持任一平台角色（角色清单以 PlatformRole.ALL 为单一真相源）。
                        // 原写法 hasRole("SUPER_ADMIN") 必须改：否则运营/审计/客服账号进不了后台。
                        // aibi.rbac.enabled=false 时 JwtAuthFilter 只发 ROLE_SUPER_ADMIN/ROLE_USER，
                        // 本规则自然退化为升级前的二元判定，无需分支配置
                        .requestMatchers("/api/admin/**", "/api/ops/**")
                        .hasAnyRole(PlatformRole.ALL)
                        // 组织治理域：显式列出作为后续收紧的挂载点（当前语义等价于 anyRequest）
                        .requestMatchers("/api/org/**", "/api/member/**", "/api/team/**", "/api/invitation/**")
                        .authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 未认证时返回统一 JSON（HTTP 401），而非默认登录页/空白 403。 */
    private void writeUnauthorized(jakarta.servlet.http.HttpServletRequest request,
                                   HttpServletResponse response,
                                   org.springframework.security.core.AuthenticationException ex)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40001,\"message\":\"未认证或登录已过期\",\"data\":null}");
    }

    /** 已认证但权限不足（如非平台角色访问 {@code /api/admin/**}）返回统一 JSON（HTTP 403）。 */
    private void writeForbidden(jakarta.servlet.http.HttpServletRequest request,
                                HttpServletResponse response,
                                org.springframework.security.access.AccessDeniedException ex)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":40301,\"message\":\"无权限\",\"data\":null}");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
