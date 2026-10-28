package com.aibi.module.alert.service;

import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import com.aibi.module.message.entity.NotifyMessage;
import com.aibi.module.message.mapper.NotifyMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 站内信渠道（{@code INAPP}）：预警命中时把通知写进 {@code notify_message}，按规则
 * {@code notify_target} 指定的用户 id 列表<b>逐一写一条</b>（每人一条）。
 *
 * <p><b>与"租户全员广播"的边界</b>：站内信只发给规则明确指定的接收人（{@code notify_target}
 * 存 {@code app_user.id}，逗号/分号分隔）。本渠道不做"全组织所有人各收一条"——那会随成员数膨胀、
 * 且多数成员并不关心这条指标。若要群发，运营应在规则里显式列出目标用户 id。
 *
 * <p><b>可用性</b>：写的是本平台自己的库，无外部依赖，故 {@link #available()} 恒为 true，
 * 不会被路由回落，也不会被当成"未配置"而静默丢弃。
 *
 * <p><b>失败语义（异常契约）</b>：{@code notify_target} 为空（没指定接收人）→ 抛
 * {@link AlertNotifyException}，让 {@code AlertEngineService} 把 {@code alert_record.notified}
 * 记成 false（"没发出去"要看得见）。解析出的非法 id 片段则跳过并告警，不连累其余合法接收人。
 *
 * <p><b>租户隔离</b>：{@code notify_message} 带 {@code tenant_id}，这里显式 set 由规则归属的租户，
 * 同时租户拦截器也会按当前上下文注入，双保险。
 */
@Service
public class InAppAlertNotifier implements AlertNotifier {

    /** 渠道码。 */
    public static final String CHANNEL = "INAPP";

    private static final Logger log = LoggerFactory.getLogger(InAppAlertNotifier.class);

    private final NotifyMessageMapper messageMapper;

    public InAppAlertNotifier(NotifyMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void notify(AlertRule rule, AlertRecord record) {
        List<Long> recipients = parseRecipients(rule.getNotifyTarget());
        if (recipients.isEmpty()) {
            throw new AlertNotifyException("站内信渠道未指定接收人（notify_target 为空）");
        }
        Long tenantId = rule.getTenantId() != null ? rule.getTenantId() : record.getTenantId();
        int written = 0;
        for (Long uid : recipients) {
            NotifyMessage m = new NotifyMessage();
            m.setTenantId(tenantId);
            m.setUid(uid);
            m.setRuleId(rule.getId());
            m.setRecordId(record.getId());
            m.setSeverity(record.getSeverity());
            m.setTitle(titleOf(rule, record));
            m.setContent(record.getMessage());
            m.setIsRead(false);
            m.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(m);
            written++;
        }
        log.info("站内信已写入 rule={}({}) 接收人={} 条", rule.getId(), rule.getName(), recipients.size());
    }

    /**
     * 默认旁路投递（站内信"默认触发"）：无论规则主渠道是否为 {@code INAPP}，引擎在触发时都会
     * 额外调用本方法，保证<b>每条预警都送达相关人的站内信收件箱</b>。
     *
     * <p><b>接收人口径</b>：{@code notify_target} 指定者 ∪ 规则创建人（{@code createdBy}）。
     * 前者是规则显式指定的关注人，后者是"建了这条规则的人理应为其负责/知情"——两者并集，
     * 既尊重了「规则指定用户」的设计，又保证"默认触发"人人有份、不会因漏填而静默丢失。
     *
     * <p><b>best-effort 语义</b>：与主渠道 {@link #notify} 不同，本方法<b>永不抛异常</b>——
     * 两者皆空（既没指定接收人、规则也无创建人，极罕见）时静默跳过；插入异常只记日志。
     * 它不影响 {@code alert_record.notified}（那个标记只反映主渠道投递结果），也不反噬告警主链路。
     *
     * @return 实际写入的站内信条数（0 = 无接收人，未写）
     */
    public int notifyDefault(AlertRule rule, AlertRecord record) {
        Set<Long> recipients = new LinkedHashSet<>(parseRecipients(rule.getNotifyTarget()));
        if (rule.getCreatedBy() != null) {
            recipients.add(rule.getCreatedBy());
        }
        if (recipients.isEmpty()) {
            log.debug("站内信默认旁路跳过（无接收人）rule={}", rule.getId());
            return 0;
        }
        Long tenantId = rule.getTenantId() != null ? rule.getTenantId() : record.getTenantId();
        int written = 0;
        for (Long uid : recipients) {
            NotifyMessage m = new NotifyMessage();
            m.setTenantId(tenantId);
            m.setUid(uid);
            m.setRuleId(rule.getId());
            m.setRecordId(record.getId());
            m.setSeverity(record.getSeverity());
            m.setTitle(titleOf(rule, record));
            m.setContent(record.getMessage());
            m.setIsRead(false);
            m.setCreatedAt(LocalDateTime.now());
            try {
                messageMapper.insert(m);
                written++;
            } catch (Exception e) {
                log.warn("站内信默认旁路写入失败 rule={} uid={}: {}", rule.getId(), uid, e.getMessage());
            }
        }
        if (written > 0) {
            log.info("站内信默认旁路已写入 rule={}({}) 接收人={} 条", rule.getId(), rule.getName(), written);
        }
        return written;
    }

    /** 标题带上严重级别，便于收件箱一眼分流（与邮件渠道口径一致）。 */
    private static String titleOf(AlertRule rule, AlertRecord record) {
        return "[aibi 预警][" + record.getSeverity() + "] " + rule.getName();
    }

    /**
     * 解析接收人：{@code notify_target} 存用户 id 列表（逗号/中文逗号/分号/空白分隔），逐段解析为 Long。
     *
     * <p>非法的片段（无法解析为数字）跳过并告警——宁可少发一个明显非法的地址，也要保住其余合法接收人，
     * 与邮件渠道"形似邮箱过滤"的取舍一致。
     */
    List<Long> parseRecipients(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (String raw : target.split("[,，;；\\s]+")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("忽略非法的站内信接收人 id: {}", s);
            }
        }
        return new ArrayList<>(out);
    }
}
