package com.aibi.module.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 指标保存 / 更新表单。来源于一次问数结果（{@code ResultPanel} 的“保存为指标”）。
 *
 * @param datasourceId 指标绑定的数据源（刷新时据此重跑 SQL）
 * @param sql          已通过安全校验的 SELECT（刷新时经 {@code SqlValidator} 复校后执行）
 * @param chartSpec    图表规格 JSON 对象（透传前端 {@code ChartSpec}，服务端按 JSON 存储）
 * @param columnLabels 指标级列展示名（物理列名 → 自定义名，可空）。主要给汇总/派生列
 *                     （{@code SUM(amount) AS total_amount}）取业务叫法——这类列在语义层没有物理列行；
 *                     优先级高于语义层别名，仅影响呈现，不影响图表字段匹配与 SQL
 * @param freshnessHours 该指标的新鲜度 SLA 覆盖（小时，P2）；null=回落全局 {@code FRESHNESS / sla-hours}，
 *                       ≤0 同样视为未设置。T+1 离线表可设 36、实时表可设 4
 * @param source 来源标记：AI 自动分析生成='AI'；null/空=人工维护（MANUAL）。仅自动分析 Skill 写入，
 *               人工保存指标不传此字段（服务端不覆盖已有值，避免编辑时清掉 AI 标识）
 */
public record MetricForm(
        @NotBlank(message = "指标名称不能为空") String name,
        String description,
        String alias,
        String definition,
        String attributionDimension,
        String baselineType,
        Integer freshnessHours,
        @NotNull(message = "数据源不能为空") Long datasourceId,
        String question,
        @NotBlank(message = "SQL 不能为空") String sql,
        Object chartSpec,
        Map<String, String> columnLabels,
        String source) {
}
