package com.aibi.module.dashboard.dto;

/**
 * 驾驶舱下钻/联动筛选条件（V4.44）：点击某 widget 的维度成员后，整屏按该维度切片。
 *
 * <p>{@code dim} 必须是目标指标结果集的<b>真实列名</b>（服务端白名单校验，仅允许命中实际列名的维度，
 * 杜绝把任意字符串拼进 SQL）；{@code value} 由 JDBC 参数化绑定，不做字符串拼接，从根上防注入。
 *
 * <p>范围（scope）为整屏联动：任一 widget 下钻后，屏内所有 widget 都按同一 {@code dim=value} 重新取数。
 *
 * @param dim   筛选维度列名（如 region / category / 地区）
 * @param value 维度成员值（如 "华东" / 2024）；原样透传给 {@code PreparedStatement}
 */
public record DashboardFilter(String dim, Object value) {
}
