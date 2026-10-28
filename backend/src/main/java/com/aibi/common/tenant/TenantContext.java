package com.aibi.common.tenant;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;

/**
 * 当前租户上下文门面（多租户隔离的读取入口）。
 *
 * <p><b>已升级为委派</b>：真实存放点是 {@link AccessContextHolder} 中的 {@link AccessContext}
 * （租户 + 用户 + 角色 + 团队 + 会话的合并视图，见《角色权限体系与组织协同设计》2.4）。
 * 本类保留全部既有方法签名，故 {@code TenantContext.tenantId()} 等遍布各 Service 的调用点<b>零改动</b>。
 *
 * <p>MyBatis-Plus 的 {@code TenantLineHandler} 与 {@code TenantDataSourceManager} 仍从此处读取租户身份；
 * 由 {@code JwtAuthFilter} 在请求进入时写入、请求结束时清理，杜绝线程复用导致的串租户。
 * 异步/线程池场景需显式传递（见 {@link #wrap(Runnable)}）。
 *
 * <p>注意 {@link #set} 是<b>增量合并</b>而非整体替换：只写租户维度，保留已建立的用户/角色/团队信息
 * （典型场景是大屏公开访问按 token 反查出租户后临时设置，用毕 {@link #clear()}）。
 */
public final class TenantContext {

    /** 当前租户信息（门面投影，字段语义与升级前一致）。 */
    public record TenantInfo(Long tenantId, PlanType planType, IsolationMode isolationMode) {
    }

    private TenantContext() {
    }

    public static void set(Long tenantId, PlanType planType) {
        AccessContextHolder.mergeTenant(tenantId, planType, null);
    }

    public static void set(TenantInfo info) {
        if (info == null) {
            AccessContextHolder.clear();
            return;
        }
        AccessContextHolder.mergeTenant(info.tenantId(), info.planType(), info.isolationMode());
    }

    /** 当前租户信息；无租户上下文（匿名，或仅建立了用户维度）时返回 {@code null}。 */
    public static TenantInfo get() {
        AccessContext ctx = AccessContextHolder.get();
        if (ctx == null || ctx.tenantId() == null) {
            return null;
        }
        return new TenantInfo(ctx.tenantId(), ctx.plan(), ctx.isolationMode());
    }

    public static Long tenantId() {
        return AccessContextHolder.getOrDefault().tenantId();
    }

    public static PlanType planType() {
        return AccessContextHolder.getOrDefault().plan();
    }

    public static IsolationMode isolationMode() {
        return AccessContextHolder.getOrDefault().isolationMode();
    }

    /** 强隔离路径必须存在租户上下文，否则直接抛出，避免"无租户"越权访问。 */
    public static Long requireTenantId() {
        return AccessContextHolder.getOrDefault().requireTenantId();
    }

    /**
     * 清理当前线程的访问上下文（含用户/角色/团队维度）。
     *
     * <p>与 {@code UserContext.clear()} 指向同一存放点，重复调用无害；请求线程与异步任务结束时必须调用。
     */
    public static void clear() {
        AccessContextHolder.clear();
    }

    /** 包装异步任务，将当前<b>完整</b>访问上下文（租户 + 用户 + 团队 + 角色）传播到子线程。 */
    public static Runnable wrap(Runnable task) {
        return AccessContextHolder.wrap(task);
    }
}
