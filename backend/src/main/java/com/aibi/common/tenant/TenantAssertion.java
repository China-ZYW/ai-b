package com.aibi.common.tenant;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;

/**
 * 租户归属断言工具：为<b>不受租户拦截器自动保护</b>的读取路径兜底，见《角色权限体系与组织协同设计》5.6 / 6.5。
 *
 * <p>三类路径必须调用本类：
 * <ol>
 *   <li>表被列入 {@code MybatisPlusConfig.IGNORE_TABLES}（如 {@code team_member}/{@code user_role}/
 *       {@code invitation}）——自动注入已关闭，隔离全靠 SQL 里显式写的 {@code tenant_id}，
 *       一旦漏写就是静默越权；</li>
 *   <li>Mapper 方法标了 {@code @InterceptorIgnore(tenantLine="true")}（复杂 JOIN/聚合、登录链路）；</li>
 *   <li>按全局唯一键寻址的行（{@code share_link.token}、{@code login_session.sid}、
 *       {@code invitation.token_hash}）——这类查询天然无法带 {@code tenant_id}。</li>
 * </ol>
 *
 * <p><b>统一抛 40310（RESOURCE_FORBIDDEN，"资源不存在或无权访问"）而非 40301</b>：
 * 对跨租户访问，"存在但无权"与"根本不存在"必须给出同一响应，否则会泄露其他组织的资源 id 是否存在
 * （可被用于枚举探测）。真实原因只写日志，不回传客户端。
 */
public final class TenantAssertion {

    private TenantAssertion() {
    }

    /**
     * 断言行的租户归属与当前上下文一致。
     *
     * @param rowTenantId 数据行的 {@code tenant_id}；为 {@code null} 视为全局行（如 {@code llm_config} 的默认配置），直接放行
     * @param ctxTenantId 期望的租户 id；为 {@code null} 表示当前无租户上下文，除全局行外一律拒绝
     */
    public static void assertMatch(Long rowTenantId, Long ctxTenantId) {
        if (rowTenantId == null) {
            return;
        }
        if (ctxTenantId == null || !rowTenantId.equals(ctxTenantId)) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "资源不存在或无权访问");
        }
    }

    /** 断言行的租户归属与 {@link TenantContext} 当前租户一致。 */
    public static void assertMatch(Long rowTenantId) {
        assertMatch(rowTenantId, TenantContext.tenantId());
    }

    /**
     * 非抛出版本：用于列表过滤等"跳过而非中断"的场景（如批量取回后剔除他人行）。
     *
     * <p>注意别用它替代 {@link #assertMatch}：单资源读取路径若只是静默跳过，
     * 调用方拿到 {@code null} 后往往会报 404，掩盖了"越权访问尝试"这一安全信号。
     */
    public static boolean matches(Long rowTenantId, Long ctxTenantId) {
        if (rowTenantId == null) {
            return true;
        }
        return rowTenantId.equals(ctxTenantId);
    }

    /**
     * 断言行的租户归属与当前上下文一致，并额外校验资源所有者（{@code visibility=PRIVATE} 的资源）。
     *
     * @param rowTenantId 数据行的 {@code tenant_id}
     * @param rowOwnerUid 数据行的 {@code uid}（创建者）
     * @param ctxUid      当前操作人 uid
     * @param ownerOnly   为 {@code true} 时仅创建者可访问；为 {@code false} 时只校验租户
     */
    public static void assertOwnership(Long rowTenantId, Long rowOwnerUid, Long ctxUid, boolean ownerOnly) {
        assertMatch(rowTenantId, TenantContext.tenantId());
        if (ownerOnly && rowOwnerUid != null && !rowOwnerUid.equals(ctxUid)) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "资源不存在或无权访问");
        }
    }
}
