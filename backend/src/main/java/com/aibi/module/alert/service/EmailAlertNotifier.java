package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 邮件通知渠道（{@code EMAIL}）：规则命中时给 {@code alert_rule.notify_target} 里的收件人发一封纯文本告警邮件。
 *
 * <p><b>配置来源</b>：平台后台「通知配置」里的 SMTP 段（{@link NotifyConfigService}）。与早期版本不同，
 * 这里<b>不再</b>在构造期用 {@code @Value} 固化参数——每次发送<b>现读配置</b>并构造
 * {@link JavaMailSenderImpl}，因此后台改完 SMTP <b>立即生效、无需重启</b>；密钥也不再经环境变量/回显泄露。
 *
 * <p><b>为什么不用 Spring Boot 的自动配置 MailSender</b>：本渠道的开关与参数属于"运行期业务配置"，
 * 且要在未配置时优雅降级（而不是让应用启动失败）。这里显式构造发送器，故 yml 中<b>不需要</b>也不会读取
 * {@code spring.mail.*}（历史 {@code aibi.notify.mail.*} 仅作首次种子/兼容）。
 *
 * <p><b>超时是硬要求</b>：发信挂在预警扫描线程上，必须限时（默认 8s），否则一个不可达的 SMTP 会拖住整轮扫描。
 *
 * <p><b>失败语义</b>：收件人为空或 SMTP 投递失败 → 抛 {@link AlertNotifyException}，由
 * {@code AlertEngineService} 把 {@code alert_record.notified} 记成 false（"没发出去"要看得见，而不是假装已通知）。
 */
@Service
public class EmailAlertNotifier implements AlertNotifier {

    /** 渠道码。 */
    public static final String CHANNEL = "EMAIL";

    private static final Logger log = LoggerFactory.getLogger(EmailAlertNotifier.class);

    private final NotifyConfigService notifyConfig;

    public EmailAlertNotifier(NotifyConfigService notifyConfig) {
        this.notifyConfig = notifyConfig;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    /** 只有"开关打开 + 配了 host + 有发件人"才算可用；否则路由会回落兜底渠道。 */
    @Override
    public boolean available() {
        Map<String, Object> cfg = cfg();
        return NotifyConfigService.boolVal(cfg, "enabled", false)
                && !NotifyConfigService.str(cfg, "host", "").isEmpty()
                && !fromOf(cfg).isEmpty();
    }

    @Override
    public void notify(AlertRule rule, AlertRecord record) {
        if (!available()) {
            // 路由正常情况下不会调到此处；真被调用说明配置被中途改动，明确报错而不是悄悄吞掉
            throw new AlertNotifyException("邮件渠道不可用（未启用或缺少 host/from）");
        }
        Map<String, Object> cfg = cfg();
        List<String> recipients = parseRecipients(rule.getNotifyTarget());
        if (recipients.isEmpty()) {
            throw new AlertNotifyException("规则未配置收件人（notify_target 为空）");
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromOf(cfg));
            msg.setTo(recipients.toArray(new String[0]));
            msg.setSubject(subjectOf(rule, record));
            msg.setText(bodyOf(rule, record));
            sender(cfg).send(msg);
            log.info("预警邮件已发送 rule={}({}) 收件人={} 严重级别={}",
                    rule.getId(), rule.getName(), recipients, record.getSeverity());
        } catch (Exception e) {
            throw new AlertNotifyException("SMTP 发送失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> cfg() {
        return notifyConfig.channel(CHANNEL);
    }

    /** 发件人：未单独配置时回落登录账号。 */
    private static String fromOf(Map<String, Object> cfg) {
        String from = NotifyConfigService.str(cfg, "from", "");
        return from.isEmpty() ? NotifyConfigService.str(cfg, "username", "") : from;
    }

    /** 按当前配置构造发送器（每次发送现建：配置改动立即生效，且 SMTP 会话本身很轻、告警频率低）。 */
    private JavaMailSenderImpl sender(Map<String, Object> cfg) {
        int timeoutMs = NotifyConfigService.intVal(cfg, "timeoutMs", 8000);
        String username = NotifyConfigService.str(cfg, "username", "");
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(NotifyConfigService.str(cfg, "host", ""));
        s.setPort(NotifyConfigService.intVal(cfg, "port", 465));
        s.setUsername(username);
        s.setPassword(NotifyConfigService.str(cfg, "password", ""));
        s.setDefaultEncoding("UTF-8");

        Properties props = new Properties();
        props.put("mail.smtp.auth", username.isEmpty() ? "false" : "true");
        if (NotifyConfigService.boolVal(cfg, "sslEnabled", true)) {
            // 465 常用：隐式 TLS。587 场景请把 sslEnabled 设为 false 并让服务端走 STARTTLS
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));
        s.setJavaMailProperties(props);
        return s;
    }

    /**
     * 解析收件人：支持英文/中文逗号、分号、空白混用；去重并保序；只保留形似邮箱的片段。
     *
     * <p>之所以做"形似邮箱"过滤而不是原样透传：一个错别字（如漏了 @）会让整封邮件被 SMTP 拒绝，
     * 连带把合法的收件人也拖下水；这里宁可少发一个明显非法的地址，也要保住其余收件人。
     */
    List<String> parseRecipients(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : target.split("[,，;；\\s]+")) {
            String addr = raw.trim();
            if (addr.isEmpty()) {
                continue;
            }
            if (addr.indexOf('@') <= 0 || addr.endsWith("@")) {
                log.warn("忽略疑似非法的邮件收件人: {}", addr);
                continue;
            }
            out.add(addr);
        }
        return new ArrayList<>(out);
    }

    /** 主题带上严重级别，便于收件箱里一眼分流。 */
    private String subjectOf(AlertRule rule, AlertRecord record) {
        return "[aibi 预警][" + record.getSeverity() + "] " + rule.getName();
    }

    /** 纯文本正文：只放"规则 + 触发值 + 结论"，与站内告警页展示的信息保持一致。 */
    private String bodyOf(AlertRule rule, AlertRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("规则：").append(rule.getName()).append('\n');
        if (rule.getDescription() != null && !rule.getDescription().isBlank()) {
            sb.append("说明：").append(rule.getDescription()).append('\n');
        }
        sb.append("严重级别：").append(record.getSeverity()).append('\n');
        sb.append("监控指标：").append(record.getMetricKey()).append('\n');
        sb.append("触发条件：当前值 ").append(record.getValue())
                .append(" ").append(record.getOperator()).append(" 阈值 ").append(rule.getThreshold()).append('\n');
        sb.append("触发时间：").append(record.getCreatedAt()).append('\n');
        sb.append("组织（租户）：").append(record.getTenantId()).append('\n');
        if (record.getMessage() != null && !record.getMessage().isBlank()) {
            sb.append('\n').append(record.getMessage()).append('\n');
        }
        sb.append("\n—— 本邮件由 aibi 智能预警自动发送。可在「组织设置 · 预警」中调整规则或改回站内日志渠道。");
        return sb.toString();
    }
}
