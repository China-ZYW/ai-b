package com.aibi.module.audit.dto;

import java.time.LocalDateTime;

/**
 * 审计检索条件（§9.2：按 action/租户/操作人/时间/结果过滤）。
 *
 * <p>所有过滤字段均可空，空则不约束；由 {@code AdminAuditController} 从 {@code @RequestParam} 组装，
 * 交给 {@code AuditService.search}。{@code limit}/{@code offset} 为分页参数，服务层会做上限保护
 * （单页不超过 {@code AuditService.MAX_PAGE_SIZE}），导出走独立的更大上限。
 *
 * @param from 起始时间（含）
 * @param to   截止时间（含）
 */
public record AuditQuery(
        Long tenantId,
        String action,
        Long uid,
        String result,
        LocalDateTime from,
        LocalDateTime to,
        int limit,
        int offset) {
}
