package com.aibi.module.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 租户创建 / 更新表单（超管后台）。
 *
 * <p>{@code isolationMode} 留空则按套餐取默认；{@code dailyLimit} 留空则按套餐兜底；
 * {@code totalLimit} 留空则默认不限（{@code -1}）；{@code expireAt} 留空表示不过期。
 *
 * @param plan FREE / PRO / ENTERPRISE
 * @param status 1=正常 0=停用
 */
public record TenantForm(
        @NotBlank(message = "租户名称不能为空") String name,
        @NotBlank(message = "套餐不能为空") String plan,
        String isolationMode,
        Integer status,
        LocalDateTime expireAt,
        Integer dailyLimit,
        Long totalLimit) {
}
