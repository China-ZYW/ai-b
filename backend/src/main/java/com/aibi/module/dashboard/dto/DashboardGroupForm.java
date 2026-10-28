package com.aibi.module.dashboard.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 驾驶舱分组创建 / 更新表单。
 *
 * @param name     分组名（同级内允许重名，不做唯一约束——树形目录重名不产生歧义）
 * @param parentId 父分组 id（空=根级；更新时可借此移动整个子树）
 */
public record DashboardGroupForm(
        @NotBlank(message = "分组名称不能为空") String name,
        Long parentId) {
}
