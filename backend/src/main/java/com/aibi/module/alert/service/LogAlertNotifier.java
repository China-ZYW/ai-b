package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 站内日志渠道（{@code LOG}）：把告警打成一条 WARN 日志。
 *
 * <p><b>它同时是兜底渠道</b>：{@code aibi.notify.sender: LOG} 时，任何"未接入 / 不可用"的规则渠道都会
 * 回落到这里——告警绝不会因为渠道没配好而彻底消失。故本实现恒 {@link #available()} 为 true。
 */
@Service
public class LogAlertNotifier implements AlertNotifier {

    /** 渠道码。 */
    public static final String CHANNEL = "LOG";

    private static final Logger log = LoggerFactory.getLogger(LogAlertNotifier.class);

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public void notify(AlertRule rule, AlertRecord record) {
        log.warn("[ALERT][{}] tenant={} rule={}(id={}) metric={} value={} {} threshold={} | {}",
                record.getSeverity(), record.getTenantId(), rule.getName(), rule.getId(),
                record.getMetricKey(), record.getValue(), rule.getOperator(),
                rule.getThreshold(), record.getMessage());
    }
}
