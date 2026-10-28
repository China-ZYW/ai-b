package com.aibi.module.dashboard.dto;

import java.util.List;

/**
 * 驾驶舱整体数据（大屏批量刷新）：驾驶舱配置 + 全部 widget 的实时数据。
 *
 * <p>{@code GET /api/dashboard/{id}/data} 一次返回，供大屏按 {@code refreshSeconds} 轮询。
 */
public record DashboardDataVO(
        DashboardVO dashboard,
        List<WidgetDataVO> widgets) {
}
