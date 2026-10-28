package com.aibi.module.admin.controller;

import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import com.aibi.module.alert.service.AlertNotifier;
import com.aibi.module.alert.service.AlertNotifierRouter;
import com.aibi.module.alert.service.NotifyConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 平台「通知配置」接口（{@code /api/admin/notify-config}，SUPER_ADMIN）：维护 SMTP / 短信 / 钉钉 / 企微 /
 * 飞书 / PushPlus 六个渠道的参数与兜底渠道，并可就地做连通性测试。
 *
 * <p><b>密钥不回显</b>：读取时敏感字段（SMTP 密码、短信 AccessKeySecret、webhook、PushPlus token）置空，
 * 另附 {@code secretsSet} 标明"是否已配置"；写入时<b>留空 = 保持原值</b>（不是清空）。
 *
 * <p><b>配置即生效</b>：保存只写 {@code sys_config}（无缓存），各通知实现都在<b>发送时</b>现读配置，
 * 故改完无需重启、无需重发规则。
 *
 * <p>权限点复用平台配置域 {@code admin:config:read/write}（通知配置本质就是平台级配置），
 * 不新增权限码以免与 {@code RbacSeeder} 权限矩阵脱节。
 */
@RestController
@RequestMapping("/api/admin/notify-config")
public class AdminNotifyConfigController {

    private final NotifyConfigService notifyConfig;
    private final AlertNotifierRouter notifierRouter;

    public AdminNotifyConfigController(NotifyConfigService notifyConfig, AlertNotifierRouter notifierRouter) {
        this.notifyConfig = notifyConfig;
        this.notifierRouter = notifierRouter;
    }

    /** 读取通知配置（密钥不回显，只给"已配置"标记）。 */
    @GetMapping
    @RequiresPermission(value = "admin:config:read", platformOnly = true)
    public R<Map<String, Object>> get() {
        return R.ok(notifyConfig.masked());
    }

    /** 保存通知配置（密钥留空 = 保持不变）；保存即生效。 */
    @PutMapping
    @RequiresPermission(value = "admin:config:write", platformOnly = true)
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> form) {
        notifyConfig.save(form);
        return R.ok(notifyConfig.masked());
    }

    /** 渠道清单与当前可用性（页面据此标注「可用 / 未配置」）。 */
    @GetMapping("/channels")
    @RequiresPermission(value = "admin:config:read", platformOnly = true)
    public R<Map<String, Object>> channels() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String code : notifierRouter.channels()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", code);
            m.put("usable", notifierRouter.usable(code));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", list);
        out.put("fallbackChannel", notifierRouter.fallbackChannel());
        return R.ok(out);
    }

    /**
     * 连通性测试：用<b>该渠道本身</b>发一条测试通知。
     *
     * <p>刻意<b>不走兜底回落</b>——测试的意义就是看到目标渠道自己的结果（回落成功会掩盖"其实没配好"）。
     * 发送失败不抛 HTTP 错误，而是返回 {@code {ok:false, message}}，让页面把失败原因原样展示出来便于排查。
     *
     * @param channel 渠道码（EMAIL / SMS / DINGTALK / WECOM / FEISHU / PUSHPLUS / LOG）
     * @param target  渠道目标：EMAIL=收件人、SMS=手机号；webhook 类与 PushPlus 用配置里的地址，可空
     */
    @PostMapping("/test/{channel}")
    @RequiresPermission(value = "admin:config:write", platformOnly = true)
    public R<Map<String, Object>> test(@PathVariable String channel,
                                       @RequestParam(required = false) String target) {
        String code = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        AlertNotifier notifier = notifierRouter.notifier(code);
        if (notifier == null) {
            return R.fail(ErrorCode.PARAM_ERROR, "渠道「" + code + "」尚未接入");
        }

        AlertRule rule = new AlertRule();
        rule.setId(-1L);
        rule.setName("通知配置连通性测试");
        rule.setDescription("由平台后台「通知配置」发起，收到即表示该渠道可用。");
        rule.setMetricKey("test.metric");
        rule.setOperator(">");
        rule.setThreshold(BigDecimal.ONE);
        rule.setSeverity("TEST");
        rule.setNotifyChannel(code);
        rule.setNotifyTarget(target == null ? "" : target.trim());

        AlertRecord record = new AlertRecord();
        record.setRuleId(-1L);
        record.setRuleName("通知配置连通性测试");
        record.setMetricKey("test.metric");
        record.setValue(BigDecimal.ONE);
        record.setOperator(">");
        record.setThreshold(BigDecimal.ONE);
        record.setSeverity("TEST");
        record.setMessage("这是一条测试通知，收到即表示该渠道配置可用。");
        record.setCreatedAt(LocalDateTime.now());
        record.setTenantId(TenantContext.tenantId());

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            notifier.notify(rule, record);
            out.put("ok", true);
            out.put("message", "已发送，请到该渠道查收");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage() == null ? "发送失败" : e.getMessage());
        }
        return R.ok(out);
    }
}
