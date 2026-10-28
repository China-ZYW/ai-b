package com.aibi.module.org.dto;

import java.time.LocalDateTime;

/**
 * 邀请预览（匿名「加入组织」落地页消费，{@code GET /api/public/invitation/preview}）。
 *
 * <p>凭明文令牌只读返回邀请概要，供落地页展示：组织名、角色、留言、有效期与当前可用性状态。
 * 与 {@link InvitationVO} 不同，本 VO 面向<b>未认证</b>访问者，故绝不含 {@code tokenHash}、邀请 id、
 * 定向邮箱等内部/隐私字段；令牌无效时只回 {@code status=INVALID} 而不泄露任何组织信息（防枚举探测）。
 *
 * <p>{@code status} 取值见本类常量：OK（可接受）/ EXPIRED（已过期）/ REVOKED（已撤销）/
 * EXHAUSTED（席位已满）/ INVALID（令牌不存在或已失效）。接受链路（accept）尚未开放（依赖多组织
 * 身份登录），故落地页仅展示信息与引导登录，不提供在线接受动作。
 *
 * @param status          可用性状态（本类 {@code STATUS_*} 常量之一）
 * @param tenantName      邀请方组织名（{@code status=INVALID} 时为 null）
 * @param roleCode        接受后将授予的组织角色
 * @param channel         邀请渠道 LINK/EMAIL
 * @param message         邀请留言（可空）
 * @param requireApproval 1=接受后需审批
 * @param needLogin       1=须登录后接受
 * @param expireAt        过期时间（空=不过期）
 */
public record InvitationPreviewVO(
        String status,
        String tenantName,
        String roleCode,
        String channel,
        String message,
        Integer requireApproval,
        Integer needLogin,
        LocalDateTime expireAt) {

    /** 可接受（令牌有效、未过期、仍有席位）。 */
    public static final String STATUS_OK = "OK";
    /** 已过期（{@code expire_at} 已过）。 */
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** 已撤销（管理员手动作废）。 */
    public static final String STATUS_REVOKED = "REVOKED";
    /** 席位已满（{@code used_count >= seats}）。 */
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";
    /** 令牌不存在或已失效（不泄露任何组织信息）。 */
    public static final String STATUS_INVALID = "INVALID";

    /** 令牌无效时的空预览（只带 {@code INVALID} 状态，其余字段全空）。 */
    public static InvitationPreviewVO invalid() {
        return new InvitationPreviewVO(STATUS_INVALID, null, null, null, null, null, null, null);
    }
}
