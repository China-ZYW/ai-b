package com.aibi.module.share.dto;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.datasource.model.QueryResult;

import java.time.LocalDateTime;

/**
 * 公开访问页展示的数据（M7）：只含快照，不含租户/用户/数据源凭证等任何内部信息。
 *
 * @param question  分享的问题
 * @param sql       生成结果的 SQL（可展示）
 * @param result    结果快照（列 + 截断后的数据行）
 * @param chart     图表推荐快照（可空）
 * @param watermark 是否需加水印（免费版为 true，前端据此叠加水印）
 * @param createdAt 分享创建时间
 * @param expireAt  过期时间（空=永不过期）
 */
public record SharedView(
        String question,
        String sql,
        QueryResult result,
        ChartSpec chart,
        boolean watermark,
        LocalDateTime createdAt,
        LocalDateTime expireAt
) {
}
