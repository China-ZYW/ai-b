package com.aibi.module.alert.dto;

import com.aibi.module.alert.entity.AlertRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预警规则视图对象（列表/详情返回）。
 *
 * @param id           规则 id
 * @param name         规则名
 * @param description  说明
 * @param metricKey    指标键
 * @param operator     比较算子
 * @param threshold    阈值
 * @param severity     严重级别
 * @param enabled      是否启用
 * @param notifyChannel 通知渠道 LOG/EMAIL（SMS/WEBHOOK 为扩展位）
 * @param notifyTarget  通知目标（EMAIL=收件人，逗号分隔）
 * @param windowMinutes 去重窗口（分钟）
 * @param autoAttribution 触发时是否自动预跑归因
 * @param attributionDimension 归因维度（物理列名）；空=用指标沉淀的默认维度
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record AlertRuleVO(
        Long id,
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
        String attributionDimension,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AlertRuleVO from(AlertRule r) {
        return new AlertRuleVO(r.getId(), r.getName(), r.getDescription(), r.getMetricKey(),
                r.getOperator(), r.getThreshold(), r.getSeverity(), r.getEnabled(),
                r.getNotifyChannel(), r.getNotifyTarget(), r.getWindowMinutes(),
                r.getAutoAttribution(), r.getAttributionDimension(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
