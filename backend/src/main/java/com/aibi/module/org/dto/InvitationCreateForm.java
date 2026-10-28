package com.aibi.module.org.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发起邀请表单（{@code member:invite:create}，矩阵 YYNNNN → 仅 OWNER/ADMIN）。
 *
 * <p>服务层据此生成邀请令牌：{@code token} 明文仅在创建结果里返回一次，落库只存
 * {@code SHA-256(token)}（{@link com.aibi.common.security.TokenDigest}），避免令牌泄露即被冒用。
 *
 * <p>缺省规则（{@code null}/空 → 取默认，见 {@code InvitationService.create}）：
 * <ul>
 *   <li>{@code channel} 空 → {@code LINK}（链接邀请）；</li>
 *   <li>{@code teamId} 空 → 组织默认团队（{@code tenant.default_team_id}）；</li>
 *   <li>{@code seats} 空 → 1；{@code -1} 表示不限，但受 {@code max-seats-per-link} 上限约束；</li>
 *   <li>{@code expireDays} 空 → {@code token-ttl-days}（默认 7 天）；</li>
 *   <li>{@code requireApproval} 空 → 0（无需审批，接受即入组）。</li>
 * </ul>
 *
 * <p>{@code roleCode} 必须是 {@link com.aibi.common.security.OrgRole} 之一且<b>可授予</b>
 * （{@code OWNER} 属 NON_GRANTABLE，不可通过邀请授予）。
 */
public record InvitationCreateForm(
        String channel,
        @NotBlank(message = "角色不能为空") String roleCode,
        Long teamId,
        String targetEmail,
        String allowDomain,
        Integer seats,
        Integer expireDays,
        Integer requireApproval,
        String message) {
}
