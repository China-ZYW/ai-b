package com.aibi.module.chat.distill;

import com.aibi.common.tenant.TenantContext;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 问题沉淀内核（T11-1）：把窗口内 {@code query_history} 按归一化问法聚合成「待治理清单」。
 *
 * <p><b>三条铁律</b>（对应设计稿 §1/§5/§11，落地"与数据治理共生但不越权"）：
 * <ol>
 *   <li><b>纯只读</b>：只 {@code SELECT}、只在内存里聚合，不写库、不改指标、不碰问数主链路；</li>
 *   <li><b>零 LLM</b>：五类信号全由可观测字段（status/error_msg/verify_json/feedback/conversation_id）
 *       经规则推导，绝不由模型"感觉"打分——与归因 {@code confidenceOf} 同范式；</li>
 *   <li><b>只建议不自动改</b>：每条 issue 给「该补什么 + 一键跳转锚点」，改动权仍在人。</li>
 * </ol>
 *
 * <p>{@link #distill(List, DistillQuery)} 是<b>不依赖 Spring/DB 的纯内核</b>，单测直接喂内存行列表验证；
 * {@link #distillForTenant(Long)} 才是控制器用的"装配配置 + 取数 + 调内核"薄壳。
 */
@Component
public class IssueDistiller {

    private static final Logger log = LoggerFactory.getLogger(IssueDistiller.class);

    /** S5 代价熔断识别：错误信息含这些标记即判为"被代价预估拒绝"（对接 T10-1 CostGuard 落库前缀）。 */
    private static final String[] COST_GUARD_MARKS = {"CostGuard", "代价", "熔断", "扫描行", "cost-guard"};

    /**
     * 时间/量词噪声词（问句归一化用）。按长度降序，先剔多字（"最近"）再剔单字（"近"），
     * 避免"最近"被"近"先咬掉半截。一期用轻量字符串归一（不上 embedding）：
     * 目标是把"近30天销售额""最近一个月销售额"归到同簇，故宁可少合并（见 §11）。
     */
    private static final List<String> TIME_TOKENS = List.of(
            "最近", "近期", "过去", "之前", "以前", "上上个月", "上个月", "这个月", "本月", "上月", "当月",
            "今年", "去年", "明年", "今天", "昨天", "明天", "当天", "本周", "上周", "下周",
            "近", "日", "周", "月", "年", "天", "号", "一", "两", "几", "个");

    private final QueryHistoryMapper historyMapper;
    private final AibiConfig aibiConfig;
    private final ObjectMapper objectMapper;
    private final GovernanceIssueLedgerService ledger;

    public IssueDistiller(QueryHistoryMapper historyMapper, AibiConfig aibiConfig, ObjectMapper objectMapper,
                          GovernanceIssueLedgerService ledger) {
        this.historyMapper = historyMapper;
        this.aibiConfig = aibiConfig;
        this.objectMapper = objectMapper;
        this.ledger = ledger;
    }

    /**
     * 控制器入口：按当前租户 + {@code AibiConfig} 装配参数、取窗口内历史、调纯内核。
     *
     * <p>{@code METRIC / issue-distill} 关闭时<b>直接返回空报告</b>（不取数、不计算），前端 tab 随之空态。
     *
     * @param datasourceId 数据源过滤（可空=全租户）
     */
    public DistillReport distillForTenant(Long datasourceId) {
        Long tenantId = TenantContext.tenantId();
        if (!aibiConfig.issueDistillEnabled()) {
            return DistillReport.empty(null);
        }
        DistillQuery q = new DistillQuery(tenantId, datasourceId,
                aibiConfig.issueDistillWindowDays(), aibiConfig.issueLowScoreThreshold(),
                aibiConfig.issueMinOccurrences(), aibiConfig.issueTopN());
        LocalDateTime since = LocalDateTime.now().minusDays(q.windowDays());
        // 取回上限：给足聚合同质问法的样本，又不至于大表全扫（§11 性能护栏）
        int cap = Math.max(q.topN() * 20, 500);
        List<QueryHistory> rows = historyMapper.selectForDistill(tenantId, datasourceId, since, cap);
        DistillReport report = distill(rows, q, since);
        // 二期 B 台账：upsert 本次扫到的簇 + 把台账处置态回填到每条 issue
        if (!report.issues().isEmpty()) {
            ledger.upsertSeen(report.issues(), datasourceId);
            List<GovernanceIssue> enriched = ledger.attachStatus(report.issues(), datasourceId);
            int handled = 0;
            for (GovernanceIssue e : enriched) {
                if ("CONVERTED".equals(e.status()) || "DISMISSED".equals(e.status())) {
                    handled++;
                }
            }
            DistillReport.Stats newStats = new DistillReport.Stats(
                    report.stats().scanned(), report.stats().failedClusters(),
                    report.stats().lowScoreClusters(), report.stats().thumbsDown(),
                    report.stats().ambiguous(), report.stats().costBlocked(), handled);
            report = new DistillReport(enriched, newStats, report.since());
        }
        return report;
    }

    /** 纯内核便捷入口：按 {@link DistillQuery#windowDays()} 自行推时间窗（供单测与内联调用）。 */
    public DistillReport distill(List<QueryHistory> rows, DistillQuery q) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, q.windowDays()));
        return distill(rows, q, since);
    }

    /**
     * 纯聚合内核：不起 Spring、不连库，喂内存行列表即可。产出按（信号优先级↑、出现次数↓）排序、截断至 topN 的清单。
     */
    public DistillReport distill(List<QueryHistory> rows, DistillQuery q, LocalDateTime since) {
        if (rows == null || rows.isEmpty()) {
            return DistillReport.empty(since);
        }
        // ① 时间窗过滤（createdAt 为空的老记录保守计入）
        List<QueryHistory> win = new ArrayList<>();
        for (QueryHistory r : rows) {
            if (r == null || r.getQuestion() == null || r.getQuestion().isBlank()) {
                continue;
            }
            if (r.getCreatedAt() == null || !r.getCreatedAt().isBefore(since)) {
                win.add(r);
            }
        }
        if (win.isEmpty()) {
            return DistillReport.empty(since);
        }

        int minOcc = Math.max(1, q.minOccurrences());
        List<GovernanceIssue> issues = new ArrayList<>();
        int failedClusters = 0, lowScoreClusters = 0, thumbsDown = 0, ambiguous = 0, costBlocked = 0;

        // ② 按归一化键分组（代表问句取簇内最长一条）
        Map<String, List<QueryHistory>> byKey = new LinkedHashMap<>();
        for (QueryHistory r : win) {
            String key = normalizeKey(r.getQuestion());
            if (key.isEmpty()) {
                continue;
            }
            byKey.computeIfAbsent(key, x -> new ArrayList<>()).add(r);
        }

        // ③ S1/S5/S2：逐归一键统计失败、熔断、低分
        for (Map.Entry<String, List<QueryHistory>> e : byKey.entrySet()) {
            String key = e.getKey();
            List<QueryHistory> grp = e.getValue();

            List<QueryHistory> blocked = new ArrayList<>();
            List<QueryHistory> failedGen = new ArrayList<>();
            List<QueryHistory> lowScore = new ArrayList<>();
            for (QueryHistory r : grp) {
                boolean failed = "FAILED".equalsIgnoreCase(r.getStatus());
                if (failed && isCostBlocked(r)) {
                    blocked.add(r);
                } else if (failed) {
                    failedGen.add(r);
                } else if ("OK".equalsIgnoreCase(r.getStatus()) && isLowScore(r, q.lowScoreThreshold())) {
                    lowScore.add(r);
                }
            }

            // S5 代价熔断：单条即可actionable（用户确有需求），不受 minOcc 限制
            if (!blocked.isEmpty()) {
                issues.add(buildIssue(GovernanceSignal.COST_BLOCKED, key, blocked, blocked.size()));
                costBlocked++;
            }
            if (failedGen.size() >= minOcc) {
                issues.add(buildIssue(GovernanceSignal.FAILED_HIGH, key, failedGen, failedGen.size()));
                failedClusters++;
            }
            if (lowScore.size() >= minOcc) {
                issues.add(buildIssue(GovernanceSignal.LOW_SCORE, key, lowScore, lowScore.size()));
                lowScoreClusters++;
            }
        }

        // ④ S3 用户点踩：逐条即候选（最强信号，不受 minOcc 限制），按归一键聚一次
        Map<String, List<QueryHistory>> downByKey = new LinkedHashMap<>();
        for (QueryHistory r : win) {
            if (r.getFeedback() != null && r.getFeedback() == -1) {
                String key = normalizeKey(r.getQuestion());
                if (!key.isEmpty()) {
                    downByKey.computeIfAbsent(key, x -> new ArrayList<>()).add(r);
                }
            }
        }
        for (Map.Entry<String, List<QueryHistory>> e : downByKey.entrySet()) {
            issues.add(buildIssue(GovernanceSignal.THUMBS_DOWN, e.getKey(), e.getValue(), e.getValue().size()));
            thumbsDown++;
        }

        // ⑤ S4 反复歧义：同会话内同归一键出现 ≥2 次（指代反复、一次问不对）
        Map<String, Integer> ambRepeat = new LinkedHashMap<>();
        Map<String, List<QueryHistory>> byConv = new LinkedHashMap<>();
        for (QueryHistory r : win) {
            if (r.getConversationId() != null && !r.getConversationId().isBlank()) {
                byConv.computeIfAbsent(r.getConversationId(), x -> new ArrayList<>()).add(r);
            }
        }
        for (List<QueryHistory> conv : byConv.values()) {
            if (conv.size() < 2) {
                continue;
            }
            Map<String, Integer> kc = new LinkedHashMap<>();
            for (QueryHistory r : conv) {
                String key = normalizeKey(r.getQuestion());
                if (!key.isEmpty()) {
                    kc.merge(key, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> k : kc.entrySet()) {
                if (k.getValue() >= 2) {
                    ambRepeat.merge(k.getKey(), k.getValue(), Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> e : ambRepeat.entrySet()) {
            List<QueryHistory> sample = byKey.get(e.getKey());
            if (sample == null || sample.isEmpty()) {
                continue;
            }
            issues.add(buildIssue(GovernanceSignal.AMBIGUOUS, e.getKey(), sample, e.getValue()));
            ambiguous++;
        }

        // ⑥ 排序（信号优先级升、出现次数降）+ 截断 topN
        issues.sort(Comparator
                .comparingInt((GovernanceIssue i) -> i.signal().priority())
                .thenComparing(Comparator.comparingInt(GovernanceIssue::occurrences).reversed()));
        int topN = Math.max(1, q.topN());
        if (issues.size() > topN) {
            issues = new ArrayList<>(issues.subList(0, topN));
        }

        DistillReport.Stats stats = new DistillReport.Stats(win.size(), failedClusters,
                lowScoreClusters, thumbsDown, ambiguous, costBlocked, 0);
        log.info("问题沉淀：租户[{}] 数据源[{}] 窗口[{}~{}] 扫描[{}] 条，产出清单[{}] 条（失败簇{} 低分簇{} 点踩{} 歧义{} 熔断{}）",
                q.tenantId(), q.datasourceId(), since, LocalDateTime.now(), win.size(), issues.size(),
                failedClusters, lowScoreClusters, thumbsDown, ambiguous, costBlocked);
        return new DistillReport(issues, stats, since);
    }

    // ==================== 内部辅助 ====================

    /**
     * 组装一条 issue：代表问句取簇内最长，技能/错误样本取簇内首个有值者；代表 SQL 优先取同簇 OK 行（可信草稿）。
     *
     * <p>T11-2：额外带出用户点踩时填的修正建议（expected_sql / feedback_note 首个非空者），
     * 供转写抽屉优先预填——仍是草稿性质，绝不据此自动改库。
     */
    private GovernanceIssue buildIssue(GovernanceSignal signal, String key, List<QueryHistory> grp, int occurrences) {
        QueryHistory rep = grp.get(0);
        for (QueryHistory r : grp) {
            if (r.getQuestion() != null && rep.getQuestion() != null
                    && r.getQuestion().length() > rep.getQuestion().length()) {
                rep = r;
            }
        }
        String skill = null;
        String errSample = null;
        String userSuggestedSql = null;
        String userNote = null;
        for (QueryHistory r : grp) {
            if (skill == null && r.getSkill() != null && !r.getSkill().isBlank()) {
                skill = r.getSkill();
            }
            if (errSample == null && r.getErrorMsg() != null && !r.getErrorMsg().isBlank()) {
                errSample = trim(r.getErrorMsg(), 200);
            }
            // T11-2：点踩修正采集——簇内首个非空期望 SQL / 口径说明（可能多条点踩，取先填报者）
            if (userSuggestedSql == null && r.getExpectedSql() != null && !r.getExpectedSql().isBlank()) {
                userSuggestedSql = r.getExpectedSql();
            }
            if (userNote == null && r.getFeedbackNote() != null && !r.getFeedbackNote().isBlank()) {
                userNote = r.getFeedbackNote();
            }
        }
        // 二期：选代表 SQL —— 优先同归一键的 OK 行 sql（可信草稿），无则退回代表行 sql（失败草稿，前端标"需校对"）
        // 不截断 SQL：避免省略号破坏可直接复制/转写的草稿；列表已限 topN，负载可控
        QueryHistory sqlRep = null;
        boolean hasSuccessSql = false;
        for (QueryHistory r : grp) {
            if ("OK".equalsIgnoreCase(r.getStatus()) && r.getSqlText() != null && !r.getSqlText().isBlank()) {
                sqlRep = r;
                hasSuccessSql = true;
                break;
            }
        }
        if (sqlRep == null) {
            sqlRep = rep;
        }
        String sampleSql = (sqlRep.getSqlText() != null && !sqlRep.getSqlText().isBlank())
                ? sqlRep.getSqlText() : null;
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("datasourceId", rep.getDatasourceId());
        anchor.put("normalizedKey", key);
        if (skill != null) {
            anchor.put("skill", skill);
        }
        if (sqlRep.getId() != null) {
            anchor.put("historyId", sqlRep.getId());
        }
        return new GovernanceIssue(signal, rep.getQuestion(), key, occurrences, skill, errSample,
                actionFor(signal, skill), sampleSql, hasSuccessSql, anchor,
                null, "OPEN", null, null, userSuggestedSql, userNote);
    }

    /**
     * 信号 + 当时技能 → 治理建议（这是价值落点，见设计稿 §4）。
     *
     * <p>刻意只吃 {@code (signal, skill)}：二者都在 {@code query_history} 里，纯内核因此无需连指标库即可判方向，
     * 保证脱库单测可行；"命中不到指标名"这类需查库的精细归因留二期（叠 {@code MetricMapper}/{@code TermMapMapper} 富化）。
     */
    private String actionFor(GovernanceSignal signal, String skill) {
        switch (signal) {
            case COST_BLOCKED:
                return "该问法代价过高被熔断、用户确有取数需求 → 建议建预聚合指标 / 中间表 / 物化视图";
            case THUMBS_DOWN:
                if ("metric".equals(skill)) {
                    return "用户点踩且走指标直达 → 请人工复核该指标口径定义（勿自动改）";
                }
                return "用户明确点踩、答案不符预期 → 复核口径 / 补充或修正少样本";
            case AMBIGUOUS:
                return "同一会话反复澄清 → 建议补充术语映射或指标别名，让该问法一次可命中";
            case LOW_SCORE:
            case FAILED_HIGH:
            default:
                if (skill == null || "nl2sql".equals(skill)) {
                    return "走了兜底生成仍失败/低质 → 该问法无确定性口径接住，建议沉淀为指标或加入少样本";
                }
                if ("fewshot".equals(skill)) {
                    return "样例复用未答好 → 补充/修正少样本问法";
                }
                if ("metric".equals(skill)) {
                    return "指标直达结果不佳 → 复核该指标口径与 SQL";
                }
                if ("direct_sql".equals(skill)) {
                    return "直连 SQL 问法失败 → 核对表/字段是否存在、口径是否清晰";
                }
                return "完善口径 / 术语映射 / 少样本";
        }
    }

    /** S5：错误信息含代价熔断标记。 */
    private boolean isCostBlocked(QueryHistory r) {
        String msg = r.getErrorMsg();
        if (msg == null) {
            return false;
        }
        for (String mark : COST_GUARD_MARKS) {
            if (msg.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    /** S2：verify_json.score 存在且低于阈值（解析失败/无分数视为非低分，绝不因脏数据误报）。 */
    private boolean isLowScore(QueryHistory r, int threshold) {
        int score = scoreOf(r.getVerifyJson());
        return score >= 0 && score < threshold;
    }

    private int scoreOf(String verifyJson) {
        if (verifyJson == null || verifyJson.isBlank()) {
            return -1;
        }
        try {
            JsonNode node = objectMapper.readTree(verifyJson);
            JsonNode s = node.path("score");
            return s.isNumber() ? s.asInt() : -1;
        } catch (Exception e) {
            log.debug("解析 verify_json 分数失败，视为无分数：{}", e.getMessage());
            return -1;
        }
    }

    /**
     * 问句归一化分组键：小写、全角转半角、去标点/空白/数字、剥离时间量词噪声。
     *
     * <p>public static 以便单测直接断言"近30天销售额"与"最近一个月销售额"归一键相同（设计稿 §9 用例 5）。
     */
    public static String normalizeKey(String question) {
        if (question == null) {
            return "";
        }
        String s = question.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u3000') {                     // 全角空格
                continue;
            }
            if (c >= '\uFF01' && c <= '\uFF5E') {    // 全角 ASCII 区 → 半角
                c = (char) (c - 0xFEE0);
            }
            sb.append(c);
        }
        s = sb.toString();
        s = s.replaceAll("[\\p{Punct}，。、；：！？“”‘’（）《》【】…—·]", ""); // 去标点
        s = s.replaceAll("\\s+", "");                                          // 去空白
        s = s.replaceAll("[0-9]+", "");                                        // 去数字
        for (String token : TIME_TOKENS) {
            s = s.replace(token, "");
        }
        return s.trim();
    }

    private String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
