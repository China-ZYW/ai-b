package com.aibi.module.dashboard.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 驾驶舱创建 / 更新表单（含布局保存）。
 *
 * @param theme          light / dark
 * @param cols           网格列数（默认 12）
 * @param refreshSeconds 自动刷新间隔秒（0=不自动刷新）
 * @param isScreen       1=作为全屏大屏
 * @param background     大屏自定义背景（CSS background 值，空=跟随主题）
 * @param styleConfig    大屏级自定义样式覆盖（JSON 对象，透传；空=完全跟随主题）
 * @param layout         网格布局 JSON 数组（{@link WidgetLayout} 列表，透传）
 * @param groupId        所属分组（仅 create 生效；update 是全量 set，编辑器历史 payloads 不携带分组，
 *                       若在 update 里 set 会把分组静默清空——改分组一律走 PUT /{id}/group 专用端点）
 */
public record DashboardForm(
        @NotBlank(message = "驾驶舱名称不能为空") String name,
        String description,
        String theme,
        Integer cols,
        Integer refreshSeconds,
        Integer isScreen,
        String background,
        Object styleConfig,
        Object layout,
        Long groupId) {
}
