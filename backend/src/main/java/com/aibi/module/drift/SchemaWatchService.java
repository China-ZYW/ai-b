package com.aibi.module.drift;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.alert.entity.AlertRule;
import com.aibi.module.alert.mapper.AlertRuleMapper;
import com.aibi.module.chat.distill.GovernanceIssue;
import com.aibi.module.chat.distill.GovernanceIssueLedgerService;
import com.aibi.module.chat.distill.GovernanceSignal;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.datasource.entity.DataSource;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.drift.entity.SchemaSnapshot;
import com.aibi.module.drift.mapper.SchemaSnapshotMapper;
import com.aibi.module.message.entity.NotifyMessage;
import com.aibi.module.message.mapper.NotifyMessageMapper;
import com.aibi.module.query.entity.SavedQuery;
import com.aibi.module.query.mapper.SavedQueryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 结构变更感知服务（T12-3 指标变更感知）。
 *
 * <p><b>与 {@link SchemaDriftService} 的分工</b>：对账 check 是「当前 schema vs 下游引用」的无状态比对，
 * 回答"现在有哪些资产已经坏了"；本服务是「上轮快照 vs 本轮扫描」的有状态 diff，回答
 * <b>"这次改了什么、砸到了谁的资产"</b>——删表/删列事件反查 saved_query/metric（并顺带标出
 * 挂坏指标的智能预警规则），命中行进治理台账（{@link GovernanceSignal#META_SCHEMA_CHANGED}），
 * 并定向站内信受影响资产的创建人。
 *
 * <p><b>确定性、零 LLM、只读业务数据</b>：schema 直扫（{@link DataSourceService#scanSchemaRaw}，
 * 不落缓存不动取数链路）；首轮只建基准不告警；无论是否命中影响，感知成功即滚动快照，
 * 同一变更不会重复霸屏。门控 {@code METRIC / drift-watch}（默认开）。
 */
@Service
public class SchemaWatchService {

    private static final Logger log = LoggerFactory.getLogger(SchemaWatchService.class);

    /** 台账行 skill 标记（与巡检行 meta_patrol 并列的读侧口径）。 */
    public static final String SKILL_TAG = "schema_watch";

    /** 预警规则业务指标键前缀（与 {@code AlertEngineService.METRIC_PREFIX} 同格式约定）。 */
    private static final String METRIC_KEY_PREFIX = "metric:";

    private final DataSourceService dataSourceService;
    private final SchemaSnapshotMapper snapshotMapper;
    private final SavedQueryMapper savedQueryMapper;
    private final MetricMapper metricMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final GovernanceIssueLedgerService ledger;
    private final NotifyMessageMapper notifyMessageMapper;
    private final ObjectMapper objectMapper;

    public SchemaWatchService(DataSourceService dataSourceService,
                              SchemaSnapshotMapper snapshotMapper,
                              SavedQueryMapper savedQueryMapper,
                              MetricMapper metricMapper,
                              AlertRuleMapper alertRuleMapper,
                              GovernanceIssueLedgerService ledger,
                              NotifyMessageMapper notifyMessageMapper,
                              ObjectMapper objectMapper) {
        this.dataSourceService = dataSourceService;
        this.snapshotMapper = snapshotMapper;
        this.savedQueryMapper = savedQueryMapper;
        this.metricMapper = metricMapper;
        this.alertRuleMapper = alertRuleMapper;
        this.ledger = ledger;
        this.notifyMessageMapper = notifyMessageMapper;
        this.objectMapper = objectMapper;
    }

    /** 受影响资产的前端投影（不带资产全文，避免大 SQL 过网络）。 */
    public record ImpactBrief(String assetType, Long assetId, String name, List<String> brokenRefs) {
    }

    /** 一次感知的结果投影（前端/接口展示用）。 */
    public record WatchResult(Long datasourceId,
                              boolean baselineCreated,
                              List<String> changeSummaries,
                              List<ImpactBrief> impacts,
                              int notifiedUsers,
                              String note) {
    }

    /**
     * 感知指定数据源的结构变更：现扫 → 与上轮快照 diff → 删表/删列反查影响 → 台账+站内信 → 滚动快照。
     *
     * @param datasourceId 数据源 id（跨租户/不存在由 scanSchemaRaw 内部校验抛错）
     */
    public WatchResult watch(Long datasourceId) {
        List<TableMeta> schema = dataSourceService.scanSchemaRaw(datasourceId);
        Map<String, Set<String>> current = index(schema);

        SchemaSnapshot snap = snapshotMapper.selectOne(
                new QueryWrapper<SchemaSnapshot>().eq("datasource_id", datasourceId).last("LIMIT 1"));
        if (snap == null) {
            saveSnapshot(null, datasourceId, current);
            return new WatchResult(datasourceId, true, List.of(), List.of(), 0,
                    "首轮基准已建立（" + current.size() + " 表），本次未做变更比对");
        }

        Map<String, Set<String>> prev = load(snap);
        List<SchemaChangeDiff.Change> changes = SchemaChangeDiff.diff(prev, current);
        List<SchemaChangeDiff.Change> drops = SchemaChangeDiff.drops(changes);
        List<String> summaries = summarize(changes);
        if (drops.isEmpty()) {
            saveSnapshot(snap, datasourceId, current);
            return new WatchResult(datasourceId, false, summaries, List.of(), 0,
                    summaries.isEmpty() ? "结构无变化" : "仅新增（不打断既有引用），未触发预警");
        }

        // 反查受影响资产：本数据源的查询模板（sql+spec 文本）与指标（sql+chartSpec 文本）
        List<SchemaImpactScanner.Asset> assets = new ArrayList<>();
        for (SavedQuery q : savedQueryMapper.selectList(
                new QueryWrapper<SavedQuery>().eq("datasource_id", datasourceId))) {
            assets.add(new SchemaImpactScanner.Asset("SAVED_QUERY", q.getId(), q.getName(), q.getUid(),
                            joinText(q.getSqlTemplate(), q.getSpec())));
        }
        for (Metric m : metricMapper.selectList(
                new QueryWrapper<Metric>().eq("datasource_id", datasourceId))) {
            assets.add(new SchemaImpactScanner.Asset("METRIC", m.getId(), m.getName(), m.getUid(),
                    joinText(m.getSqlText(), m.getChartSpec())));
        }
        List<SchemaImpactScanner.Impact> impacts = new ArrayList<>(
                SchemaImpactScanner.scan(drops, assets));
        attachAlertRuleHints(impacts);

        String dsName = dataSourceName(datasourceId);
        String changeText = String.join("；", summaries);
        if (!impacts.isEmpty()) {
            recordLedger(datasourceId, dsName, changeText, impacts);
        }
        int notified = notifyOwners(dsName, changeText, impacts);

        saveSnapshot(snap, datasourceId, current);
        return new WatchResult(datasourceId, false, summaries, briefs(impacts), notified,
                impacts.isEmpty() ? "有删除变更，但未发现受影响资产" : null);
    }

    /** 命中明细 → 前端投影（去掉资产全文）。 */
    private static List<ImpactBrief> briefs(List<SchemaImpactScanner.Impact> impacts) {
        List<ImpactBrief> out = new ArrayList<>();
        for (SchemaImpactScanner.Impact im : impacts) {
            out.add(new ImpactBrief(im.asset().type(), im.asset().id(), im.asset().name(), im.brokenRefs()));
        }
        return List.copyOf(out);
    }

    // ==================== 台账 ====================

    private void recordLedger(Long datasourceId, String dsName, String changeText,
                              List<SchemaImpactScanner.Impact> impacts) {
        List<GovernanceIssue> issues = new ArrayList<>();
        for (SchemaImpactScanner.Impact im : impacts) {
            String refs = String.join("；", im.brokenRefs());
            String kind = "SAVED_QUERY".equals(im.asset().type()) ? "查询模板" : "指标";
            issues.add(new GovernanceIssue(GovernanceSignal.META_SCHEMA_CHANGED,
                    "数据源「" + dsName + "」结构变更：" + changeText,
                    "WATCH|" + im.asset().type() + "|" + im.asset().id(),
                    1, SKILL_TAG, refs,
                    "受影响" + kind + "「" + im.asset().name() + "」引用的表/列已被删除，请校对并更新其 SQL",
                    null, false,
                    Map.of("datasourceId", datasourceId,
                            "assetType", im.asset().type(), "assetId", im.asset().id()),
                    null, "OPEN", null, null));
        }
        try {
            ledger.upsertSeen(issues, datasourceId);
        } catch (RuntimeException e) {
            // 台账失败不拦快照滚动与站内信（下次感知会重新 diff 出同一批变更）
            log.warn("结构变更台账入账失败 ds={}: {}", datasourceId, e.getMessage());
        }
    }

    /** METRIC 受影响资产顺带反查挂它的启用预警规则（明细追加"被预警规则X引用"）。 */
    private void attachAlertRuleHints(List<SchemaImpactScanner.Impact> impacts) {
        for (int i = 0; i < impacts.size(); i++) {
            SchemaImpactScanner.Impact im = impacts.get(i);
            if (!"METRIC".equals(im.asset().type()) || im.asset().id() == null) {
                continue;
            }
            List<AlertRule> rules = alertRuleMapper.selectList(new QueryWrapper<AlertRule>()
                    .eq("metric_key", METRIC_KEY_PREFIX + im.asset().id()).eq("enabled", true));
            if (rules.isEmpty()) {
                continue;
            }
            List<String> refs = new ArrayList<>(im.brokenRefs());
            for (AlertRule r : rules) {
                refs.add("被预警规则「" + r.getName() + "」引用");
            }
            impacts.set(i, new SchemaImpactScanner.Impact(im.asset(), List.copyOf(refs)));
        }
    }

    // ==================== 站内信（定向受影响资产创建人，fail-soft） ====================

    private int notifyOwners(String dsName, String changeText,
                             List<SchemaImpactScanner.Impact> impacts) {
        Map<Long, List<SchemaImpactScanner.Impact>> byUid = new LinkedHashMap<>();
        for (SchemaImpactScanner.Impact im : impacts) {
            if (im.asset().ownerUid() != null) {
                byUid.computeIfAbsent(im.asset().ownerUid(), k -> new ArrayList<>()).add(im);
            }
        }
        int written = 0;
        for (Map.Entry<Long, List<SchemaImpactScanner.Impact>> e : byUid.entrySet()) {
            try {
                StringBuilder content = new StringBuilder("数据源「").append(dsName)
                        .append("」结构发生变更：").append(changeText).append("。受影响资产：");
                for (SchemaImpactScanner.Impact im : e.getValue()) {
                    String kind = "SAVED_QUERY".equals(im.asset().type()) ? "查询模板" : "指标";
                    content.append("【").append(kind).append("】").append(im.asset().name())
                            .append("（").append(String.join("；", im.brokenRefs())).append("） ");
                }
                content.append("，请尽快校对相关 SQL。");
                NotifyMessage m = new NotifyMessage();
                m.setTenantId(com.aibi.common.tenant.TenantContext.tenantId());
                m.setUid(e.getKey());
                m.setSeverity("WARN");
                m.setTitle("【结构变更预警】" + dsName);
                m.setContent(truncate(content.toString(), 2000));
                m.setIsRead(false);
                m.setCreatedAt(LocalDateTime.now());
                notifyMessageMapper.insert(m);
                written++;
            } catch (RuntimeException ex) {
                log.warn("结构变更站内信失败 uid={}: {}", e.getKey(), ex.getMessage());
            }
        }
        return written;
    }

    // ==================== 快照读写 ====================

    private void saveSnapshot(SchemaSnapshot snap, Long datasourceId, Map<String, Set<String>> current) {
        int cols = 0;
        for (Set<String> c : current.values()) {
            cols += c.size();
        }
        try {
            String json = objectMapper.writeValueAsString(toSorted(current));
            if (snap == null) {
                SchemaSnapshot s = new SchemaSnapshot();
                s.setDatasourceId(datasourceId);
                s.setSchemaJson(json);
                s.setTableCount(current.size());
                s.setColCount(cols);
                s.setCreatedAt(LocalDateTime.now());
                snapshotMapper.insert(s);
            } else {
                snap.setSchemaJson(json);
                snap.setTableCount(current.size());
                snap.setColCount(cols);
                snapshotMapper.updateById(snap);
            }
        } catch (Exception e) {
            // 序列化/落库失败=基准无法滚动：抛出给端点报错（下次感知仍从旧基准 diff，不漏报）
            throw new BizException(ErrorCode.SYSTEM_ERROR, "结构快照保存失败：" + e.getMessage());
        }
    }

    private Map<String, Set<String>> load(SchemaSnapshot snap) {
        try {
            Map<String, List<String>> raw = objectMapper.readValue(
                    snap.getSchemaJson(), new TypeReference<Map<String, List<String>>>() {
                    });
            Map<String, Set<String>> out = new LinkedHashMap<>();
            raw.forEach((t, cols) -> out.put(t, new LinkedHashSet<>(cols)));
            return out;
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "结构快照解析失败，请重新建立基准：" + e.getMessage());
        }
    }

    // ==================== 辅助 ====================

    /** TableMeta 列表 → 「小写表名 → 小写列名集合」索引。 */
    static Map<String, Set<String>> index(List<TableMeta> schema) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (TableMeta t : schema) {
            if (t == null || t.name() == null || t.name().isBlank()) {
                continue;
            }
            String table = com.aibi.common.sql.SqlTableExtractor.bareName(t.name());
            Set<String> cols = out.computeIfAbsent(table, k -> new LinkedHashSet<>());
            for (TableMeta.ColumnMeta c : t.columns()) {
                if (c != null && c.name() != null && !c.name().isBlank()) {
                    cols.add(c.name().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** 变更事件 → 人话摘要（含改名启发提示）。 */
    static List<String> summarize(List<SchemaChangeDiff.Change> changes) {
        List<String> out = new ArrayList<>();
        Map<String, List<String[]>> hints = SchemaChangeDiff.renameHints(changes);
        for (SchemaChangeDiff.Change c : changes) {
            switch (c.kind()) {
                case TABLE_ADDED -> out.add("新增表 " + c.table());
                case TABLE_DROPPED -> out.add("删除表 " + c.table());
                case COLUMN_ADDED -> out.add("表 " + c.table() + " 新增列 " + c.column());
                case COLUMN_DROPPED -> {
                    String hint = hints.getOrDefault(c.table(), List.of()).stream()
                            .filter(p -> p[0].equals(c.column()))
                            .map(p -> "（同表新增列 " + p[1] + "，可能为改名）")
                            .findFirst().orElse("");
                    out.add("表 " + c.table() + " 删除列 " + c.column() + hint);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> toSorted(Map<String, Set<String>> m) {
        Map<String, Object> out = new TreeMap<>();
        m.forEach((t, cols) -> out.put(t, new TreeSet<>(cols)));
        return out;
    }

    private String dataSourceName(Long datasourceId) {
        try {
            DataSource ds = dataSourceService.getById(datasourceId);
            return ds != null && ds.getName() != null ? ds.getName() : ("#" + datasourceId);
        } catch (RuntimeException e) {
            return "#" + datasourceId;
        }
    }

    private static String joinText(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + "\n" + b;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
