package com.aibi.module.alert.dto;

import com.aibi.module.alert.entity.AlertRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预警触发记录视图对象（「触发记录」列表返回）。
 *
 * @param id        记录 id
 * @param ruleId    命中规则 id
 * @param ruleName  命中规则名
 * @param metricKey 指标键
 * @param value     实测值
 * @param operator  比较算子
 * @param threshold 阈值
 * @param severity  严重级别
 * @param status    状态 FIRED/ACK/RESOLVED
 * @param message   说明
 * @param notified  是否已通知
 * @param attribution 触发时预跑的归因摘要（未开启/未产生时为 null）
 * @param createdAt 触发时间
 */
public record AlertRecordVO(
        Long id,
        Long ruleId,
        String ruleName,
        String metricKey,
        BigDecimal value,
        String operator,
        BigDecimal threshold,
        String severity,
        String status,
        String message,
        Boolean notified,
        AlertAttributionVO attribution,
        LocalDateTime createdAt
) {

    private static final Logger log = LoggerFactory.getLogger(AlertRecordVO.class);

    /** 无归因摘要的构造（列表接口不带 ObjectMapper 时使用；新代码一律用 {@link #from(AlertRecord, ObjectMapper)}）。 */
    public static AlertRecordVO from(AlertRecord r) {
        return new AlertRecordVO(r.getId(), r.getRuleId(), r.getRuleName(), r.getMetricKey(),
                r.getValue(), r.getOperator(), r.getThreshold(), r.getSeverity(),
                r.getStatus(), r.getMessage(), r.getNotified(), null, r.getCreatedAt());
    }

    /**
     * 带归因摘要的构造：把库里存的 {@code attribution} JSON 反序列化回结构化对象。
     *
     * <p>反序列化失败（历史脏数据 / 归因 VO 结构演进）只降级为 null 并告警，
     * 不让一条坏记录拖垮整个记录列表——归因是增强信息，缺了它记录本身依然完整可读。
     */
    public static AlertRecordVO from(AlertRecord r, ObjectMapper om) {
        AlertAttributionVO avo = null;
        String raw = r.getAttribution();
        if (raw != null && !raw.isBlank() && om != null) {
            try {
                avo = om.readValue(raw, AlertAttributionVO.class);
            } catch (Exception e) {
                log.warn("预警记录 {} 归因摘要解析失败，按无归因展示: {}", r.getId(), e.getMessage());
            }
        }
        return new AlertRecordVO(r.getId(), r.getRuleId(), r.getRuleName(), r.getMetricKey(),
                r.getValue(), r.getOperator(), r.getThreshold(), r.getSeverity(),
                r.getStatus(), r.getMessage(), r.getNotified(), avo, r.getCreatedAt());
    }
}
