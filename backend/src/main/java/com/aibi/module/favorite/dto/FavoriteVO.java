package com.aibi.module.favorite.dto;

import java.time.LocalDateTime;

/**
 * 收藏条目视图（M4）。
 *
 * @param id           收藏 id
 * @param datasourceId 关联数据源
 * @param title        标题（可空）
 * @param question     自然语言问题
 * @param sql          已验证 SQL（可空）
 * @param createdAt    收藏时间
 */
public record FavoriteVO(
        Long id,
        Long datasourceId,
        String title,
        String question,
        String sql,
        LocalDateTime createdAt
) {
}
