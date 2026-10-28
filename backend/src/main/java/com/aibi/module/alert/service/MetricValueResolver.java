package com.aibi.module.alert.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 指标当前值解析器。引擎通过它把规则里的 {@code metricKey} 翻译成实时数值。
 * 未知 key 返回 {@link Optional#empty()}，上层跳过该规则（不报错），便于后续平滑扩展新指标。
 */
public interface MetricValueResolver {

    /** 解析某指标在指定租户的实时值；无法解析返回 empty。 */
    Optional<BigDecimal> resolve(Long tenantId, String metricKey);
}
