package com.aibi.module.chat.distill;

import com.aibi.module.history.entity.QueryHistory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 成本巡检纯内核（T12-4 成本优化 Agent 一期，零 LLM、纯规则、只读）。
 *
 * <p>输入 = 时间窗内的问数历史行（{@code selectForDistill} 同源取数，本类不查库），
 * 按 {@link IssueDistiller#normalizeKey(String) 归一化问法} 聚簇成功行（status=OK 且耗时有值），
 * 每簇按「均耗时 × 出现次数」三分类出成本建议，<b>一簇只出一行</b>（与 T12-3 整表删除抑制列级
 * 同款防重复霸屏哲学）：
 * <ol>
 *   <li>{@link GovernanceSignal#COST_PATROL_MATERIALIZE}：又慢又反复问——物化/预聚合收益最高，
 *       建议转指标口径或走驾驶舱/定时报表离线产出；</li>
 *   <li>{@link GovernanceSignal#COST_PATROL_SLOW_SQL}：单次均耗时超慢阈值——建议加过滤缩范围/拆分时间窗；</li>
 *   <li>{@link GovernanceSignal#COST_PATROL_DUP_QUERY}：问法不慢但被反复问——建议转指标/少样本或收藏固化。</li>
 * </ol>
 *
 * <p><b>宁缺勿滥边界</b>：失败行耗时无「跑完」语义（且 FAILED_HIGH 已管）不参与；归一键为空、
 * 耗时缺失/为负的行剔除；三分类阈值全部由调用方经 {@code AibiConfig} 注入，本类无配置依赖。
 * 只产出<b>建议</b>，绝不自动改任何资产——转指标出口复用治理面板既有转写链路（必经人工确认）。
 */
public final class CostPatrolAnalyzer {

    /** 判定参数（窗口天数仅进建议文案与摘要，行集过滤在取数侧完成）。 */
    public record Params(long slowMs, int dupMinOccurrences, int materializeMinOccurrences,
                         int windowDays, int topN) {
    }

    /** 一簇归一同款问法的成功行聚合体。 */
    private record Cluster(String key, List<QueryHistory> rows) {
    }

    /** 建议行 + 簇均耗时（排序用第三维，不进投影，出口前剥离）。 */
    private record Scored(GovernanceIssue issue, long avgMs) {
    }

    private CostPatrolAnalyzer() {
    }

    /**
     * 聚簇并三分类出成本建议。
     *
     * @param datasourceId 建议行的跳转锚点（可空=台账侧用默认数据源）
     * @param rows         窗口内历史行（顺序按 created_at DESC，簇内首行即最新）
     * @param p            阈值参数
     * @return 成本建议行（信号优先级升 → 出现次数降 → 均耗时降，截断 topN）；输入空返回空表
     */
    public static List<GovernanceIssue> analyze(Long datasourceId, List<QueryHistory> rows, Params p) {
        if (rows == null || rows.isEmpty() || p == null) {
            return List.of();
        }
        // ① 聚簇：只收成功且耗时有意义的行
        Map<String, Cluster> clusters = new LinkedHashMap<>();
        for (QueryHistory r : rows) {
            if (r == null || !"OK".equalsIgnoreCase(r.getStatus())) {
                continue;
            }
            if (r.getElapsedMs() == null || r.getElapsedMs() < 0) {
                continue;
            }
            String key = IssueDistiller.normalizeKey(r.getQuestion());
            if (key.isBlank()) {
                continue;
            }
            Cluster c = clusters.computeIfAbsent(key, k -> new Cluster(k, new ArrayList<>()));
            c.rows().add(r);
        }

        // ② 三分类（一簇一行；慢优先于重复，又慢又频繁升格为物化候选）
        List<Scored> scored = new ArrayList<>();
        for (Cluster c : clusters.values()) {
            int occ = c.rows().size();
            long sum = 0;
            for (QueryHistory r : c.rows()) {
                sum += r.getElapsedMs();
            }
            long avg = Math.round((double) sum / occ);
            if (avg < p.slowMs() && occ < p.dupMinOccurrences()) {
                continue; // 既不快也不慢、又没被反复问：不值得占屏
            }
            GovernanceSignal signal;
            String action;
            if (avg >= p.slowMs() && occ >= p.materializeMinOccurrences()) {
                signal = GovernanceSignal.COST_PATROL_MATERIALIZE;
                action = "近 " + p.windowDays() + " 天被问 " + occ + " 次、平均耗时 " + fmtSec(avg)
                        + "：又慢又高频，优先转预聚合指标（转指标）或落驾驶舱/定时报表离线产出。";
            } else if (avg >= p.slowMs()) {
                signal = GovernanceSignal.COST_PATROL_SLOW_SQL;
                action = "平均耗时 " + fmtSec(avg) + "（近 " + p.windowDays() + " 天 " + occ
                        + " 次）：建议增加过滤维度缩小扫描范围或拆分时间窗；口径稳定的话转成指标统一走加速路径。";
            } else {
                signal = GovernanceSignal.COST_PATROL_DUP_QUERY;
                action = "同一问法近 " + p.windowDays() + " 天出现 " + occ
                        + " 次：建议转指标/少样本或加入驾驶舱收藏，免去重复生成与执行。";
            }
            scored.add(new Scored(toIssue(datasourceId, c, signal, action, occ, avg), avg));
        }

        // ③ 稳定排序 + 截断：信号优先级升 → 出现次数降 → 均耗时降
        scored.sort((a, b) -> {
            int bySignal = Integer.compare(a.issue().signal().priority(), b.issue().signal().priority());
            if (bySignal != 0) {
                return bySignal;
            }
            if (a.issue().occurrences() != b.issue().occurrences()) {
                return Integer.compare(b.issue().occurrences(), a.issue().occurrences());
            }
            return Long.compare(b.avgMs(), a.avgMs());
        });
        List<GovernanceIssue> issues = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            issues.add(s.issue());
        }
        return issues.size() > p.topN() && p.topN() > 0
                ? List.copyOf(issues.subList(0, p.topN())) : issues;
    }

    /** 簇 → 治理行：代表问句取簇内最长（最完整），代表 SQL 取簇内最长 OK 行 sql。 */
    private static GovernanceIssue toIssue(Long datasourceId, Cluster c, GovernanceSignal signal,
                                           String action, int occ, long avgMs) {
        String question = null;
        String sampleSql = null;
        String skill = c.rows().get(0).getSkill(); // 行集 created_at DESC，簇首行=最新一次
        for (QueryHistory r : c.rows()) {
            String q = r.getQuestion();
            if (q != null && (question == null || q.length() > question.length())) {
                question = q;
            }
            String sql = r.getSqlText();
            if (sql != null && !sql.isBlank() && (sampleSql == null || sql.length() > sampleSql.length())) {
                sampleSql = sql;
            }
        }
        Map<String, Object> anchor = new LinkedHashMap<>();
        if (datasourceId != null) {
            anchor.put("datasourceId", datasourceId);
        }
        anchor.put("elapsedAvgMs", avgMs);
        return new GovernanceIssue(signal, question, c.key(), occ, skill, null, action,
                sampleSql, sampleSql != null, Map.copyOf(anchor),
                null, "OPEN", null, null);
    }

    private static String fmtSec(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }
}
