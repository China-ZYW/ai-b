package com.aibi.module.audit.service;

import com.aibi.common.response.PageResult;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.dto.AuditLogVO;
import com.aibi.module.audit.dto.AuditQuery;
import com.aibi.module.audit.entity.AuthAuditLog;
import com.aibi.module.audit.mapper.AuthAuditLogMapper;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审计服务（《角色权限体系与组织协同设计》10.2）：必审事件写入 + 平台后台跨租户检索。
 *
 * <p><b>写入</b>：{@link #record} 在<b>请求线程</b>上捕获操作人上下文（{@code AccessContext} + ip/ua），
 * 组装为不可变实体后，若处于事务中则注册 {@code afterCommit} 回调——确保审计只反映<b>已提交</b>的变更
 * （业务回滚则不产生审计）；实际 INSERT 交由独立的 {@code auditExecutor} 线程池异步执行，
 * 全程 {@code try/catch} 只告警、绝不抛出（审计不可反噬业务）。
 *
 * <p><b>为何不用 {@code @Async}</b>：{@code @Async} 方法在另一线程执行，而 {@code AccessContextHolder} 是
 * {@code ThreadLocal}、不会自动传播；且自调用不经代理会失效。故改为「调用线程捕获上下文 → 提交到执行器」，
 * 语义明确、无上下文丢失风险。
 *
 * <p><b>检索</b>：{@link #search}/{@link #exportList} 走 {@code @InterceptorIgnore} 的 Mapper 跨租户查询，
 * 再批量联查用户名/租户名（一次 {@code selectBatchIds}，避免 N+1）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 单页最大条数（防 {@code limit} 传入过大值拖垮查询）。 */
    public static final int MAX_PAGE_SIZE = 200;
    /** 导出一次性拉取上限（超出请收窄过滤条件）。 */
    public static final int EXPORT_MAX = 5000;
    /** 默认单页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    // 各列宽（超长截断而非报错，与 LoginSessionService 口径一致）
    private static final int DETAIL_MAX = 1000;
    private static final int IP_MAX = 64;
    private static final int UA_MAX = 512;
    private static final int ACTION_MAX = 48;
    private static final int RESOURCE_TYPE_MAX = 32;
    private static final int RESOURCE_ID_MAX = 64;

    private final AuthAuditLogMapper auditMapper;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor auditExecutor;

    public AuditService(AuthAuditLogMapper auditMapper,
                        UserMapper userMapper,
                        TenantMapper tenantMapper,
                        ObjectMapper objectMapper,
                        @Qualifier("auditExecutor") ThreadPoolTaskExecutor auditExecutor) {
        this.auditMapper = auditMapper;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.objectMapper = objectMapper;
        this.auditExecutor = auditExecutor;
    }

    // ==================== 写入 ====================

    /**
     * 记录一条审计事件。操作人身份（uid/identity/actor_type/ip/ua）从当前上下文自动捕获，
     * 故<b>必须在请求线程上调用</b>（异步线程 {@code AccessContextHolder} 为空）。
     *
     * @param action        动作码，见 {@link AuditAction}
     * @param result        结果码（OK/DENIED/FAILED）；{@code null} 记为 OK
     * @param eventTenantId 事件归属租户（受影响租户）；{@code null} 回退当前上下文租户，仍无则 0（平台域）
     * @param targetUid     被操作人 uid，可空
     * @param resourceType  资源类型，可空
     * @param resourceId    资源标识，可空
     * @param detail        变更详情（序列化为脱敏 JSON），可空；<b>禁含口令/凭证</b>
     */
    public void record(String action, String result, Long eventTenantId, Long targetUid,
                       String resourceType, String resourceId, Map<String, ?> detail) {
        try {
            AccessContext ctx = AccessContextHolder.get();
            AuthAuditLog entity = build(action, result, resolveTenantId(eventTenantId, ctx),
                    ctx == null ? null : ctx.uid(), ctx == null ? null : ctx.identityId(),
                    resolveActorType(ctx), targetUid, resourceType, resourceId, detail);
            dispatch(entity);
        } catch (Exception e) {
            // 审计构建/提交失败绝不阻断业务
            log.warn("审计记录失败(action={}): {}", action, e.getMessage());
        }
    }

    /**
     * 记录<b>认证类</b>事件（{@code LOGIN}/{@code LOGOUT}/{@code LOGIN_FAILED}）。
     *
     * <p>登录在 {@code SecurityConfig} 中放行、令牌尚未签发，登录失败时更无有效身份，
     * 故 {@code AccessContextHolder} 不含操作人——改由调用方<b>显式传入</b> uid/租户/identity；
     * ip/ua 仍从当前请求线程捕获。{@code actor_type} 按平台域（tenant==0）→OPERATOR、其余→USER、
     * 无 uid（如用户名不存在的失败登录）→SYSTEM 派生，与 {@link #resolveActorType} 口径一致。
     *
     * @param actorUid      认证主体 uid；用户不存在的失败登录传 {@code null}
     * @param actorTenantId 主体所属租户（平台账号为 0）；可空
     * @param identityId    自然人身份 id，可空
     */
    public void recordAuth(String action, String result, Long actorUid, Long actorTenantId,
                           Long identityId, String resourceType, String resourceId, Map<String, ?> detail) {
        try {
            String actorType;
            if (actorUid == null) {
                actorType = "SYSTEM";
            } else if (actorTenantId != null && actorTenantId == 0L) {
                actorType = "OPERATOR";
            } else {
                actorType = "USER";
            }
            AuthAuditLog entity = build(action, result, actorTenantId, actorUid, identityId,
                    actorType, null, resourceType, resourceId, detail);
            dispatch(entity);
        } catch (Exception e) {
            log.warn("认证审计记录失败(action={}): {}", action, e.getMessage());
        }
    }

    /** 组装审计实体（各列超长截断 + ip/ua 从当前请求捕获），供 {@link #record}/{@link #recordAuth} 复用。 */
    private AuthAuditLog build(String action, String result, Long tenantId, Long uid, Long identityId,
                               String actorType, Long targetUid, String resourceType, String resourceId,
                               Map<String, ?> detail) {
        AuthAuditLog entity = new AuthAuditLog();
        entity.setTenantId(tenantId == null ? 0L : tenantId);
        entity.setUid(uid);
        entity.setIdentityId(identityId);
        entity.setActorType(actorType);
        entity.setAction(truncate(action, ACTION_MAX));
        entity.setResourceType(truncate(resourceType, RESOURCE_TYPE_MAX));
        entity.setResourceId(truncate(resourceId, RESOURCE_ID_MAX));
        entity.setTargetUid(targetUid);
        entity.setResult(result == null ? AuditAction.RESULT_OK : result);
        entity.setDetail(serializeDetail(detail));
        entity.setIp(truncate(clientIp(), IP_MAX));
        entity.setUserAgent(truncate(userAgent(), UA_MAX));
        return entity;
    }

    private Long resolveTenantId(Long eventTenantId, AccessContext ctx) {
        if (eventTenantId != null) {
            return eventTenantId;
        }
        if (ctx != null && ctx.tenantId() != null) {
            return ctx.tenantId();
        }
        return 0L;
    }

    /** USER/OPERATOR/SUPPORT/SYSTEM：协助影子令牌→SUPPORT，平台域→OPERATOR，无上下文→SYSTEM，其余→USER。 */
    private String resolveActorType(AccessContext ctx) {
        if (ctx == null || ctx.uid() == null) {
            return "SYSTEM";
        }
        if (ctx.isAssist()) {
            return "SUPPORT";
        }
        return ctx.isPlatform() ? "OPERATOR" : "USER";
    }

    private String serializeDetail(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(detail), DETAIL_MAX);
        } catch (Exception e) {
            log.warn("审计 detail 序列化失败，降级为 toString: {}", e.getMessage());
            return truncate(String.valueOf(detail), DETAIL_MAX);
        }
    }

    /** 事务中→提交后写；否则立即写。确保审计只反映已提交变更。 */
    private void dispatch(AuthAuditLog entity) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(entity);
                }
            });
        } else {
            submit(entity);
        }
    }

    private void submit(AuthAuditLog entity) {
        try {
            auditExecutor.execute(() -> {
                try {
                    auditMapper.insertLog(entity);
                } catch (Exception e) {
                    log.warn("审计写入失败(action={}, tenant={}, uid={}): {}",
                            entity.getAction(), entity.getTenantId(), entity.getUid(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("审计任务提交失败(action={}): {}", entity.getAction(), e.getMessage());
        }
    }

    // ==================== 检索 ====================

    /** 跨租户分页检索（§9.2：按 action/租户/操作人/时间/结果过滤）。 */
    public PageResult<AuditLogVO> search(AuditQuery q) {
        int limit = clampLimit(q.limit());
        int offset = Math.max(0, q.offset());
        List<AuthAuditLog> rows = auditMapper.search(q.tenantId(), q.action(), q.uid(), q.result(),
                q.from(), q.to(), limit, offset);
        long total = auditMapper.count(q.tenantId(), q.action(), q.uid(), q.result(), q.from(), q.to());
        return PageResult.of(toVOs(rows), total, limit, offset);
    }

    /** 导出：按同条件一次性取至多 {@link #EXPORT_MAX} 条（不分页）。 */
    public List<AuditLogVO> exportList(AuditQuery q) {
        List<AuthAuditLog> rows = auditMapper.search(q.tenantId(), q.action(), q.uid(), q.result(),
                q.from(), q.to(), EXPORT_MAX, 0);
        return toVOs(rows);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    /** 批量联查用户名/租户名，避免逐行 N+1（{@code app_user}/{@code tenant} 均在 IGNORE 名单，无租户注入）。 */
    private List<AuditLogVO> toVOs(List<AuthAuditLog> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<Long> uids = new HashSet<>();
        Set<Long> tids = new HashSet<>();
        for (AuthAuditLog r : rows) {
            if (r.getUid() != null) {
                uids.add(r.getUid());
            }
            if (r.getTargetUid() != null) {
                uids.add(r.getTargetUid());
            }
            if (r.getTenantId() != null && r.getTenantId() != 0L) {
                tids.add(r.getTenantId());
            }
        }
        Map<Long, User> users = new HashMap<>();
        if (!uids.isEmpty()) {
            for (User u : userMapper.selectBatchIds(uids)) {
                users.put(u.getId(), u);
            }
        }
        Map<Long, Tenant> tenants = new HashMap<>();
        if (!tids.isEmpty()) {
            for (Tenant t : tenantMapper.selectBatchIds(tids)) {
                tenants.put(t.getId(), t);
            }
        }
        List<AuditLogVO> list = new ArrayList<>(rows.size());
        for (AuthAuditLog r : rows) {
            User op = r.getUid() == null ? null : users.get(r.getUid());
            User tgt = r.getTargetUid() == null ? null : users.get(r.getTargetUid());
            Tenant tn = r.getTenantId() == null ? null : tenants.get(r.getTenantId());
            list.add(new AuditLogVO(r.getId(), r.getTenantId(), tn == null ? null : tn.getName(),
                    r.getUid(), op == null ? null : op.getUsername(), r.getIdentityId(), r.getActorType(),
                    r.getAction(), r.getResourceType(), r.getResourceId(), r.getTargetUid(),
                    tgt == null ? null : tgt.getUsername(), r.getResult(), r.getDetail(),
                    r.getIp(), r.getUserAgent(), r.getTraceId(), r.getCreatedAt()));
        }
        return list;
    }

    // ==================== 请求信息（同 LoginSessionService 口径） ====================

    /** 客户端 IP：优先 {@code X-Forwarded-For} 首段；仅供审计展示，绝不作权限判据。 */
    private String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return normalizeLoopback(comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim());
        }
        return normalizeLoopback(request.getRemoteAddr());
    }

    /**
     * 回环地址归一：IPv6 回环 {@code 0:0:0:0:0:0:0:1}/{@code ::1} 与 {@code 127.0.0.1}
     * 统一记为 {@code 127.0.0.1}，避免审计展示出现难看的裸回环串（本地访问时尤其明显）。
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

    private String userAgent() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    /** 当前请求；非请求线程（定时/异步）返回 {@code null}。 */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
