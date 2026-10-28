package com.aibi.module.audit.interceptor;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.audit.entity.OperationLog;
import com.aibi.module.audit.service.OperationLogService;
import com.aibi.module.config.service.AibiConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全局操作日志拦截器（《角色权限体系》10.2 审计落地之二）：横切捕获每一次 HTTP 操作。
 *
 * <p>在 {@code afterCompletion}（控制器已执行、状态码/异常已知）组装 {@link OperationLog} 并异步落库，
 * 与 {@code auth_audit_log} 的事件埋点形成互补：覆盖全量请求（含被权限拦截的 403 / 业务 500），
 * 且无需任何业务代码改动。
 *
 * <p><b>拦截范围</b>：注册于 {@link OperationLogWebConfig} 的 {@code /api/**}；
 * 本类再二次过滤掉审计页自身（避免「看日志」被反复记录）、actuator、error 与 SSE 流式端点（噪音大）。
 *
 * <p><b>开关</b>：{@code AibiConfig.operationLogEnabled()}（{@code AUDIT/operation-log}，默认开）。
 * 读取发生在请求线程，{@code AccessContextHolder}/{@code TenantContext} 此时仍有效。
 *
 * <p><b>零反噬</b>：整个 {@code afterCompletion} 包在 try/catch 中，审计异常绝不抛向业务线程。
 */
public class OperationLogInterceptor implements HandlerInterceptor {

    private static final String ATTR_START = "___op_log_start";
    private static final String ATTR_RID = "___op_log_rid";

    /** 请求体截断上限（脱敏后仍可能较长，超长截断避免撑爆列）。 */
    private static final int BODY_MAX = 4000;
    /** 需要读取 JSON 体的方法（GET 等无体，直接跳过）。 */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 口令/凭证类字段值脱敏（仅处理 JSON 字符串值，最佳努力）。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(\"(?:password|pwd|passwd|secret|secret_key|secretkey|access_key|accesskey|"
                    + "token|authorization|api_key|apikey|cookie)\"\\s*:\\s*\")([^\"]*)(\")");

    private final OperationLogService service;
    private final AibiConfig aibiConfig;

    public OperationLogInterceptor(OperationLogService service, AibiConfig aibiConfig) {
        this.service = service;
        this.aibiConfig = aibiConfig;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START, System.currentTimeMillis());
        request.setAttribute(ATTR_RID, UUID.randomUUID().toString());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            if (!aibiConfig.operationLogEnabled()) {
                return;
            }
            String uri = request.getRequestURI();
            if (shouldSkip(uri)) {
                return;
            }
            // 匿名请求（未登录即被 SecurityConfig 拒）价值低且噪音大，不记
            AccessContext ctx = AccessContextHolder.get();
            if (ctx == null) {
                return;
            }

            Object s = request.getAttribute(ATTR_START);
            long start = s instanceof Long v ? v : System.currentTimeMillis();
            long duration = System.currentTimeMillis() - start;

            OperationLog log = new OperationLog();
            log.setTenantId(ctx.tenantId() == null ? 0L : ctx.tenantId());
            log.setUserId(ctx.uid());
            log.setUsername(ctx.username());
            log.setOrgRoles(String.join(",", ctx.orgRoles()));
            log.setPlatformRoles(String.join(",", ctx.platformRoles()));
            log.setRequestId((String) request.getAttribute(ATTR_RID));
            log.setModule(deriveModule(uri));
            log.setAction(request.getMethod() + " " + uri);
            log.setMethod(request.getMethod());
            log.setUri(uri);
            log.setQueryString(request.getQueryString());
            log.setRequestBody(readBody(request));
            log.setIp(clientIp(request));
            log.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
            log.setHttpStatus(response.getStatus());
            log.setErrorMsg(ex == null ? null : truncate(ex.getMessage(), 512));
            log.setDurationMs(duration);
            service.saveAsync(log);
        } catch (Exception e) {
            // 审计异常绝不反噬业务
        }
    }

    /** 审计页自身 / actuator / error / SSE 流式：避免自递归与高噪音。 */
    private boolean shouldSkip(String uri) {
        return uri.contains("/audit/")
                || uri.contains("/actuator")
                || uri.startsWith("/error")
                || uri.contains("/api/chat/stream");
    }

    /** 由路径推导业务模块：{@code /api/org/metrics} → org:metrics，{@code /api/admin/audit} → admin:audit。 */
    private String deriveModule(String uri) {
        String p = uri.startsWith("/api/") ? uri.substring(5) : uri;
        String[] seg = p.split("/");
        if (seg.length == 0 || seg[0].isEmpty()) {
            return "other";
        }
        String first = seg[0];
        if (("org".equals(first) || "admin".equals(first)) && seg.length > 1) {
            return first + ":" + seg[1];
        }
        return first;
    }

    /** 读取已缓存的请求体（需 {@link RequestCachingFilter} 提前包装），脱敏 + 截断。 */
    private String readBody(HttpServletRequest request) {
        if (!BODY_METHODS.contains(request.getMethod())) {
            return null;
        }
        if (!(request instanceof ContentCachingRequestWrapper w)) {
            return null;
        }
        byte[] bytes = w.getContentAsByteArray();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String raw;
        try {
            raw = new String(bytes, w.getCharacterEncoding());
        } catch (Exception e) {
            raw = new String(bytes);
        }
        raw = SENSITIVE.matcher(raw).replaceAll("$1***$3");
        return truncate(raw, BODY_MAX);
    }

    /** 客户端 IP：优先 X-Forwarded-For 首段（仅供审计展示，不作权限判据）。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return normalizeLoopback(truncate(comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim(), 64));
        }
        return normalizeLoopback(truncate(request.getRemoteAddr(), 64));
    }

    /**
     * 回环地址归一：IPv6 回环 {@code 0:0:0:0:0:0:0:1}/{@code ::1} 与 {@code 127.0.0.1}
     * 统一记为 {@code 127.0.0.1}，避免审计展示出现难看的裸回环串。
     */
    private static String normalizeLoopback(String ip) {
        if (ip == null) {
            return null;
        }
        String t = ip.trim();
        if ("0:0:0:0:0:0:0:1".equals(t) || "::1".equals(t) || "127.0.0.1".equals(t)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
