package com.aibi.module.dashboard.dto;

/**
 * 驾驶舱内置模板视图对象。{@code layout} 为骨架布局 JSON 数组（{@link WidgetLayout} 列表，含占位槽）。
 */
public record DashboardTemplateVO(
        Long id,
        String code,
        String name,
        String category,
        String description,
        String icon,
        String theme,
        Object layout) {
}
