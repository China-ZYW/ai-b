package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 预警通知路由：按 {@link AlertRule#getNotifyChannel()} 选择 {@link AlertNotifier} 实现。
 *
 * <p><b>为什么需要它</b>：以前 {@code AlertEngineService} 直接注入<b>单个</b> {@link AlertNotifier}，
 * 只能有一个实现（{@code LOG}）——一旦新增 {@code EMAIL} 实现，Spring 就会因"候选 bean 不唯一"
 * 直接启动失败。改由本路由持有全部实现、按渠道码分发后，
 * <b>新增渠道 = 加一个 {@code @Service implements AlertNotifier}，路由与引擎都不用改</b>。
 *
 * <p><b>回落策略（关键：告警永远不能"静默消失"）</b>：
 * <ol>
 *   <li>规则渠道已接入且可用 → 用它；</li>
 *   <li>规则渠道<b>未接入</b>或<b>已接入但不可用</b>（如 EMAIL 没配 SMTP）→ 回落到<b>兜底渠道</b>
 *       （平台后台「通知配置」里的 fallbackChannel，未配置时用 yml 的 {@code aibi.notify.sender}），
 *       并打 WARN 说明原因；</li>
 *   <li>兜底渠道本身也不可用 → 再退到<b>内置 LOG</b>（无外部依赖、恒可用）作为最终安全网。
 *       全程<b>不抛异常</b>：少发一封通知，不能让预警主链路失败；此时 {@code alert_record} 仍已落库，数据不丢。</li>
 * </ol>
 *
 * <p>兜底渠道<b>每次发送现读</b>平台配置，故后台改完立即生效（"配置即生效"）。
 */
@Service
public class AlertNotifierRouter {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifierRouter.class);

    /** 渠道码 → 实现。 */
    private final Map<String, AlertNotifier> byChannel;
    /** yml 兜底值（平台配置未设时使用）。 */
    private final String ymlFallbackChannel;
    /** 平台级通知配置（提供运行时兜底渠道）。 */
    private final NotifyConfigService notifyConfig;

    /**
     * 向后兼容构造器：不接平台通知配置，{@link #fallbackChannel()} 直接用 yml 值。
     * 供单测与"不依赖平台配置"的装配场景使用。
     */
    public AlertNotifierRouter(List<AlertNotifier> notifiers, String fallbackChannel) {
        this(notifiers, null, fallbackChannel);
    }

    /**
     * 主构造器：<b>必须显式 {@code @Autowired}</b>——本类另有兼容构造器（见上），
     * 多个构造器而不标注，Spring 会去找无参构造器从而启动失败（本轮踩过一次）。
     */
    @Autowired
    public AlertNotifierRouter(List<AlertNotifier> notifiers,
                               NotifyConfigService notifyConfig,
                               @Value("${aibi.notify.sender:LOG}") String fallbackChannel) {
        this.notifyConfig = notifyConfig;
        Map<String, AlertNotifier> map = new HashMap<>();
        for (AlertNotifier n : notifiers) {
            String code = norm(n.channel());
            if (code == null) {
                log.warn("预警通知实现 {} 未声明渠道码，已忽略", n.getClass().getSimpleName());
                continue;
            }
            AlertNotifier prev = map.put(code, n);
            if (prev != null) {
                // 同名渠道只保留一个，但必须留痕——否则"我改了实现却没生效"会变成悬案
                log.warn("预警渠道码[{}]重复：{} 被 {} 覆盖", code,
                        prev.getClass().getSimpleName(), n.getClass().getSimpleName());
            }
        }
        this.byChannel = Map.copyOf(map);
        String fb = norm(fallbackChannel);
        this.ymlFallbackChannel = fb == null ? LogAlertNotifier.CHANNEL : fb;
        log.info("预警通知渠道就绪：已接入{} yml 兜底渠道[{}]（平台配置可覆盖）",
                map.keySet(), this.ymlFallbackChannel);
    }

    /** 归一化渠道码（去空白 + 大写）；空/空白返回 null。 */
    private static String norm(String channel) {
        if (channel == null) {
            return null;
        }
        String t = channel.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    /** 已接入的渠道码（升序）。供接口/前端展示"当前真正可选的渠道"。 */
    public List<String> channels() {
        return byChannel.keySet().stream().sorted().toList();
    }

    /** 取指定渠道的实现（未接入返回 {@code null}）；供「通知配置」页的连通性测试直达该渠道。 */
    public AlertNotifier notifier(String channel) {
        String code = norm(channel);
        return code == null ? null : byChannel.get(code);
    }

    /** 指定渠道是否已接入且当前可用。 */
    public boolean usable(String channel) {
        String code = norm(channel);
        if (code == null) {
            return false;
        }
        AlertNotifier n = byChannel.get(code);
        return n != null && n.available();
    }

    /** 兜底渠道码（平台配置优先，未配置回落 yml）。 */
    public String fallbackChannel() {
        String configured = notifyConfig == null
                ? ymlFallbackChannel
                : notifyConfig.fallbackChannel(ymlFallbackChannel);
        return norm(configured);
    }

    /**
     * 按规则渠道投递通知（见类注释的回落策略）。
     *
     * @throws AlertNotifyException 目标渠道真的尝试投递并失败时，由实现抛出、本方法原样向上传递
     */
    public void notify(AlertRule rule, AlertRecord record) {
        String want = norm(rule.getNotifyChannel());
        AlertNotifier target = want == null ? null : byChannel.get(want);
        boolean knownButUnavailable = target != null && !target.available();
        if (knownButUnavailable) {
            target = null;
        }
        String fbChannel = fallbackChannel();
        if (target == null) {
            String reason = knownButUnavailable
                    ? "已接入但当前不可用（如未配置 SMTP / webhook / 短信密钥）"
                    : "尚未接入";
            target = pickFallback(fbChannel);
            if (target == null) {
                log.error("预警通知无可用渠道，本次仅落库未外发 rule={} 期望渠道[{}] 兜底渠道[{}] 已接入{}",
                        rule.getId(), rule.getNotifyChannel(), fbChannel, byChannel.keySet());
                return;
            }
            log.warn("预警渠道[{}]{}，已回落兜底渠道[{}] rule={}（已接入{}）",
                    rule.getNotifyChannel(), reason, target.channel(), rule.getId(), byChannel.keySet());
        }
        target.notify(rule, record);
    }

    /**
     * 选兜底渠道：优先平台配置/yml 指定的；若它未接入或不可用，再退到<b>内置 LOG</b>。
     *
     * <p>这层"退无可退再退 LOG"是刻意的安全网：兜底渠道可能被配成尚未就绪的渠道
     * （如改成 {@code EMAIL} 但没配 SMTP），若就此放弃，告警会连一条日志都没有——与
     * "告警绝不能静默消失"的目标相悖。LOG 无外部依赖、恒可用，是最可靠的最后一站。
     *
     * @return 可用的兜底渠道；连 LOG 都不可用（理论上不会发生）时返回 {@code null}
     */
    private AlertNotifier pickFallback(String fbChannel) {
        AlertNotifier fb = byChannel.get(fbChannel);
        if (fb != null && fb.available()) {
            return fb;
        }
        AlertNotifier builtin = byChannel.get(LogAlertNotifier.CHANNEL);
        if (builtin != null && builtin != fb && builtin.available()) {
            return builtin;
        }
        return null;
    }
}
