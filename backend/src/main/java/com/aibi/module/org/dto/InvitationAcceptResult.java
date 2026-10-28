package com.aibi.module.org.dto;

/**
 * 邀请接受结果视图（{@code InvitationService.accept} 返回）。
 *
 * <p>仅投影被邀请者需要的信息：所加入的组织、被授予的角色、最终状态与一句提示。
 * 绝不回传令牌哈希或邀请 id 之外的敏感字段。
 */
public record InvitationAcceptResult(
        /** 所加入的组织 id（即邀请所属租户）。 */
        Long tenantId,
        /** 组织名称（用于落地页「你已加入 XXX」）。 */
        String tenantName,
        /** 接受后授予的组织角色码。 */
        String roleCode,
        /** 最终状态：ACCEPTED（直接生效）或 PENDING（待管理员审批）。 */
        String status,
        /** 面向用户的提示文案。 */
        String message) {
}
