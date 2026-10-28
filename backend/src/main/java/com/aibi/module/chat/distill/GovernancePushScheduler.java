package com.aibi.module.chat.distill;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.message.entity.NotifyMessage;
import com.aibi.module.message.mapper.NotifyMessageMapper;
import com.aibi.module.rbac.mapper.UserRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 待治理问题定时推送（T11-1 二期 B）。
 *
 * <p>逐租户扫描治理清单，把新增且未推送的 OPEN 簇聚合为一条站内信送达管理角色（OWNER/ADMIN/DATA_STEWARD）。
 * 镜像 {@code AlertEngineService.sweepAll()} 的逐租户设上下文范式。
 *
 * <p><b>默认关闭</b>：{@code METRIC / issue-push = false}，需在配置页手动开启。
 * <p><b>去重</b>：台账 {@code notified_at} 保证同一未处置问题在一个周期内只推一次（D2 决策：不做周期性再提醒）。
 */
@Service
public class GovernancePushScheduler {

    private static final Logger log = LoggerFactory.getLogger(GovernancePushScheduler.class);

    /** 管理角色码，恰为 tuning:panel:read (YYYNNN) 受众。 */
    private static final List<String> MANAGER_ROLES = List.of("OWNER", "ADMIN", "DATA_STEWARD");

    private final IssueDistiller issueDistiller;
    private final GovernanceIssueLedgerService ledger;
    private final GovernanceLedgerMapper ledgerMapper;
    private final AibiConfig aibiConfig;
    private final UserRoleMapper userRoleMapper;
    private final NotifyMessageMapper messageMapper;

    public GovernancePushScheduler(IssueDistiller issueDistiller,
                                   GovernanceIssueLedgerService ledger,
                                   GovernanceLedgerMapper ledgerMapper,
                                   AibiConfig aibiConfig,
                                   UserRoleMapper userRoleMapper,
                                   NotifyMessageMapper messageMapper) {
        this.issueDistiller = issueDistiller;
        this.ledger = ledger;
        this.ledgerMapper = ledgerMapper;
        this.aibiConfig = aibiConfig;
        this.userRoleMapper = userRoleMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 每日定时扫描（cron 可配 {@code aibi.governance.push-cron}，默认每天 09:00）。
     */
    @Scheduled(cron = "${aibi.governance.push-cron:0 0 9 * * *}")
    public void sweep() {
        List<Long> tenants;
        try {
            tenants = ledgerMapper.selectTenantsWithData();
        } catch (Exception e) {
            log.warn("治理推送租户扫描失败: {}", e.getMessage());
            return;
        }
        for (Long tid : tenants) {
            if (tid == null) {
                continue;
            }
            AccessContext previous = AccessContextHolder.get();
            AccessContextHolder.set(AccessContext.builder().tenantId(tid).build());
            try {
                if (!aibiConfig.issuePushEnabled()) {
                    continue;
                }
                pushForTenant(tid);
            } catch (Exception e) {
                log.warn("治理推送 tenant={} 失败: {}", tid, e.getMessage());
            } finally {
                if (previous == null) {
                    AccessContextHolder.clear();
                } else {
                    AccessContextHolder.set(previous);
                }
            }
        }
    }

    private void pushForTenant(Long tenantId) {
        // 1. 跑一次蒸馏（全数据源）并 upsert 台账
        issueDistiller.distillForTenant(null);
        // 2. 取待推送行
        List<GovernanceLedgerEntry> pending = ledger.pendingNotifiable();
        if (pending.isEmpty()) {
            return;
        }
        // 3. 解析收件人
        Set<Long> recipients = resolveManagerUids(tenantId);
        if (recipients.isEmpty()) {
            log.debug("治理推送 tenant={} 无管理角色收件人，跳过", tenantId);
            return;
        }
        // 4. 构造聚合文案
        String title = "【待治理问题】新增 " + pending.size() + " 条待处理";
        StringBuilder sb = new StringBuilder();
        sb.append("进入「问数调优中心 · 待治理问题」查看并一键转写。");
        sb.append("\n");
        int limit = Math.min(pending.size(), 10); // 最多列 10 条摘要
        for (int i = 0; i < limit; i++) {
            GovernanceLedgerEntry e = pending.get(i);
            sb.append("· [").append(e.getSignal()).append("] ")
              .append(e.getQuestion() != null ? e.getQuestion() : e.getNormalizedKey())
              .append(" (").append(e.getOccurrences()).append(" 次)\n");
        }
        if (pending.size() > limit) {
            sb.append("…共 ").append(pending.size()).append(" 条");
        }
        String content = sb.toString();
        String severity = pending.size() >= 5 ? "WARN" : "INFO";

        // 5. 逐收件人写站内信
        LocalDateTime now = LocalDateTime.now();
        for (Long uid : recipients) {
            NotifyMessage m = new NotifyMessage();
            m.setTenantId(tenantId);
            m.setUid(uid);
            m.setRuleId(null);
            m.setRecordId(null);
            m.setSeverity(severity);
            m.setTitle(title);
            m.setContent(content);
            m.setIsRead(false);
            m.setCreatedAt(now);
            try {
                messageMapper.insert(m);
            } catch (Exception e) {
                log.debug("治理推送写站内信失败 uid={}: {}", uid, e.getMessage());
            }
        }
        // 6. 标记已推送（去重闸落下）
        ledger.markNotified(pending);
        log.info("治理推送 tenant={} 已送达 {} 人，新增 {} 条", tenantId, recipients.size(), pending.size());
    }

    private Set<Long> resolveManagerUids(Long tenantId) {
        Set<Long> uids = new LinkedHashSet<>();
        for (String role : MANAGER_ROLES) {
            try {
                List<Long> list = userRoleMapper.selectTenantRoleHolders(tenantId, role);
                if (list != null) {
                    uids.addAll(list);
                }
            } catch (Exception e) {
                log.debug("解析管理角色 role={} 失败: {}", role, e.getMessage());
            }
        }
        return uids;
    }
}
