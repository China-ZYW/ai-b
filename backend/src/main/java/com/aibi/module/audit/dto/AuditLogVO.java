package com.aibi.module.audit.dto;

import java.time.LocalDateTime;

/**
 * 审计日志视图对象（§9.2 治理组 · 审计日志）。
 *
 * <p>{@code auth_audit_log} 行 + 批量联查出的可读名（操作人/被操作人用户名、租户名），
 * 供平台后台跨租户检索页渲染。绝不含口令与凭证（{@code detail} 已在写入侧脱敏）。
 *
 * @param tenantId       事件所属租户；{@code 0}=平台域
 * @param tenantName     租户名（{@code tenantId=0} 或租户已删时为空，前端渲染「平台」/「#id」）
 * @param uid            操作人 {@code app_user.id}（匿名事件为空）
 * @param username       操作人用户名（联 {@code app_user}，账号已删时为空）
 * @param targetUid      被操作人 {@code app_user.id}
 * @param targetUsername 被操作人用户名
 * @param detail         脱敏 JSON（变更前后值、角色码等）
 */
public record AuditLogVO(
        Long id,
        Long tenantId,
        String tenantName,
        Long uid,
        String username,
        Long identityId,
        String actorType,
        String action,
        String resourceType,
        String resourceId,
        Long targetUid,
        String targetUsername,
        String result,
        String detail,
        String ip,
        String userAgent,
        String traceId,
        LocalDateTime createdAt) {
}
