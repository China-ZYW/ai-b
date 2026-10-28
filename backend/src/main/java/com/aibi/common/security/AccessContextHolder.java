package com.aibi.common.security;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.IsolationMode;
import com.aibi.common.tenant.PlanType;

import java.util.Set;

/**
 * {@link AccessContext} 的 ThreadLocal 持有者：全链路访问上下文的唯一存放点。
 *
 * <p>生命周期与原 {@code TenantContext}/{@code UserContext} 完全一致——由 {@code JwtAuthFilter}
 * 在请求进入时写入、{@code finally} 中清理，杜绝线程复用导致的串租户/串用户。
 * 异步场景（SSE 工作线程、语义层骨架构建线程）必须用 {@link #wrap(Runnable)} 或显式快照传播。
 *
 * <p>{@code TenantContext} 与 {@code UserContext} 已改为门面并委派到本类：二者原本是两个独立 ThreadLocal，
 * 合并后<b>任一门面的写入都必须保留另一维度</b>（如公开分享访问只设租户、不设用户），
 * 故提供 {@link #mergeTenant} / {@link #mergeUser} 做增量合并，而非整体替换。
 */
public final class AccessContextHolder {

    private static final ThreadLocal<AccessContext> HOLDER = new ThreadLocal<>();

    private AccessContextHolder() {
    }

    /** 整体写入（登录链路构建出完整上下文后调用）。 */
    public static void set(AccessContext context) {
        HOLDER.set(context);
    }

    /** 当前上下文；未认证/匿名时为 {@code null}。 */
    public static AccessContext get() {
        return HOLDER.get();
    }

    /**
     * 当前上下文，永不返回 {@code null}：无上下文时给出"匿名空上下文"
     * （{@code tenantId=null} → 租户拦截器注入 {@code NULL} → 命中不到任何行，安全默认）。
     */
    public static AccessContext getOrDefault() {
        AccessContext ctx = HOLDER.get();
        return ctx == null ? AccessContext.builder().build() : ctx;
    }

    /** 要求已认证，否则抛 40001；供权限切面与需要操作人的 Service 使用。 */
    public static AccessContext require() {
        AccessContext ctx = HOLDER.get();
        if (ctx == null || ctx.uid() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        return ctx;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 增量写入租户维度，保留已建立的用户/角色/团队/会话信息。
     *
     * <p>供 {@code TenantContext.set(...)} 委派。典型场景：大屏公开访问按 token 反查出租户后
     * {@code TenantContext.set(tenantId, null)}——此时请求线程可能已有其他维度，整体替换会误清。
     *
     * @param plan          为空时回退 FREE（与既有 {@code TenantContext.set} 行为一致）
     * @param isolationMode 为空时由套餐推导
     */
    public static void mergeTenant(Long tenantId, PlanType plan, IsolationMode isolationMode) {
        PlanType effectivePlan = plan == null ? PlanType.FREE : plan;
        IsolationMode effectiveMode = isolationMode == null
                ? effectivePlan.defaultIsolationMode() : isolationMode;
        HOLDER.set(getOrDefault().toBuilder()
                .tenantId(tenantId)
                .plan(effectivePlan)
                .isolationMode(effectiveMode)
                .build());
    }

    /**
     * 增量写入用户维度，保留已建立的租户/团队信息。
     *
     * <p>供 {@code UserContext.set(...)} 委派（SSE 工作线程重建上下文时与 {@link #mergeTenant} 先后各调一次）。
     *
     * @param platformRoles 平台角色码；租户身份必须传空集（见设计文档 3.6①）
     * @param orgRoles      组织角色码
     */
    public static void mergeUser(Long uid, String username, Long identityId,
                                 Set<String> platformRoles, Set<String> orgRoles) {
        HOLDER.set(getOrDefault().toBuilder()
                .uid(uid)
                .username(username)
                .identityId(identityId)
                .platformRoles(platformRoles)
                .orgRoles(orgRoles)
                .build());
    }

    /**
     * 包装异步任务，将当前<b>完整</b>访问上下文传播到子线程，执行完毕恢复子线程原有上下文。
     *
     * <p>相比原 {@code TenantContext.wrap}，此处传播的是整个 {@link AccessContext}（含用户、团队、角色），
     * 使异步任务里的权限判定与数据范围收敛与请求线程一致，而不是只剩租户 id。
     */
    public static Runnable wrap(Runnable task) {
        AccessContext snapshot = HOLDER.get();
        return () -> {
            AccessContext previous = HOLDER.get();
            try {
                if (snapshot == null) {
                    HOLDER.remove();
                } else {
                    HOLDER.set(snapshot);
                }
                task.run();
            } finally {
                if (previous == null) {
                    HOLDER.remove();
                } else {
                    HOLDER.set(previous);
                }
            }
        };
    }
}
