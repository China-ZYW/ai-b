package com.aibi.module.attribution.dto;

import java.math.BigDecimal;

/**
 * 趋势序列中的一个数据点（按期聚合的度量值），供前端画趋势折线。
 *
 * <p>period 为时间列原值（如 {@code 2026-09} 或 {@code 2026-09-19}，已按 ASC 排序），
 * value 为该期度量合计。与三套审计 SQL 解耦——趋势数据独立执行并返回，
 * 前端既可画折线、也可在「趋势 SQL」标签页查看对应的只读语句。
 */
public record AttributionTrendPoint(String period, BigDecimal value) {
}
