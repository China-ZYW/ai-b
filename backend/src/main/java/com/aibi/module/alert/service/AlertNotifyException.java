package com.aibi.module.alert.service;

/**
 * 预警通知<b>投递失败</b>（渠道已尝试发送但失败，如 SMTP 拒连、Webhook 超时）。
 *
 * <p>与"渠道不可用"区分：不可用由 {@link AlertNotifier#available()} 表达、由路由回落兜底渠道处理，
 * <b>不抛本异常</b>；只有真的尝试投递并失败才抛，以便 {@code AlertEngineService} 把
 * {@code alert_record.notified} 记为 false，让"没发出去的告警"在数据上可见。
 */
public class AlertNotifyException extends RuntimeException {

    public AlertNotifyException(String message) {
        super(message);
    }

    public AlertNotifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
