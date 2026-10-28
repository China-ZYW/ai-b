package com.aibi.module.admin.dto;

import java.util.List;

/**
 * 平台概览趋势数据（超管后台首页图表区）。跨租户聚合，仅超级管理员可见。
 *
 * <p>与 {@link AdminOverviewVO}（总数卡片）配套：这里提供近 14 天时间序列与分布数据，
 * 供前端绘制问数趋势、用户增长、套餐分布、活跃租户 TOP 等图表。日期序列由后端补零，
 * 前端拿到即可直接渲染，无需自行对齐日期轴。
 */
public record AdminOverviewTrendVO(
        List<QueryTrendPoint> queryTrend,
        List<SignupTrendPoint> signupTrend,
        List<NameValue> planDistribution,
        List<NameValue> topTenants) {

    /** 单日问数趋势点：date=yyyy-MM-dd，total=当日问数次数，failed=当日失败次数。 */
    public record QueryTrendPoint(String date, long total, long failed) {
    }

    /** 单日新增趋势点：date=yyyy-MM-dd，users=当日新增账号数，tenants=当日新增租户数。 */
    public record SignupTrendPoint(String date, long users, long tenants) {
    }

    /** 通用「名称-数值」项（套餐分布、活跃租户 TOP 等）。 */
    public record NameValue(String name, long value) {
    }
}
