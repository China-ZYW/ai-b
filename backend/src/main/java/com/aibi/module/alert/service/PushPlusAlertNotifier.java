package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * PushPlus 微信推送通知渠道（{@code PUSHPLUS}）。
 *
 * <p>与其它 webhook 渠道不同，PushPlus 只要求配一个 <b>token</b>（URL 是固定的官方网关），
 * 故这里覆写 {@link #available()} 与 {@link #webhookOf} 而不看 {@code webhook} 字段。
 *
 * <p>报文：{@code {"token":"...","title":"...","content":"...","template":"txt"}}；成功判定 {@code code=200}。
 */
@Service
public class PushPlusAlertNotifier extends AbstractWebhookNotifier {

    public static final String CHANNEL = "PUSHPLUS";

    /** PushPlus 官方网关（配置只需 token）。 */
    private static final String ENDPOINT = "https://www.pushplus.plus/send";

    public PushPlusAlertNotifier(NotifyConfigService notifyConfig) {
        super(notifyConfig);
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean available() {
        Map<String, Object> cfg = cfg();
        return NotifyConfigService.boolVal(cfg, "enabled", false)
                && !NotifyConfigService.str(cfg, "token", "").isBlank();
    }

    @Override
    protected String webhookOf(Map<String, Object> cfg) {
        return ENDPOINT;
    }

    @Override
    protected String buildBody(AlertRule rule, AlertRecord record) {
        String token = NotifyConfigService.str(cfg(), "token", "");
        return "{\"token\":\"" + jsonEscape(token)
                + "\",\"title\":\"" + jsonEscape(titleOf(rule, record))
                + "\",\"content\":\"" + jsonEscape(textOf(rule, record))
                + "\",\"template\":\"txt\"}";
    }

    @Override
    protected void checkBusiness(String respBody) {
        String compact = respBody == null ? "" : respBody.replaceAll("\\s+", "");
        if (compact.contains("\"code\":200")) {
            return;
        }
        if (compact.contains("\"code\"")) {
            throw new AlertNotifyException("PushPlus 返回业务错误：" + brief(respBody));
        }
    }
}
