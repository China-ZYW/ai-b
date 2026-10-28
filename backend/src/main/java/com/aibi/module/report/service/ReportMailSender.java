package com.aibi.module.report.service;

import com.aibi.module.alert.service.NotifyConfigService;
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
 * 报表邮件发送器（T12-1）：把渲染好的报表正文发给 {@code email_targets} 收件人。
 *
 * <p><b>为什么不复用 {@code EmailAlertNotifier}</b>：它绑死 {@code AlertRule/AlertRecord} 接口契约
 * （设计稿拍板 P3——报表不是告警，不伪造告警对象过通知路由）。本类只借它的<b>配置口径</b>：
 * 同读平台「通知配置」的 SMTP 段（{@link NotifyConfigService#channel}），每次发送<b>现读现建</b>
 * 发送器（后台改 SMTP 立即生效）、同款 8s 超时硬限（发信挂在调度扫描线程上，必须限时）。
 *
 * <p><b>失败语义</b>：{@link #available()} 为 false（未启用/缺 host/缺发件人）由调用侧
 * {@code ReportService} 提前判定并降级为「仅站内」注记，本类不会被无配置调用；真正投递异常
 * 抛 {@link ReportMailException}，由调用侧决定整单成败。
 *
 * <p><b>收件人隐私红线</b>：日志只打收件人<b>条数</b>，绝不打印完整邮箱清单。
 */
@Service
public class ReportMailSender {

    private static final Logger log = LoggerFactory.getLogger(ReportMailSender.class);

    /** 与告警邮件同源的渠道配置段（平台通知配置的 EMAIL 段）。 */
    private static final String CHANNEL = "EMAIL";

    private final NotifyConfigService notifyConfig;

    public ReportMailSender(NotifyConfigService notifyConfig) {
        this.notifyConfig = notifyConfig;
    }

    /** 只有「开关打开 + 配了 host + 有发件人」才算可用；否则调用侧走「仅站内」降级。 */
    public boolean available() {
        Map<String, Object> cfg = notifyConfig.channel(CHANNEL);
        return NotifyConfigService.boolVal(cfg, "enabled", false)
                && !NotifyConfigService.str(cfg, "host", "").isEmpty()
                && !fromOf(cfg).isEmpty();
    }

    /**
     * 发送一封纯文本报表邮件。
     *
     * @param targetsRaw 收件人原始串（逗号/分号/空白混用，宽容解析同告警口径）
     * @throws ReportMailException 无有效收件人或 SMTP 投递失败
     */
    public void send(String targetsRaw, String subject, String body) {
        Map<String, Object> cfg = notifyConfig.channel(CHANNEL);
        List<String> recipients = parseRecipients(targetsRaw);
        if (recipients.isEmpty()) {
            throw new ReportMailException("报表无有效邮件收件人");
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromOf(cfg));
            msg.setTo(recipients.toArray(new String[0]));
            msg.setSubject(subject);
            msg.setText(body);
            sender(cfg).send(msg);
            // 隐私红线：只打条数，不打地址清单
            log.info("报表邮件已发送 收件人={} 条 主题={}", recipients.size(), subject);
        } catch (Exception e) {
            throw new ReportMailException("SMTP 发送失败: " + e.getMessage(), e);
        }
    }

    /** 发件人：未单独配置时回落登录账号（与告警邮件同口径）。 */
    private static String fromOf(Map<String, Object> cfg) {
        String from = NotifyConfigService.str(cfg, "from", "");
        return from.isEmpty() ? NotifyConfigService.str(cfg, "username", "") : from;
    }

    /** 按当前配置现建发送器（配置改动即时生效；报表频率低，构造开销可忽略）。 */
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
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));
        s.setJavaMailProperties(props);
        return s;
    }

    /**
     * 宽容解析收件人：英文/中文逗号、分号、空白混用；去重保序；只留形似邮箱的片段。
     *
     * <p>取舍同告警邮件渠道：一个错别字会让整封邮件被 SMTP 拒绝并连累合法收件人，
     * 宁可跳过明显非法的地址也要保住其余人。
     */
    static List<String> parseRecipients(String target) {
        if (target == null || target.isBlank()) {
            return new ArrayList<>();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : target.split("[,，;；\\s]+")) {
            String addr = raw.trim();
            if (addr.isEmpty()) {
                continue;
            }
            if (addr.indexOf('@') <= 0 || addr.endsWith("@")) {
                log.warn("忽略疑似非法的报表邮件收件人（已隐去）");
                continue;
            }
            out.add(addr);
        }
        return new ArrayList<>(out);
    }

    /** 报表邮件投递异常（由 {@code ReportService} 捕获决定整单成败）。 */
    public static class ReportMailException extends RuntimeException {
        public ReportMailException(String message) {
            super(message);
        }

        public ReportMailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
