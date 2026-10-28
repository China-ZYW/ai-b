package com.aibi.module.dashboard.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 指标视图对象。{@code chartSpec} 以 JSON 对象透传（非转义字符串），前端可直接喂给 {@code buildChartOption}。
 *
 * @param columnLabels 指标级列展示名（物理列名 → 自定义名）；无自定义时为 null，
 *                     前端编辑抽屉据此回填，刷新结果的展示名由服务端已合并后下发
 * @param refreshedAt  数据最后成功刷新时间（P2 新鲜度）；null=从未刷新
 * @param freshnessHours 该指标的新鲜度 SLA 覆盖（小时）；null=回落全局阈值
 * @param stale        是否已超时（服务端按 SLA 判定，前端据此标黄，避免两端各算一套口径）
 * @param neverRefreshed 是否从未刷新过（与 stale 区分：前者提示"尚未取数"，后者提示"已过期 X 小时"）
 * @param ageHours     距今小时数（refreshedAt 为 null 时取 -1）
 * @param source       来源标记：AI 自动分析生成='AI'；null/空=人工维护（MANUAL）。前端据此展示 AI 自动识别标识
 */
public record MetricVO(
        Long id,
        String name,
        String description,
        String alias,
        String definition,
        String attributionDimension,
        String baselineType,
        /** 所属分组（{@code resource_group.id}，METRIC 域；空=未分组）。移动归属走专用端点。 */
        Long groupId,
        Integer freshnessHours,
        Long datasourceId,
        String question,
        String sql,
        Object chartSpec,
        Map<String, String> columnLabels,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime refreshedAt,
        boolean stale,
        boolean neverRefreshed,
        long ageHours,
        String source) {
}
