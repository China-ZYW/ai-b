package com.aibi.module.alert.dto;

import java.math.BigDecimal;

/**
 * 预警规则创建/更新表单（请求体）。所有字段可空（更新时按需传），由 Service 做空值跳过。
 *
 * @param name         规则名
 * @param description  说明
 * @param metricKey    指标键（如 query.count.24h）
 * @param operator     比较算子 GT/LT/GTE/LTE
 * @param threshold    阈值
 * @param severity     严重级别 INFO/WARN/CRITICAL
 * @param enabled      是否启用
 * @param notifyChannel 通知渠道 LOG/EMAIL（SMS/WEBHOOK 为扩展位）
 * @param notifyTarget  通知目标（EMAIL=收件人，逗号分隔；WEBHOOK=URL；SMS=手机号）
 * @param windowMinutes 去重窗口（分钟）
 * @param autoAttribution 触发时是否自动预跑归因（仅业务指标 metric:&lt;id&gt; 生效）
 * @param attributionDimension 归因维度（物理列名）；空=用指标沉淀的默认维度
 */
public record AlertRuleForm(
        String name,
        String description,
        String metricKey,
        String operator,
        BigDecimal threshold,
        String severity,
        Boolean enabled,
        String notifyChannel,
        String notifyTarget,
        Integer windowMinutes,
        Boolean autoAttribution,
        String attributionDimension
) {
}
