package com.aibi.module.dashboard.dto;

/**
 * 驾驶舱分组视图对象（扁平列表，前端据此组树；{@code parentId} 空=根级）。
 */
public record DashboardGroupVO(
        Long id,
        String name,
        Long parentId,
        Integer sort,
        java.time.LocalDateTime createdAt) {
}
