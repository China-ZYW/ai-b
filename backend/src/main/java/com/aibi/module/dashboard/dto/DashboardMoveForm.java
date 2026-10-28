package com.aibi.module.dashboard.dto;

/**
 * 驾驶舱移动分组表单：{@code groupId} 为空表示移出分组（未分组）。
 */
public record DashboardMoveForm(Long groupId) {
}
