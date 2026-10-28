package com.aibi.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 问数请求。
 *
 * @param datasourceId   目标数据源 id（在当前租户范围内解析，跨租户视为不存在）
 * @param question       用户自然语言问题
 * @param conversationId 会话 id（多轮追问传同一值；首轮可空，由后端生成并随响应返回）
 * @param skill          期望使用的问数技能编码（可空=自动路由；见 {@code GET /api/chat/skills}）。
 *                       仅用于调试与运营（如"强制走智能生成"），不确定时留空由路由择优
 * @param clarified      本次是否为已完成口径澄清后的重发（T10-2，可空=false）：前端用户点选澄清候选后
 *                       置 true 重发，后端据此跳过反问门、锁定口径直接执行（保证最多 1 轮）
 */
public record ChatQueryRequest(
        @NotNull(message = "数据源不能为空")
        Long datasourceId,

        @NotBlank(message = "不能为空")
        @Size(max = 500, message = "过长（<=500 字）")
        String question,

        @Size(max = 64, message = "会话 id 过长（<=64 字）")
        String conversationId,

        @Size(max = 32, message = "技能编码过长（<=32 字）")
        String skill,

        Boolean clarified
) {

    /** 是否已完成单轮澄清重发（null 视为未澄清，保证老客户端不传时行为不变）。 */
    public boolean clarifiedOrDefault() {
        return Boolean.TRUE.equals(clarified);
    }
}
