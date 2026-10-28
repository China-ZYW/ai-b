package com.aibi.module.auth.service;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.TokenDigest;
import com.aibi.module.auth.entity.LoginSession;
import com.aibi.module.auth.mapper.LoginSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 登录会话服务：会话登记、吊销与过期清理（实现《角色权限体系与组织协同设计》6.3 的精确吊销）。
 *
 * <p><b>为什么必须有这张表</b>：JWT 无状态，签发后在自然过期前（{@code aibi.jwt.expire-minutes=120}）
 * 始终有效。没有会话行，"移除成员"后对方仍能用旧令牌继续访问 2 小时。有了 {@code sid → revoked_at}，
 * {@code JwtAuthFilter} 每请求按唯一索引查一次即可实现 ≤1s 生效的强制下线。
 *
 * <p><b>硬约束</b>：{@code logout} 必须走 {@link #revokeCurrent()} 真吊销，不能只让前端清本地存储。
 *
 * <p>本类落地在设计文档 12 的 {@code SessionService} 职责上，放在 {@code module/auth} 而非
 * {@code module/rbac}，因为它管理的是 {@code module/auth} 的 {@code login_session} 表；
 * P1 的 {@code PermVersionService}（版本号比对）会复用这里的吊销能力。
 *
 * <p><b>失败策略</b>：登记失败只告警不抛出。此时令牌本身仍然有效，让登录整体失败会把一次
 * 数据库抖动放大成"全站无法登录"；代价是该令牌不受吊销保护，已由 {@code JwtAuthFilter}
 * 对"查不到会话行"的放行分支同步告警，运维可从日志发现。
 */
@Service
public class LoginSessionService {

    private static final Logger log = LoggerFactory.getLogger(LoginSessionService.class);

    /** {@code login_session.ip} 列宽。 */
    private static final int IP_MAX = 64;
    /** {@code login_session.user_agent} 列宽：真实 UA 可达数百字符，超长必须截断而非报错。 */
    private static final int UA_MAX = 512;
    /** 单次清理的最大批次数，防止极端堆积时清理任务长时间占用连接。 */
    private static final int CLEANUP_MAX_BATCHES = 50;
    /** 每批删除行数：分批以免大事务长时间持锁。 */
    private static final int CLEANUP_BATCH_SIZE = 500;

    private final LoginSessionMapper sessionMapper;

    public LoginSessionService(LoginSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    // ==================== 登记 ====================

    /**
     * 登录/注册成功后登记会话行。
     *
     * @param context     签发令牌时用的上下文，其 {@code sid} 必须与令牌 claim 里的同一个值
     * @param accessToken 明文令牌，仅用于算 SHA-256 存 {@code token_hash}，绝不落明文
     * @param expiresAt   令牌到期时间，须与 claim {@code exp} 一致，否则清理任务会误删活会话
     */
    public void register(AccessContext context, String accessToken, Date expiresAt) {
        if (context == null || context.sid() == null || context.sid().isBlank()) {
            // 无 sid 说明走的是旧签发路径（generate），不受吊销保护；不登记空 sid 行以免撞 uk_sid
            log.warn("上下文缺少 sid，跳过会话登记（该令牌不受吊销保护）: uid={}",
                    context == null ? null : context.uid());
            return;
        }
        try {
            LoginSession session = new LoginSession();
            session.setTenantId(context.tenantId() == null ? 0L : context.tenantId());
            session.setUid(context.uid());
            session.setSid(context.sid());
            session.setTokenHash(TokenDigest.sha256Hex(accessToken));
            session.setScope(context.tokenScope().name());
            session.setIp(truncate(clientIp(), IP_MAX));
            session.setUserAgent(truncate(userAgent(), UA_MAX));
            session.setExpireAt(toLocalDateTime(expiresAt));
            sessionMapper.insert(session);
        } catch (Exception e) {
            log.warn("会话登记失败（登录仍放行，但该令牌不受吊销保护）: uid={}, sid={}, err={}",
                    context.uid(), context.sid(), e.getMessage());
        }
    }

    // ==================== 吊销 ====================

    /**
     * 自助登出：吊销当前线程令牌对应的会话。
     *
     * @return {@code true} 表示确有会话被吊销；{@code false} 表示无会话可吊（旧格式令牌或重复登出），
     *         两种情况都<b>不算失败</b>——登出的用户侧结果一致（令牌不再被前端使用）
     */
    public boolean revokeCurrent() {
        AccessContext context = AccessContextHolder.get();
        if (context == null || context.sid() == null || context.sid().isBlank()) {
            return false;
        }
        // revokedBy 记本人：自助登出与管理员强制下线在审计上要能区分
        int affected = sessionMapper.revokeBySid(context.sid(), context.uid());
        if (affected == 0) {
            log.debug("会话 {} 已吊销或不存在，忽略重复登出", context.sid());
        }
        return affected > 0;
    }

    /**
     * 吊销某成员在本组织的全部会话（移除成员、停用账号、重置口令后必须调用）。
     *
     * @param revokedBy 操作人 uid；系统自动触发时传 {@code null}
     */
    public int revokeAllForUser(Long tenantId, Long uid, Long revokedBy) {
        if (tenantId == null || uid == null) {
            return 0;
        }
        int affected = sessionMapper.revokeAllByUid(tenantId, uid, revokedBy);
        log.info("吊销成员全部会话: tenant={}, uid={}, 会话数={}, by={}", tenantId, uid, affected, revokedBy);
        return affected;
    }

    /** 吊销某组织全部会话（组织被停用/解散；平台运营操作）。 */
    public int revokeAllForTenant(Long tenantId, Long revokedBy) {
        if (tenantId == null) {
            return 0;
        }
        int affected = sessionMapper.revokeAllByTenant(tenantId, revokedBy);
        log.info("吊销组织全部会话: tenant={}, 会话数={}, by={}", tenantId, affected, revokedBy);
        return affected;
    }

    // ==================== 查询与清理 ====================

    /** 某成员的在册会话（多端登录管理页）。 */
    public List<LoginSession> listActive(Long tenantId, Long uid) {
        return sessionMapper.selectActiveByUid(tenantId, uid);
    }

    /** 删除已自然过期的会话行，返回删除总数。 */
    public int cleanupExpired() {
        LocalDateTime before = LocalDateTime.now();
        int total = 0;
        for (int i = 0; i < CLEANUP_MAX_BATCHES; i++) {
            int affected = sessionMapper.deleteExpired(before, CLEANUP_BATCH_SIZE);
            total += affected;
            if (affected < CLEANUP_BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("清理过期登录会话 {} 行", total);
        }
        return total;
    }

    /**
     * 每日 03:30 清理过期会话。
     *
     * <p>不设清理会让本表随登录次数无限增长（每次登录一行），且 {@code idx_expire} 上的范围扫描
     * 会越来越慢。选凌晨低峰执行；异常不外抛，避免调度线程因一次失败而停止后续执行。
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledCleanup() {
        try {
            cleanupExpired();
        } catch (Exception e) {
            log.warn("清理过期登录会话失败: {}", e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 客户端 IP：优先取反向代理写入的 {@code X-Forwarded-For} 首段（最靠近客户端的一跳）。
     *
     * <p>容器化部署下 {@code getRemoteAddr()} 拿到的是网关/代理地址，登录审计里全是同一个内网 IP
     * 就失去了追溯价值。该头可被客户端伪造，故只作<b>审计展示</b>用，绝不作为任何权限判据。
     */
    private String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private String userAgent() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    /**
     * 当前请求；非请求线程（定时清理、异步任务、管理员后台批处理）返回 {@code null}。
     *
     * <p>刻意不把 {@code HttpServletRequest} 加进 {@code UserService.login} 的签名：那会让业务层
     * 依赖 Servlet API，且所有调用点（含未来的第三方登录、SSO 回调）都要跟着改。
     */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    /** {@link Date} → {@link LocalDateTime}：按系统时区换算，与 JDBC 写入 DATETIME 的口径一致。 */
    private LocalDateTime toLocalDateTime(Date date) {
        return date == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
