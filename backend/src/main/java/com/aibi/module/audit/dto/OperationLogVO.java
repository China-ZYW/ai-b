package com.aibi.module.audit.dto;

import java.time.LocalDateTime;

/**
 * 全局操作日志只读投影。与 {@link OperationLog} 字段对齐，额外补 {@code tenantName}（批量联查，避免 N+1）。
 */
public record OperationLogVO(Long id, Long tenantId, String tenantName, Long userId, String username,
                             String orgRoles, String platformRoles, String requestId, String module,
                             String action, String method, String uri, String queryString, String requestBody,
                             String ip, String userAgent, Integer httpStatus, String errorMsg,
                             Long durationMs, LocalDateTime createdAt) {
}
