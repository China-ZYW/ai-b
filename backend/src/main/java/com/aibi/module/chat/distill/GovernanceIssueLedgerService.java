package com.aibi.module.chat.distill;

import com.aibi.common.tenant.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 待治理问题台账服务（T11-1 二期 B）：持久化处置态、支持"已处置不再霸屏"与定时推送去重。
 *
 * <p><b>红线</b>：本服务只写 {@code governance_issue} 表（自有台账），绝不触碰 metric/fewshot 数据资产。
 */
@Service
public class GovernanceIssueLedgerService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceIssueLedgerService.class);

    private final GovernanceLedgerMapper mapper;

    public GovernanceIssueLedgerService(GovernanceLedgerMapper mapper) {
        this.mapper = mapper;
    }

    // ==================== 读侧：upsert + attach ====================

    /**
     * 蒸馏得到的簇列表 → upsert 台账：不存在则 INSERT（OPEN），存在仅刷新 last_seen_at / occurrences（<b>不回退 status</b>）。
     *
     * @param issues       本次纯内核产出的治理簇列表
     * @param datasourceId 数据源（空=全租户，但台账按簇各行的 jumpAnchor 取实际 ds）
     */
    public void upsertSeen(List<GovernanceIssue> issues, Long datasourceId) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null || issues == null || issues.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (GovernanceIssue issue : issues) {
            Long ds = resolveDs(issue, datasourceId);
            if (ds == null) {
                continue; // 无数据源锚点则无法定位唯一键，跳过
            }
            String keyHash = sha256(issue.normalizedKey());
            String signal = issue.signal().name();
            // 尝试查找已有行
            GovernanceLedgerEntry existing = selectByUnique(tenantId, ds, keyHash, signal);
            if (existing == null) {
                // INSERT 新行
                GovernanceLedgerEntry row = new GovernanceLedgerEntry();
                row.setTenantId(tenantId);
                row.setDatasourceId(ds);
                row.setSignal(signal);
                row.setNormalizedKey(truncate(issue.normalizedKey(), 512));
                row.setKeyHash(keyHash);
                row.setStatus("OPEN");
                row.setQuestion(truncate(issue.question(), 512));
                row.setOccurrences(issue.occurrences());
                row.setFirstSeenAt(now);
                row.setLastSeenAt(now);
                try {
                    mapper.insert(row);
                } catch (Exception e) {
                    // 并发唯一键冲突 → 下次再刷新（不抛，不中断循环）
                    log.debug("台账 upsert 冲突跳过: ds={} signal={} key={}", ds, signal, issue.normalizedKey());
                }
            } else {
                // UPDATE last_seen_at + occurrences（不改 status）
                existing.setLastSeenAt(now);
                existing.setOccurrences(issue.occurrences());
                existing.setQuestion(truncate(issue.question(), 512));
                mapper.updateById(existing);
            }
        }
    }

    /**
     * 读侧把台账状态映射回 {@link GovernanceIssue}（创建新 record 实例填充 ledgerId/status/refType/refId）。
     *
     * @return 新的 enriched list（原 list 不变，record 不可变）
     */
    public List<GovernanceIssue> attachStatus(List<GovernanceIssue> issues, Long datasourceId) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null || issues == null || issues.isEmpty()) {
            return issues;
        }
        List<GovernanceIssue> enriched = new ArrayList<>(issues.size());
        for (GovernanceIssue issue : issues) {
            Long ds = resolveDs(issue, datasourceId);
            if (ds == null) {
                enriched.add(issue);
                continue;
            }
            String keyHash = sha256(issue.normalizedKey());
            GovernanceLedgerEntry entry = selectByUnique(tenantId, ds, keyHash, issue.signal().name());
            if (entry != null) {
                enriched.add(new GovernanceIssue(
                        issue.signal(), issue.question(), issue.normalizedKey(), issue.occurrences(),
                        issue.skill(), issue.errorMsgSample(), issue.suggestedAction(),
                        issue.sampleSql(), issue.hasSuccessSql(), issue.jumpAnchor(),
                        entry.getId(), entry.getStatus(), entry.getRefType(), entry.getRefId(),
                        // T11-2：透传点踩修正建议，否则回填后台丢失
                        issue.userSuggestedSql(), issue.userNote()));
            } else {
                enriched.add(issue); // 无台账行（理论上 upsert 后不会到这），保持原样
            }
        }
        return enriched;
    }

    // ==================== 写侧：处置操作 ====================

    /** 标记转写完成（CONVERTED），锚点到产出资源。 */
    public void markConverted(Long datasourceId, String normalizedKey, String signal,
                              String refType, Long refId, Long uid) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null || datasourceId == null || normalizedKey == null || signal == null) {
            return;
        }
        String keyHash = sha256(normalizedKey);
        GovernanceLedgerEntry entry = selectByUnique(tenantId, datasourceId, keyHash, signal);
        if (entry == null) {
            return; // 幂等：行不存在则跳过（竞态安全）
        }
        entry.setStatus("CONVERTED");
        entry.setRefType(refType);
        entry.setRefId(refId);
        entry.setResolvedAt(LocalDateTime.now());
        entry.setResolvedBy(uid);
        mapper.updateById(entry);
    }

    /** 标记忽略（DISMISSED）。 */
    public void markDismissed(Long datasourceId, String normalizedKey, String signal,
                              String reason, Long uid) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null || datasourceId == null || normalizedKey == null || signal == null) {
            return;
        }
        String keyHash = sha256(normalizedKey);
        GovernanceLedgerEntry entry = selectByUnique(tenantId, datasourceId, keyHash, signal);
        if (entry == null) {
            return;
        }
        entry.setStatus("DISMISSED");
        entry.setDismissReason(truncate(reason, 255));
        entry.setResolvedAt(LocalDateTime.now());
        entry.setResolvedBy(uid);
        mapper.updateById(entry);
    }

    /** 重新打开（OPEN），清空处置痕迹与推送去重。 */
    public void reopen(Long ledgerId, Long uid) {
        if (ledgerId == null) {
            return;
        }
        GovernanceLedgerEntry entry = mapper.selectById(ledgerId);
        if (entry == null) {
            return;
        }
        entry.setStatus("OPEN");
        entry.setRefType(null);
        entry.setRefId(null);
        entry.setDismissReason(null);
        entry.setResolvedAt(null);
        entry.setResolvedBy(null);
        entry.setNotifiedAt(null); // 撤回后视为新问题，可再次进入下一轮推送
        mapper.updateById(entry);
    }

    // ==================== 读侧：元数据巡检行投影（T11-4） ====================

    /**
     * 取台账中巡检行（signal 以 {@code META_} 开头）并投影为 {@link GovernanceIssue}，供 {@code GET /chat/tuning/issues} 合入展示。
     *
     * <p>巡检行命中时已落台账，此处不再 upsert；排序按信号优先级（枚举声明序）升、同信号按 occurrences 降，
     * 与蒸馏簇合并时天然排在问数信号之后（META_* 追加在枚举尾部）。
     *
     * @param includeHandled 是否含已处置（CONVERTED/DISMISSED）行，语义与蒸馏侧一致
     */
    public List<GovernanceIssue> listMetaPatrol(boolean includeHandled) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) {
            return List.of();
        }
        LambdaQueryWrapper<GovernanceLedgerEntry> q = new LambdaQueryWrapper<>();
        q.eq(GovernanceLedgerEntry::getTenantId, tenantId)
         .likeRight(GovernanceLedgerEntry::getSignal, "META_");
        if (!includeHandled) {
            q.eq(GovernanceLedgerEntry::getStatus, "OPEN");
        }
        List<GovernanceLedgerEntry> rows = mapper.selectList(q);
        List<GovernanceIssue> out = new ArrayList<>(rows.size());
        for (GovernanceLedgerEntry e : rows) {
            GovernanceSignal signal;
            try {
                signal = GovernanceSignal.valueOf(e.getSignal());
            } catch (IllegalArgumentException ex) {
                continue; // 台账里出现未知信号（回滚升级等）：跳过不炸清单
            }
            if (signal.priority() < GovernanceSignal.META_LINEAGE_BROKEN.priority()) {
                continue; // 防御：只收 META_* 系（巡检三值 + T12-3 结构变更）
            }
            Map<String, Object> anchor = new java.util.LinkedHashMap<>();
            if (e.getDatasourceId() != null) {
                anchor.put("datasourceId", e.getDatasourceId());
            }
            out.add(new GovernanceIssue(signal, e.getQuestion(), e.getNormalizedKey(),
                    e.getOccurrences() == null ? 1 : e.getOccurrences(),
                    "meta_patrol", null, null, null, false, java.util.Collections.unmodifiableMap(anchor),
                    e.getId(), e.getStatus(), e.getRefType(), e.getRefId()));
        }
        out.sort(java.util.Comparator.comparingInt((GovernanceIssue i) -> i.signal().priority())
                .thenComparing(i -> -i.occurrences()));
        return out;
    }

    // ==================== 读侧：成本巡检行投影（T12-4） ====================

    /**
     * 取台账中成本巡检行（signal 以 {@code COST_PATROL_} 开头）并投影为 {@link GovernanceIssue}，
     * 供 {@code GET /chat/tuning/issues} 合入展示。
     *
     * <p>与 {@link #listMetaPatrol} 同构分带：名称门槛 {@code COST_PATROL_} 与 {@code COST_BLOCKED}（蒸馏信号）
     * 天然互斥，优先级防御只收 T12-4 三值；排序优先级升、同信号 occurrences 降，合入时排在巡检行之后。
     *
     * @param includeHandled 是否含已处置（CONVERTED/DISMISSED）行，语义与蒸馏侧一致
     */
    public List<GovernanceIssue> listCostPatrol(boolean includeHandled) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) {
            return List.of();
        }
        LambdaQueryWrapper<GovernanceLedgerEntry> q = new LambdaQueryWrapper<>();
        q.eq(GovernanceLedgerEntry::getTenantId, tenantId)
         .likeRight(GovernanceLedgerEntry::getSignal, "COST_PATROL_");
        if (!includeHandled) {
            q.eq(GovernanceLedgerEntry::getStatus, "OPEN");
        }
        List<GovernanceLedgerEntry> rows = mapper.selectList(q);
        List<GovernanceIssue> out = new ArrayList<>(rows.size());
        for (GovernanceLedgerEntry e : rows) {
            GovernanceSignal signal;
            try {
                signal = GovernanceSignal.valueOf(e.getSignal());
            } catch (IllegalArgumentException ex) {
                continue; // 未知信号（回滚升级等）：跳过不炸清单
            }
            if (signal.priority() < GovernanceSignal.COST_PATROL_MATERIALIZE.priority()) {
                continue; // 防御：只收 COST_PATROL_* 三值
            }
            Map<String, Object> anchor = new java.util.LinkedHashMap<>();
            if (e.getDatasourceId() != null) {
                anchor.put("datasourceId", e.getDatasourceId());
            }
            out.add(new GovernanceIssue(signal, e.getQuestion(), e.getNormalizedKey(),
                    e.getOccurrences() == null ? 1 : e.getOccurrences(),
                    "cost_patrol", null, costAdvice(signal), null, false, java.util.Collections.unmodifiableMap(anchor),
                    e.getId(), e.getStatus(), e.getRefType(), e.getRefId()));
        }
        out.sort(java.util.Comparator.comparingInt((GovernanceIssue i) -> i.signal().priority())
                .thenComparing(i -> -i.occurrences()));
        return out;
    }

    /**
     * 成本行的通用建议文案：台账未存当时耗时数字（重算易漂移），按信号给确定性方向文案，
     * 保证面板「建议动作」列对成本行始终有指导（具体均耗时见巡检当次提示）。
     */
    private static String costAdvice(GovernanceSignal signal) {
        switch (signal) {
            case COST_PATROL_MATERIALIZE:
                return "又慢又被反复问：优先转指标（预聚合）或落驾驶舱/定时报表离线产出";
            case COST_PATROL_SLOW_SQL:
                return "该问法执行慢：考虑增加过滤缩小扫描范围，口径稳定则转指标走确定性加速";
            case COST_PATROL_DUP_QUERY:
                return "该问法反复出现：建议转指标/少样本或加入收藏，免去重复生成与执行";
            default:
                return null;
        }
    }

    // ==================== 推送侧 ====================

    /**
     * 取待推送的 OPEN 行（scheduler 用）：status=OPEN 且（notified_at IS NULL OR notified_at < last_seen_at）。
     * 效果：首次冒头推一次，持续存在不重复推（D2 决策），reopen 清 notified_at 后可再推。
     */
    public List<GovernanceLedgerEntry> pendingNotifiable() {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) {
            return List.of();
        }
        LambdaQueryWrapper<GovernanceLedgerEntry> q = new LambdaQueryWrapper<>();
        q.eq(GovernanceLedgerEntry::getTenantId, tenantId)
         .eq(GovernanceLedgerEntry::getStatus, "OPEN")
         .and(w -> w.isNull(GovernanceLedgerEntry::getNotifiedAt)
                 .or().apply("notified_at < last_seen_at"));
        return mapper.selectList(q);
    }

    /** 批量标记已推送。 */
    public void markNotified(List<GovernanceLedgerEntry> entries) {
        LocalDateTime now = LocalDateTime.now();
        for (GovernanceLedgerEntry e : entries) {
            e.setNotifiedAt(now);
            mapper.updateById(e);
        }
    }

    // ==================== 内部辅助 ====================

    private GovernanceLedgerEntry selectByUnique(Long tenantId, Long ds, String keyHash, String signal) {
        LambdaQueryWrapper<GovernanceLedgerEntry> q = new LambdaQueryWrapper<>();
        q.eq(GovernanceLedgerEntry::getTenantId, tenantId)
         .eq(GovernanceLedgerEntry::getDatasourceId, ds)
         .eq(GovernanceLedgerEntry::getKeyHash, keyHash)
         .eq(GovernanceLedgerEntry::getSignal, signal)
         .last("LIMIT 1");
        return mapper.selectOne(q);
    }

    private Long resolveDs(GovernanceIssue issue, Long fallbackDs) {
        // 优先从 jumpAnchor 取 datasourceId，否则用传入参数
        if (issue.jumpAnchor() != null) {
            Object ds = issue.jumpAnchor().get("datasourceId");
            if (ds instanceof Number n) {
                return n.longValue();
            }
        }
        return fallbackDs;
    }

    static String sha256(String input) {
        if (input == null || input.isEmpty()) {
            return "0".repeat(64);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
