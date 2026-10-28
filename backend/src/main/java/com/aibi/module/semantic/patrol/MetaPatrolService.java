package com.aibi.module.semantic.patrol;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.chat.distill.GovernanceIssue;
import com.aibi.module.chat.distill.GovernanceIssueLedgerService;
import com.aibi.module.chat.distill.GovernanceSignal;
import com.aibi.module.chat.nl2sql.SchemaProvider;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.entity.DataSource;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 元数据巡检编排（T11-4）：解析数据源 → 拉 schema/语义行/指标 → {@link MetaPatrolScanner} 纯规则判定 →
 * 命中行经 {@link GovernanceIssueLedgerService#upsertSeen} 进 T11-1 既有台账（幂等、处置态不回退、推送去重全复用）。
 *
 * <p><b>护栏</b>：单次最多扫 {@link #MAX_DATASOURCES} 个数据源（超出截断并 note）；
 * 单 ds 的 schema 拉取失败（数据源连不上）跳过该 ds 计数不中断整体（静默降级实践）；
 * 投影 issues cap {@link #ISSUE_CAP} 条防响应爆长（台账不受 cap 影响，全量落）。
 *
 * <p><b>边界</b>：只读元数据 + 缓存 schema（{@link SchemaProvider} 走 DataMaskGate 过滤后表集），
 * 绝不查业务表数据、零 LLM；扫描范围由租户拦截器硬约束。门控 {@code METRIC / meta-patrol}（默认开）。
 */
@Service
public class MetaPatrolService {

    private static final Logger log = LoggerFactory.getLogger(MetaPatrolService.class);

    /** 单次巡检数据源上限（设计稿 §5.1：防大租户一把扫穿）。 */
    public static final int MAX_DATASOURCES = 5;

    /** 响应投影条数上限（台账落库不截断）。 */
    public static final int ISSUE_CAP = 100;

    private final DataSourceService dataSourceService;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SchemaProvider schemaProvider;
    private final SemanticMetaMapper semanticMetaMapper;
    private final MetricMapper metricMapper;
    private final GovernanceIssueLedgerService ledger;

    public MetaPatrolService(DataSourceService dataSourceService,
                             DataSourceAdapterRegistry adapterRegistry,
                             SchemaProvider schemaProvider,
                             SemanticMetaMapper semanticMetaMapper,
                             MetricMapper metricMapper,
                             GovernanceIssueLedgerService ledger) {
        this.dataSourceService = dataSourceService;
        this.adapterRegistry = adapterRegistry;
        this.schemaProvider = schemaProvider;
        this.semanticMetaMapper = semanticMetaMapper;
        this.metricMapper = metricMapper;
        this.ledger = ledger;
    }

    /**
     * 立即巡检：指定 ds 只扫那一个；空则扫本租户启用数据源（前 {@link #MAX_DATASOURCES} 个）。
     *
     * @param datasourceId 数据源 id（可空）
     * @return 巡检摘要 + 本次命中投影（已同步 upsert 进台账）
     */
    public MetaPatrolResult patrol(Long datasourceId) {
        List<Long> dsIds = new ArrayList<>();
        String note = null;
        if (datasourceId != null) {
            dsIds.add(datasourceId); // 跨租户 id 由 loadConfig 抛 DATASOURCE_NOT_FOUND，不泄露存在性
        } else {
            List<DataSource> enabled = dataSourceService.list(new LambdaQueryWrapper<DataSource>()
                    .eq(DataSource::getStatus, 1).orderByAsc(DataSource::getId));
            if (enabled.size() > MAX_DATASOURCES) {
                note = "租户启用数据源 " + enabled.size() + " 个，本次仅扫前 " + MAX_DATASOURCES + " 个";
            }
            enabled.stream().limit(MAX_DATASOURCES).forEach(ds -> dsIds.add(ds.getId()));
        }

        List<GovernanceIssue> all = new ArrayList<>();
        int scanned = 0;
        int failed = 0;
        for (Long ds : dsIds) {
            try {
                all.addAll(scanOne(ds));
                scanned++;
            } catch (Exception e) {
                // schema/数据源不可达等：跳过该 ds，其余照常（静默降级）
                failed++;
                log.warn("元数据巡检跳过 ds={}: {}", ds, e.getMessage());
            }
        }

        // 命中全量进台账（幂等 upsert，jumpAnchor 自带 datasourceId）
        ledger.upsertSeen(all, datasourceId);

        int broken = count(all, GovernanceSignal.META_LINEAGE_BROKEN);
        int diffDef = count(all, GovernanceSignal.META_SAME_NAME_DIFF_DEF);
        int noComment = count(all, GovernanceSignal.META_NO_COMMENT);
        // 投影排序：信号优先级升（BROKEN>DIFFDEF>NO_COMMENT），同信号按出现数降
        List<GovernanceIssue> sorted = new ArrayList<>(all);
        sorted.sort(java.util.Comparator.comparingInt((GovernanceIssue i) -> i.signal().priority())
                .thenComparing(i -> -i.occurrences()));
        List<GovernanceIssue> top = sorted.size() > ISSUE_CAP ? List.copyOf(sorted.subList(0, ISSUE_CAP)) : sorted;
        return new MetaPatrolResult(scanned, failed, note, broken, diffDef, noComment, top);
    }

    /** 扫一个数据源：schema（缓存+ACL 过滤后）→ 语义行 + 指标 → 纯规则判定。schema 拉不到抛异常由上层计入 failed。 */
    private List<GovernanceIssue> scanOne(Long datasourceId) {
        DataSourceConfig config = dataSourceService.loadConfig(datasourceId);
        DataSourceAdapter adapter = adapterRegistry.get(config.type());
        List<TableMeta> tables = schemaProvider.getTables(adapter, config);
        if (tables == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "schema 不可用");
        }
        // 小写裸表名 → 小写列集（列集为空 = 拿不到列信息，Scanner 侧只判表存在性防误杀）
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            Set<String> cols = new LinkedHashSet<>();
            for (TableMeta.ColumnMeta c : t.columns()) {
                if (c != null && c.name() != null && !c.name().isBlank()) {
                    cols.add(c.name().trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (t.name() != null && !t.name().isBlank()) {
                schema.putIfAbsent(com.aibi.common.sql.SqlTableExtractor.bareName(t.name()), cols);
            }
        }
        List<SemanticMeta> metas = semanticMetaMapper.selectList(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId));
        List<Metric> metrics = metricMapper.selectList(new LambdaQueryWrapper<Metric>()
                .eq(Metric::getDatasourceId, datasourceId));
        return MetaPatrolScanner.scan(datasourceId, metas, metrics, schema);
    }

    private static int count(List<GovernanceIssue> issues, GovernanceSignal signal) {
        return (int) issues.stream().filter(i -> i.signal() == signal).count();
    }
}
