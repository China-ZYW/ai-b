package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;

/**
 * 预警通知出口（<b>渠道扩展点</b>）。
 *
 * <p>每个实现对应一个<b>渠道码</b>，由 {@link AlertNotifierRouter} 按 {@link AlertRule#getNotifyChannel()}
 * 路由。当前已接入：{@code LOG}（{@link LogAlertNotifier}）、{@code EMAIL}（{@link EmailAlertNotifier}）、
 * {@code INAPP}（{@link InAppAlertNotifier}，站内信，按规则 {@code notify_target} 指定的用户 id 写库）；
 * {@code SMS} / {@code WEBHOOK} 等第三方渠道<b>只需新增一个 {@code @Service implements AlertNotifier}
 * 并返回自己的 {@link #channel()}</b>，路由与本接口都不必改动。
 *
 * <p><b>异常契约（两类情况必须分开）</b>：
 * <ul>
 *   <li><b>渠道不可用</b>（未配置 / 尚未接入）→ <b>不要抛</b>，由 {@link #available()} 返回 {@code false}
 *       让路由回落到兜底渠道。这样"没配邮件"不会导致告警彻底丢失。</li>
 *   <li><b>真的尝试投递但失败</b>（SMTP 拒连、Webhook 超时等）→ <b>抛</b> {@link AlertNotifyException}。
 *       {@code AlertEngineService} 会捕获并把 {@code alert_record.notified} 记成 false，
 *       让管理员能看出"这条告警没发出去"，而不是以为已通知。</li>
 * </ul>
 */
public interface AlertNotifier {

    /** 渠道码（大写，与 {@link AlertRule#getNotifyChannel()} 取值一致）：LOG / EMAIL / INAPP / SMS / WEBHOOK。 */
    String channel();

    /**
     * 渠道当前是否可用（配置齐备、依赖就绪）。
     *
     * <p>返回 {@code false} 时路由会回落到 {@code aibi.notify.sender} 指定的兜底渠道。
     * 默认 {@code true}；仅"依赖外部配置"的渠道需要覆写。
     */
    default boolean available() {
        return true;
    }

    /**
     * 规则命中时投递通知。
     *
     * @throws AlertNotifyException 投递失败（见类注释的异常契约）
     */
    void notify(AlertRule rule, AlertRecord record);
}
