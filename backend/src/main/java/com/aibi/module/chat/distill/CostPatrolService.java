package com.aibi.module.chat.distill;

import com.aibi.common.tenant.TenantContext;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本巡检编排（T12-4 成本优化 Agent 一期）。
 *
 * <p>一次 {@link #patrol(Long)}：按窗口取本租户（可限定单数据源）问数历史 →
 * {@link CostPatrolAnalyzer} 纯内核聚簇三分类出成本建议 → 命中行 {@code upsertSeen} 进治理台账 →
 * 返回摘要 + 本次投影（含建议文案）。<b>零 LLM、零 DDL、只读自身历史表</b>，不碰用户业务库，
 * 也绝不自动改任何资产——「转指标」出口复用治理面板既有转写链路（必经人工确认）。
 *
 * <p>门控 {@code METRIC / cost-patrol}（默认开）由控制器把关；本服务不重复判闸（对齐 T11-4 分工）。
 */
@Service
public class CostPatrolService {

    private static final Logger log = LoggerFactory.getLogger(CostPatrolService.class);

    private final QueryHistoryMapper historyMapper;
    private final AibiConfig aibiConfig;
    private final GovernanceIssueLedgerService ledger;

    public CostPatrolService(QueryHistoryMapper historyMapper, AibiConfig aibiConfig,
                             GovernanceIssueLedgerService ledger) {
        this.historyMapper = historyMapper;
        this.aibiConfig = aibiConfig;
        this.ledger = ledger;
    }

    /**
     * 立即成本巡检。
     *
     * @param datasourceId 指定数据源（可空=本租户全部数据源，按行自带 ds 分组各自聚簇——
     *                     台账唯一键含 ds，跨库同问法各算各账，不互相稀释）
     */
    public CostPatrolResult patrol(Long datasourceId) {
        Long tenantId = TenantContext.tenantId();
        int days = aibiConfig.costPatrolWindowDays();
        if (tenantId == null) {
            return CostPatrolResult.empty(days);
        }
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        // 取数口径与 T11-1 蒸馏同源（同列面同 cap 护栏）：耗时/SQL/归一问法所需的列都在投影里
        int cap = Math.max(aibiConfig.issueTopN() * 20, 500);
        List<QueryHistory> rows = historyMapper.selectForDistill(tenantId, datasourceId, since, cap);
        String note = rows != null && rows.size() >= cap
                ? "窗口内历史达取数上限 " + cap + " 条，更早的未计入本次聚簇" : null;
        if (rows == null || rows.isEmpty()) {
            return new CostPatrolResult(0, days, since, 0, 0, 0, note, List.of());
        }

        CostPatrolAnalyzer.Params p = new CostPatrolAnalyzer.Params(
                aibiConfig.costPatrolSlowMs(), aibiConfig.costPatrolDupMinOccurrences(),
                aibiConfig.costPatrolMaterializeMinOccurrences(), days, aibiConfig.issueTopN());

        // 分组聚簇：显式指定 ds 时整集一组；否则按行自带 ds 分组（无 ds 的脏行剔除，防台账丢键）
        Map<Long, List<QueryHistory>> byDs = new LinkedHashMap<>();
        for (QueryHistory r : rows) {
            Long ds = datasourceId != null ? datasourceId : (r == null ? null : r.getDatasourceId());
            if (ds == null) {
                continue;
            }
            byDs.computeIfAbsent(ds, k -> new ArrayList<>()).add(r);
        }
        List<GovernanceIssue> all = new ArrayList<>();
        for (Map.Entry<Long, List<QueryHistory>> e : byDs.entrySet()) {
            all.addAll(CostPatrolAnalyzer.analyze(e.getKey(), e.getValue(), p));
        }

        // 命中全量进台账（幂等 upsert，同簇复发只累计不重复插行；台账失败不拦本次返回）
        try {
            ledger.upsertSeen(all, datasourceId);
        } catch (Exception ex) {
            log.warn("成本巡检台账写入失败（不拦本次结果返回）: {}", ex.getMessage());
        }

        int materialize = count(all, GovernanceSignal.COST_PATROL_MATERIALIZE);
        int slow = count(all, GovernanceSignal.COST_PATROL_SLOW_SQL);
        int dup = count(all, GovernanceSignal.COST_PATROL_DUP_QUERY);
        // 跨组重排：信号优先级升 → 出现次数降，截断 topN（投影只供本次弹窗，台账已全量落）
        List<GovernanceIssue> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt((GovernanceIssue i) -> i.signal().priority())
                .thenComparing(i -> -i.occurrences()));
        List<GovernanceIssue> top = sorted.size() > p.topN()
                ? List.copyOf(sorted.subList(0, p.topN())) : sorted;
        return new CostPatrolResult(rows.size(), days, since, materialize, slow, dup, note, top);
    }

    private static int count(List<GovernanceIssue> issues, GovernanceSignal signal) {
        return (int) issues.stream().filter(i -> i.signal() == signal).count();
    }
}
