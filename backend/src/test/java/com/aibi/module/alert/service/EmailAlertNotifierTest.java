package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件通知渠道的「可用性判定」与「收件人解析」口径（v3.30 / §48）。
 *
 * <p>本测试<b>只覆盖纯逻辑、不碰真实 SMTP</b>（不发信），重点钉两处最容易出错的地方：
 * <ol>
 *   <li><b>可用性</b>：没配 host / 没发件人时 {@code available()} 必须为 false——
 *       路由据此回落兜底渠道；若这里误判为 true，邮件会直接抛错，告警反而变成"未通知"。</li>
 *   <li><b>收件人解析</b>：中英文逗号/分号/空白混用、去重保序、明显非法地址被剔除
 *       （一个漏了 @ 的地址会让整封邮件被 SMTP 拒收，不能连累合法收件人）。</li>
 * </ol>
 */
class EmailAlertNotifierTest {

    /** 用一段内存配置构造通知器（配置改由 {@link NotifyConfigService} 提供，测试不碰库、不发信）。 */
    private static EmailAlertNotifier notifier(boolean enabled, String host, String from) {
        return new EmailAlertNotifier(stubConfig(enabled, host, from));
    }

    /** 桩：只覆写"取渠道配置"，避免单测依赖 sys_config。 */
    private static NotifyConfigService stubConfig(boolean enabled, String host, String from) {
        java.util.Map<String, Object> email = new java.util.LinkedHashMap<>();
        email.put("enabled", enabled);
        email.put("host", host);
        email.put("port", 465);
        email.put("username", "bot@example.com");
        email.put("password", "pwd");
        email.put("from", from);
        email.put("sslEnabled", true);
        email.put("timeoutMs", 3000);
        return new NotifyConfigService(null, new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public java.util.Map<String, Object> channel(String code) {
                return email;
            }

            @Override
            public java.util.Map<String, Object> raw() {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("fallbackChannel", "LOG");
                m.put("email", email);
                return m;
            }
        };
    }

    @Test
    @DisplayName("可用性：开关关 / 缺 host / 缺发件人 → 均不可用（路由会回落兜底渠道）")
    void availableRequiresSwitchHostAndFrom() {
        assertFalse(notifier(false, "smtp.example.com", "bot@example.com").available(), "开关关闭");
        assertFalse(notifier(true, "", "bot@example.com").available(), "缺 host");
        assertTrue(notifier(true, "smtp.example.com", "bot@example.com").available(), "配置齐全");
    }

    @Test
    @DisplayName("未单独配 from 时回落 username：配了 host+username 即视为可用")
    void fromFallsBackToUsername() {
        EmailAlertNotifier n = notifier(true, "smtp.example.com", "");
        assertTrue(n.available(), "from 为空应回落 username（构造时已传入 bot@example.com）");
    }

    @Test
    @DisplayName("渠道码恒为 EMAIL（路由按此注册）")
    void channelCode() {
        assertEquals("EMAIL", notifier(true, "h", "f@x.com").channel());
        assertEquals(EmailAlertNotifier.CHANNEL, notifier(false, "", "").channel());
    }

    @Test
    @DisplayName("收件人解析：中英文逗号/分号/空白混用、去重保序")
    void parseRecipientsMixedSeparators() {
        EmailAlertNotifier n = notifier(true, "h", "f@x.com");
        List<String> got = n.parseRecipients("a@x.com, b@x.com；c@x.com;  d@x.com\ne@x.com a@x.com");
        assertEquals(List.of("a@x.com", "b@x.com", "c@x.com", "d@x.com", "e@x.com"), got,
                "应支持中英文分隔符、去重且保持首次出现顺序");
    }

    @Test
    @DisplayName("★收件人解析：明显非法地址被剔除，不连累合法收件人")
    void parseRecipientsDropsInvalid() {
        EmailAlertNotifier n = notifier(true, "h", "f@x.com");
        assertEquals(List.of("ok@x.com"), n.parseRecipients("ok@x.com, not-an-email, @nope.com, tail@"));
    }

    @Test
    @DisplayName("收件人为空 / 空白 / null → 返回空列表（调用方据此报错）")
    void parseRecipientsEmpty() {
        EmailAlertNotifier n = notifier(true, "h", "f@x.com");
        assertTrue(n.parseRecipients(null).isEmpty());
        assertTrue(n.parseRecipients("   ").isEmpty());
        assertTrue(n.parseRecipients(",,  ;;").isEmpty());
    }

    @Test
    @DisplayName("★规则没填收件人 → 抛 AlertNotifyException（引擎据此把 notified 记为 false，不假装已通知）")
    void emptyRecipientsThrows() {
        EmailAlertNotifier n = notifier(true, "smtp.example.com", "bot@example.com");
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("无收件人规则");
        rule.setNotifyTarget("");

        AlertNotifyException e = assertThrows(AlertNotifyException.class,
                () -> n.notify(rule, new AlertRecord()));
        assertTrue(e.getMessage().contains("收件人"), "异常信息应指出缺收件人，便于运维定位：" + e.getMessage());
    }

    @Test
    @DisplayName("渠道不可用时被直接调用 → 抛 AlertNotifyException（而非静默成功）")
    void unavailableThrows() {
        EmailAlertNotifier n = notifier(false, "smtp.example.com", "bot@example.com");
        AlertRule rule = new AlertRule();
        rule.setNotifyTarget("a@x.com");

        assertThrows(AlertNotifyException.class, () -> n.notify(rule, new AlertRecord()));
    }
}
