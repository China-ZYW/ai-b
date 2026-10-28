package com.aibi.module.dashboard.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 驾驶舱模板创建 / 更新表单（仅超管可用）。{@code layout} 为骨架布局 JSON 数组（{@link WidgetLayout} 列表）。
 */
public record DashboardTemplateForm(
        @NotBlank(message = "模板编码不能为空") String code,
        @NotBlank(message = "模板名称不能为空") String name,
        String category,
        String description,
        String icon,
        String theme,
        Object layout) {
}
