package com.aibi.module.mcp.dto;

import com.aibi.module.mcp.entity.McpPat;

import java.time.LocalDateTime;

/**
 * MCP 令牌视图（后台列表用）。
 *
 * <p><b>刻意不含任何可还原明文的字段</b>：只有 {@code tokenPrefix}（展示/辨认用），
 * 明文仅在创建响应里出现一次（见 {@code McpPatService.create}）。
 */
public record McpPatVO(
        Long id,
        Long tenantId,
        Long uid,
        String name,
        /** 明文前缀，如 {@code aibi_pat_9f3c...}；仅展示。 */
        String tokenPrefix,
        String scopes,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        LocalDateTime lastUsedAt,
        LocalDateTime createdAt,
        /** 是否已失效（已吊销或已过期），供前端标灰。 */
        boolean disabled
) {

    public static McpPatVO of(McpPat e) {
        if (e == null) {
            return null;
        }
        return new McpPatVO(e.getId(), e.getTenantId(), e.getUid(), e.getName(), e.getTokenPrefix(),
                e.getScopes(), e.getExpiresAt(), e.getRevokedAt(), e.getLastUsedAt(), e.getCreatedAt(),
                !e.usable(LocalDateTime.now()));
    }
}
