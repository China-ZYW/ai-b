package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预警通知路由的渠道分发与<b>回落策略</b>（v3.30 / §48）。
 *
 * <p><b>为什么必须有这个测试</b>：通知路由的失败模式是"告警静默消失"——配错了渠道却不报错、
 * 也不降级，等到出事时才发现从来没收到过告警。所以这里把三类边界全部钉住：
 * <ol>
 *   <li>渠道已接入且可用 → 就用它（不能被无谓回落）；</li>
 *   <li>渠道<b>未接入</b>（选了 SMS 但没实现）/ <b>已接入但不可用</b>（EMAIL 没配 SMTP）
 *       → <b>必须回落</b>兜底渠道，而不是丢弃；</li>
 *   <li>兜底渠道本身也不可用 → <b>不抛异常</b>（少发一封通知，不能让预警主链路失败）。</li>
 * </ol>
 */
class AlertNotifierRouterTest {

    /** 测试用渠道实现：记录是否被调用、可指定可用性。 */
    static class FakeNotifier implements AlertNotifier {
        final String code;
        final boolean available;
        int calls = 0;

        FakeNotifier(String code, boolean available) {
            this.code = code;
            this.available = available;
        }

        @Override
        public String channel() {
            return code;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void notify(AlertRule rule, AlertRecord record) {
            calls++;
        }
    }

    private static AlertRule ruleOf(String channel) {
        AlertRule r = new AlertRule();
        r.setId(7L);
        r.setName("测试规则");
        r.setNotifyChannel(channel);
        return r;
    }

    private final AlertRecord record = new AlertRecord();

    @Test
    @DisplayName("规则渠道已接入且可用：直接用该渠道，不发生回落")
    void usesRequestedChannel() {
        FakeNotifier log = new FakeNotifier("LOG", true);
        FakeNotifier mail = new FakeNotifier("EMAIL", true);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log, mail), "LOG");

        router.notify(ruleOf("EMAIL"), record);

        assertEquals(1, mail.calls, "应调用 EMAIL 渠道");
        assertEquals(0, log.calls, "不应回落去调 LOG");
    }

    @Test
    @DisplayName("★规则渠道未接入（选了 SMS 但没实现）：回落兜底渠道，绝不丢弃告警")
    void fallsBackWhenChannelNotImplemented() {
        FakeNotifier log = new FakeNotifier("LOG", true);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log), "LOG");

        router.notify(ruleOf("SMS"), record);

        assertEquals(1, log.calls, "未接入的渠道必须回落兜底渠道");
    }

    @Test
    @DisplayName("★规则渠道已接入但不可用（选了 EMAIL 但没配 SMTP）：同样回落兜底渠道")
    void fallsBackWhenChannelUnavailable() {
        FakeNotifier log = new FakeNotifier("LOG", true);
        FakeNotifier mail = new FakeNotifier("EMAIL", false);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log, mail), "LOG");

        router.notify(ruleOf("EMAIL"), record);

        assertEquals(0, mail.calls, "不可用的渠道不应被调用");
        assertEquals(1, log.calls, "不可用的渠道必须回落兜底渠道");
    }

    @Test
    @DisplayName("渠道码归一化：大小写与前后空白都能命中（' email ' → EMAIL）")
    void channelCodeIsNormalized() {
        FakeNotifier mail = new FakeNotifier("email", true);
        FakeNotifier log = new FakeNotifier("LOG", true);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log, mail), "LOG");

        router.notify(ruleOf("  EmAiL  "), record);

        assertEquals(1, mail.calls);
        assertEquals(0, log.calls);
    }

    @Test
    @DisplayName("规则未设置渠道（null / 空串）：直接回落兜底渠道")
    void nullChannelFallsBack() {
        FakeNotifier log = new FakeNotifier("LOG", true);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log), "LOG");

        router.notify(ruleOf(null), record);
        router.notify(ruleOf("   "), record);

        assertEquals(2, log.calls);
    }

    @Test
    @DisplayName("★兜底渠道本身也不可用：不抛异常（预警主链路不能被通知失败拖垮）")
    void doesNotThrowWhenEvenFallbackUnavailable() {
        FakeNotifier log = new FakeNotifier("LOG", false);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log), "LOG");

        assertDoesNotThrow(() -> router.notify(ruleOf("SMS"), record));
        assertEquals(0, log.calls);
    }

    @Test
    @DisplayName("兜底渠道可配置：sender 指向 EMAIL 时，未接入渠道回落到 EMAIL")
    void fallbackChannelIsConfigurable() {
        FakeNotifier log = new FakeNotifier("LOG", true);
        FakeNotifier mail = new FakeNotifier("EMAIL", true);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log, mail), "email");

        router.notify(ruleOf("WEBHOOK"), record);

        assertEquals(1, mail.calls, "sender=email 时应回落到 EMAIL");
        assertEquals(0, log.calls);
    }

    @Test
    @DisplayName("兜底渠道为空/非法：退化为 LOG（默认兜底），保证始终有出口")
    void blankFallbackDegradesToLog() {
        FakeNotifier log = new FakeNotifier("LOG", true);
        AlertNotifierRouter blank = new AlertNotifierRouter(List.of(log), "   ");
        FakeNotifier log2 = new FakeNotifier("LOG", true);
        AlertNotifierRouter unknown = new AlertNotifierRouter(List.of(log2), "NOT_A_CHANNEL");

        blank.notify(ruleOf("SMS"), record);
        unknown.notify(ruleOf("SMS"), record);

        assertEquals(1, log.calls, "sender 为空时应回落到 LOG");
        assertEquals(1, log2.calls, "sender 指向不存在的渠道时应回落到 LOG");
    }

    @Test
    @DisplayName("channels()/usable() 如实反映已接入与可用状态")
    void channelsAndUsable() {
        FakeNotifier log = new FakeNotifier("LOG", true);
        FakeNotifier mail = new FakeNotifier("EMAIL", false);
        AlertNotifierRouter router = new AlertNotifierRouter(List.of(log, mail), "LOG");

        assertEquals(new ArrayList<>(List.of("EMAIL", "LOG")), router.channels(), "渠道码应升序");
        assertTrue(router.usable("log"), "LOG 可用");
        assertFalse(router.usable("EMAIL"), "EMAIL 已接入但未配置 → 不可用");
        assertFalse(router.usable("SMS"), "SMS 未接入");
        assertFalse(router.usable(null), "null 渠道不可用");
        assertEquals("LOG", router.fallbackChannel());
    }
}
