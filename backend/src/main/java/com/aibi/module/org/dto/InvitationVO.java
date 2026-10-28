package com.aibi.module.org.dto;

import java.time.LocalDateTime;

/**
 * 邀请视图对象（§9.2 组织设置 · 邀请列表，{@code member:invite:read}）。
 *
 * <p><b>绝不含 {@code tokenHash}</b>：令牌哈希属敏感凭证，列表/详情一律不回传，防止前端或日志侧泄露。
 * 明文 {@code token} 仅在 {@link InvitationCreateResult} 创建结果里返回一次。
 *
 * @param id           {@code invitation.id}
 * @param channel      渠道：LINK/EMAIL/SMS/BATCH/APPLY
 * @param roleCode     接受后授予的组织角色
 * @param seats        可接受次数上限（{@code -1}=不限）
 * @param usedCount    已接受次数
 * @param requireApproval 1=接受后进入待审批
 * @param needLogin    1=须登录后接受（防匿名占席）
 * @param status       DRAFT/SENT/PENDING/APPROVED/REJECTED/ACCEPTED/EXPIRED/REVOKED
 * @param expireAt     过期时间（{@code null}=不过期）
 * @param invitedBy    发起人 {@code uid}
 */
public record InvitationVO(
        Long id,
        String channel,
        String roleCode,
        Long teamId,
        String targetEmail,
        String allowDomain,
        Integer seats,
        Integer usedCount,
        Integer requireApproval,
        Integer needLogin,
        String status,
        LocalDateTime expireAt,
        Long invitedBy,
        String message,
        LocalDateTime createdAt) {
}
