package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.springframework.stereotype.Service;

/**
 * 企业微信群机器人通知渠道（{@code WECOM}）。
 *
 * <p>报文：{@code {"msgtype":"text","text":{"content":"..."}}}；成功判定 {@code errcode=0}。
 */
@Service
public class WeComAlertNotifier extends AbstractWebhookNotifier {

    public static final String CHANNEL = "WECOM";

    public WeComAlertNotifier(NotifyConfigService notifyConfig) {
        super(notifyConfig);
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    protected String buildBody(AlertRule rule, AlertRecord record) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\""
                + jsonEscape(textOf(rule, record)) + "\"}}";
    }

    @Override
    protected void checkBusiness(String respBody) {
        String compact = respBody == null ? "" : respBody.replaceAll("\\s+", "");
        if (compact.contains("\"errcode\":0")) {
            return;
        }
        if (compact.contains("\"errcode\"")) {
            throw new AlertNotifyException("企业微信返回业务错误：" + brief(respBody));
        }
    }
}
