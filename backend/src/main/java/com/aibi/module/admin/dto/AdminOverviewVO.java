package com.aibi.module.admin.dto;

/**
 * 平台概览统计（超管后台首页）。跨租户聚合，仅超级管理员可见。
 *
 * @param todayQueryCount 今日问数次数
 * @param failedQueryCount 累计失败问数次数
 */
public record AdminOverviewVO(
        long tenantCount,
        long userCount,
        long datasourceCount,
        long llmConfigCount,
        long dashboardCount,
        long todayQueryCount,
        long totalQueryCount,
        long failedQueryCount) {
}
