package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Webhook 类通知渠道的公共基类（钉钉 / 企微 / 飞书 / PushPlus 都只是"POST 一段 JSON"）。
 *
 * <p><b>配置在发送时现读</b>（{@link NotifyConfigService#channel(String)}），故平台后台改完配置<b>立即生效</b>，
 * 不像旧的邮件实现那样在构造期把参数固化成字段。
 *
 * <p><b>异常契约</b>（与 {@link AlertNotifier} 一致）：未启用/未配 webhook → {@link #available()} 返回
 * {@code false}（让路由回落兜底渠道）；真的发出去了但 HTTP 非 200 或渠道业务码非成功 → 抛
 * {@link AlertNotifyException}，由引擎把 {@code alert_record.notified} 记成 false。
 *
 * <p><b>超时是硬要求</b>：推送挂在预警扫描线程上，必须限时（默认 8s），否则一个不可达的 webhook 会拖住整轮扫描。
 */
public abstract class AbstractWebhookNotifier implements AlertNotifier {

    protected static final Logger log = LoggerFactory.getLogger(AbstractWebhookNotifier.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    protected final NotifyConfigService notifyConfig;

    protected AbstractWebhookNotifier(NotifyConfigService notifyConfig) {
        this.notifyConfig = notifyConfig;
    }

    /** 本渠道的配置字段表。 */
    protected Map<String, Object> cfg() {
        return notifyConfig.channel(channel());
    }

    /** webhook 地址（子类可覆写，如钉钉需拼接签名参数）。 */
    protected String webhookOf(Map<String, Object> cfg) {
        return NotifyConfigService.str(cfg, "webhook", "");
    }

    /** 请求体（各渠道报文格式不同）。 */
    protected abstract String buildBody(AlertRule rule, AlertRecord record);

    /** 业务码校验：渠道返回 HTTP 200 但业务失败时抛异常（默认无）。 */
    protected void checkBusiness(String respBody) {
        // 默认不校验
    }

    @Override
    public boolean available() {
        Map<String, Object> cfg = cfg();
        return NotifyConfigService.boolVal(cfg, "enabled", false) && !webhookOf(cfg).isBlank();
    }

    @Override
    public void notify(AlertRule rule, AlertRecord record) {
        if (!available()) {
            throw new AlertNotifyException(channel() + " 渠道不可用（未启用或未配置 webhook）");
        }
        Map<String, Object> cfg = cfg();
        int timeoutMs = NotifyConfigService.intVal(cfg, "timeoutMs", 8000);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookOf(cfg)))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(rule, record), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new AlertNotifyException(channel() + " 推送失败 HTTP " + resp.statusCode() + "：" + brief(resp.body()));
            }
            checkBusiness(resp.body());
            log.info("预警已推送 [{}] rule={}({})", channel(), rule.getId(), rule.getName());
        } catch (AlertNotifyException e) {
            throw e;
        } catch (Exception e) {
            throw new AlertNotifyException(channel() + " 推送失败: " + e.getMessage(), e);
        }
    }

    /** 通知正文（各渠道共用同一份可读文本）。 */
    protected static String textOf(AlertRule rule, AlertRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(record.getSeverity()).append("】").append(rule.getName());
        if (rule.getDescription() != null && !rule.getDescription().isBlank()) {
            sb.append('\n').append(rule.getDescription());
        }
        sb.append("\n监控指标：").append(record.getMetricKey());
        sb.append("\n触发条件：当前值 ").append(record.getValue())
                .append(' ').append(record.getOperator()).append(" 阈值 ").append(rule.getThreshold());
        sb.append("\n触发时间：").append(record.getCreatedAt());
        if (record.getMessage() != null && !record.getMessage().isBlank()) {
            sb.append('\n').append(record.getMessage());
        }
        return sb.toString();
    }

    /** 标题（PushPlus/飞书等用得上）。 */
    protected static String titleOf(AlertRule rule, AlertRecord record) {
        return "[aibi 预警][" + record.getSeverity() + "] " + rule.getName();
    }

    protected static String brief(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }

    /** JSON 字符串转义（正文里可能有引号/换行）。 */
    protected static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
