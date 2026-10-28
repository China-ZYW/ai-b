package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.springframework.stereotype.Service;

/**
 * 飞书自定义机器人通知渠道（{@code FEISHU}）。
 *
 * <p>报文：{@code {"msg_type":"text","content":{"text":"..."}}}；成功判定 {@code code=0}
 * （老版网关也返回 {@code StatusCode=0}，两种都认）。
 */
@Service
public class FeishuAlertNotifier extends AbstractWebhookNotifier {

    public static final String CHANNEL = "FEISHU";

    public FeishuAlertNotifier(NotifyConfigService notifyConfig) {
        super(notifyConfig);
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    protected String buildBody(AlertRule rule, AlertRecord record) {
        return "{\"msg_type\":\"text\",\"content\":{\"text\":\""
                + jsonEscape(textOf(rule, record)) + "\"}}";
    }

    @Override
    protected void checkBusiness(String respBody) {
        String compact = respBody == null ? "" : respBody.replaceAll("\\s+", "");
        if (compact.contains("\"code\":0") || compact.contains("\"StatusCode\":0")) {
            return;
        }
        if (compact.contains("\"code\"") || compact.contains("StatusCode")) {
            throw new AlertNotifyException("飞书返回业务错误：" + brief(respBody));
        }
    }
}
