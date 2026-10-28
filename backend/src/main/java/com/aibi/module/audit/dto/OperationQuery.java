package com.aibi.module.audit.dto;

import java.time.LocalDateTime;

/**
 * 全局操作日志检索条件（全部可空，空则不约束）。
 *
 * <p>平台后台可传 {@code tenantId} 跨租户检索；组织内接口强制钉死当前租户、不接受该入参（防越权）。
 */
public record OperationQuery(Long tenantId, String module, String method, String uri,
                             String username, LocalDateTime from, LocalDateTime to, int limit, int offset) {
}
