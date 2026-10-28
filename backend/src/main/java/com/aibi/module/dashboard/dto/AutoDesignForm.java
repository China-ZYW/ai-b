package com.aibi.module.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 一键自动布局表单：给定一组指标，生成 12 列均衡网格驾驶舱。
 *
 * <p>服务端按每个指标的图表类型定尺寸（KPI/TABLE 小、BAR/LINE/PIE 中），逐行填充。
 *
 * @param metricIds 要编排的指标 id 列表（顺序影响布局先后）
 */
public record AutoDesignForm(
        @NotBlank(message = "驾驶舱名称不能为空") String name,
        @NotEmpty(message = "至少选择一个指标") List<Long> metricIds,
        String description,
        String theme,
        Integer refreshSeconds,
        Integer isScreen) {
}
