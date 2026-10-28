package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 钉钉群机器人通知渠道（{@code DINGTALK}）。
 *
 * <p>报文：{@code {"msgtype":"text","text":{"content":"..."}}}；成功判定 {@code errcode=0}。
 *
 * <p><b>加签</b>：机器人开启"加签"时，需在 webhook 后拼 {@code &timestamp=..&sign=..}，
 * sign = Base64(HMAC-SHA256({@code timestamp + "\n" + secret}, secret))。
 * 配置里的 {@code secret} 留空表示未开启加签。
 */
@Service
public class DingTalkAlertNotifier extends AbstractWebhookNotifier {

    public static final String CHANNEL = "DINGTALK";

    public DingTalkAlertNotifier(NotifyConfigService notifyConfig) {
        super(notifyConfig);
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    /** 开启加签时拼接 timestamp 与 sign。 */
    @Override
    protected String webhookOf(Map<String, Object> cfg) {
        String url = NotifyConfigService.str(cfg, "webhook", "");
        String secret = NotifyConfigService.str(cfg, "secret", "");
        if (url.isBlank() || secret.isBlank()) {
            return url;
        }
        try {
            String ts = String.valueOf(System.currentTimeMillis());
            String stringToSign = ts + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            String sep = url.contains("?") ? "&" : "?";
            return url + sep + "timestamp=" + ts + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("钉钉加签失败，将按未加签发送：{}", e.getMessage());
            return url;
        }
    }

    @Override
    protected String buildBody(AlertRule rule, AlertRecord record) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\""
                + jsonEscape(textOf(rule, record)) + "\"}}";
    }

    @Override
    protected void checkBusiness(String respBody) {
        // 钉钉成功返回 {"errcode":0,...}；加签错误/被限流等会带非 0 业务码
        String compact = respBody == null ? "" : respBody.replaceAll("\\s+", "");
        if (compact.contains("\"errcode\":0")) {
            return;
        }
        if (compact.contains("\"errcode\"")) {
            throw new AlertNotifyException("钉钉返回业务错误：" + brief(respBody));
        }
    }
}
