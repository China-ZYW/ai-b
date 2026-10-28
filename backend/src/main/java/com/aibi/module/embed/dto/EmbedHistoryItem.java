package com.aibi.module.embed.dto;

import java.time.LocalDateTime;

/**
 * 嵌入问数·外部用户历史条目（公开接口返回用）。
 *
 * <p>前端右侧悬浮面板按此渲染：每条含角色、内容、所属会话与发生时间；
 * 点击某条即把该条所在 {@code conversationId} 作为续接会话，主线程据此继续多轮追问。
 */
public record EmbedHistoryItem(
        Long id,
        String role,
        String content,
        String conversationId,
        LocalDateTime createdAt) {

    public static EmbedHistoryItem of(com.aibi.module.embed.entity.ChatEmbedLog e) {
        return new EmbedHistoryItem(e.getId(), e.getRole(), e.getContent(),
                e.getConversationId(), e.getCreatedAt());
    }
}
