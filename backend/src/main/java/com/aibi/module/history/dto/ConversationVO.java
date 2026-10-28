package com.aibi.module.history.dto;

import java.time.LocalDateTime;

/**
 * 会话摘要视图（M4）：用于前端左侧"对话列表"，每条代表一个多轮会话。
 *
 * @param conversationId 会话 id
 * @param datasourceId   该会话最近一次问数使用的数据源
 * @param lastQuestion   最近一条问题（列表标题）
 * @param messageCount   会话内问答条数
 * @param updatedAt      最近更新时间
 */
public record ConversationVO(
        String conversationId,
        Long datasourceId,
        String lastQuestion,
        Integer messageCount,
        LocalDateTime updatedAt
) {
}
