package com.aibi.module.dashboard.dto;

import com.aibi.module.datasource.model.QueryResult;

/**
 * 指标实时数据（刷新结果）：重跑已存 SQL 得到的最新 {@link QueryResult} + 图表规格。
 *
 * <p>用于 {@code POST /api/metric/{id}/refresh} 与驾驶舱 widget 渲染，<b>不消耗 LLM、不计入问数配额</b>。
 *
 * @param chartSpec 图表规格 JSON 对象（透传，前端据此渲染）
 */
public record MetricDataVO(
        Long metricId,
        String name,
        Object chartSpec,
        QueryResult result) {
}
