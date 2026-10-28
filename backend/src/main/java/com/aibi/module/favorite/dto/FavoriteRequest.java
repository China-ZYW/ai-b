package com.aibi.module.favorite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 收藏一条问数（M4 / 开发计划 T4.4）。
 *
 * <p>收藏保存"问题 + 已验证 SQL"，便于用户一键复用高频问法；{@code title} 可空，前端可用问题兜底。
 *
 * @param datasourceId 关联数据源
 * @param title        收藏标题（可空）
 * @param question     自然语言问题
 * @param sql          已验证的 SQL（可空，收藏纯问题时）
 */
public record FavoriteRequest(
        @NotNull(message = "数据源不能为空") Long datasourceId,
        @Size(max = 128, message = "标题过长（<=128 字）") String title,
        @NotBlank(message = "问题不能为空") @Size(max = 500, message = "问题过长（<=500 字）") String question,
        String sql
) {
}
