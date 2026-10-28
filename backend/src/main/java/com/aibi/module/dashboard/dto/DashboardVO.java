package com.aibi.module.dashboard.dto;

import java.time.LocalDateTime;

/**
 * 驾驶舱视图对象。{@code layout} 以 JSON 数组透传（{@link WidgetLayout} 列表）。
 */
public record DashboardVO(
        Long id,
        String name,
        String description,
        String templateCode,
        Long groupId,
        String theme,
        Integer cols,
        Integer refreshSeconds,
        Integer isScreen,
        String background,
        Object styleConfig,
        Object layout,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
