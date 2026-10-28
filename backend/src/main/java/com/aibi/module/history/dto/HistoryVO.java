package com.aibi.module.history.dto;

import java.time.LocalDateTime;

/**
 * 查询历史条目视图（M4 / 开发计划 T4.4）。
 *
 * @param id             历史记录 id
 * @param conversationId 所属会话 id（多轮追问共用）
 * @param datasourceId   数据源 id
 * @param question       自然语言问题
 * @param sql            生成的 SQL（失败时可能为空）
 * @param rowCount       结果行数
 * @param elapsedMs      执行耗时（毫秒）
 * @param status         OK / FAILED
 * @param createdAt      创建时间
 * @param feedback       用户反馈：1=赞 -1=踩（空=未反馈）
 * @param chartRequested 问题显式指定的图表类型
 * @param chartUsed      最终推荐/使用的图表类型
 */
public record HistoryVO(
        Long id,
        String conversationId,
        Long datasourceId,
        String question,
        String sql,
        Integer rowCount,
        Long elapsedMs,
        String status,
        LocalDateTime createdAt,
        Integer feedback,
        String chartRequested,
        String chartUsed
) {
}
