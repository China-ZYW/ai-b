package com.aibi.module.lineage;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dashboard.entity.Dashboard;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.DashboardMapper;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.lineage.dto.DashboardLineageVO;
import com.aibi.module.lineage.dto.DatasourceLineageVO;
import com.aibi.module.lineage.dto.FieldImpactVO;
import com.aibi.module.lineage.dto.FieldReferenceVO;
import com.aibi.module.lineage.dto.LineageVO;
import com.aibi.module.lineage.entity.LineageEdge;
import com.aibi.module.lineage.mapper.LineageEdgeMapper;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据血缘服务（需求 §3.3）：指标 ↔ 字段 ↔ 表 ↔ 看板 的轻量关系网。
 *
 * <p><b>为什么是"写期解析 + 读期单表查"而不是实时反查</b>：指标口径、看板布局都是自由文本/JSON，
 * 实时反查要全表扫 + JSON 解析，语义管理翻页就会被拖垮。这里在指标保存时把引用关系解析成边
 * 固化下来，读期只按 id 查一张边表，负载可忽略。
 *
 * <p><b>字段级血缘怎么来的</b>：不做 SQL 语法树解析（重且易错），而是把指标口径表达式与已登记
 * 的语义字段做词边界匹配——命中即认定引用。语义层登记得越全，血缘越准；未登记的字段不会被
 * 误报（宁可少报，不可错报，避免血缘成为噪声）。
 *
 * <p><b>租户隔离</b>：所有查询显式带 {@code tenant_id}，不依赖拦截器表名单——名单漏配即跨租户泄漏。
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    /** 实体类型。 */
    public static final String TYPE_METRIC = "metric";
    public static final String TYPE_FIELD = "field";
    public static final String TYPE_TABLE = "table";
    public static final String TYPE_DASHBOARD = "dashboard";
    /** 关系。 */
    public static final String REL_DEPENDS_ON = "depends_on";
    public static final String REL_SOURCE = "source";
    public static final String REL_REFERENCES = "references";

    private final LineageEdgeMapper edgeMapper;
    private final MetricMapper metricMapper;
    private final DashboardMapper dashboardMapper;
    private final SemanticMetaMapper semanticMetaMapper;
    private final AibiConfig aibiConfig;

    public LineageService(LineageEdgeMapper edgeMapper,
                          MetricMapper metricMapper,
                          DashboardMapper dashboardMapper,
                          SemanticMetaMapper semanticMetaMapper,
                          AibiConfig aibiConfig) {
        this.edgeMapper = edgeMapper;
        this.metricMapper = metricMapper;
        this.dashboardMapper = dashboardMapper;
        this.semanticMetaMapper = semanticMetaMapper;
        this.aibiConfig = aibiConfig;
    }

    /**
     * 指标血缘卡：来源表/字段 + 口径表达式 + 被哪些看板引用。
     *
     * <p>尚未解析过（如指标刚沉淀口径）时自动补一次解析，保证页面永远有内容，
     * 不要求管理员先手工点"重建血缘"。
     */
    public LineageVO metricLineage(Long metricId) {
        requireEnabled();
        Metric metric = requireMetric(metricId);
        Long tenantId = requireTenant();

        List<LineageEdge> fieldEdges = edgesFrom(tenantId, TYPE_METRIC, metricId);
        if (fieldEdges.isEmpty()) {
            rebuildMetric(metricId);
            fieldEdges = edgesFrom(tenantId, TYPE_METRIC, metricId);
        }

        List<Long> fieldIds = new ArrayList<>();
        for (LineageEdge e : fieldEdges) {
            fieldIds.add(e.getToId());
        }
        Map<Long, SemanticMeta> metaMap = metasOf(metric.getDatasourceId());

        // 按表聚合字段：血缘卡要回答"数据从哪来"，表是业务人员的第一视角
        Map<String, List<LineageVO.FieldItem>> grouped = new LinkedHashMap<>();
        for (Long fid : fieldIds) {
            SemanticMeta m = metaMap.get(fid);
            if (m == null) {
                continue;
            }
            String table = m.getTableName() == null ? "（未登记表）" : m.getTableName();
            grouped.computeIfAbsent(table, k -> new ArrayList<>()).add(new LineageVO.FieldItem(
                    m.getId(),
                    m.getColumnName(),
                    m.getAliasCn() != null && !m.getAliasCn().isBlank() ? m.getAliasCn() : m.getColumnName(),
                    m.getColumnRole(),
                    m.getSensitive()));
        }
        List<LineageVO.SourceItem> sources = new ArrayList<>(grouped.size());
        grouped.forEach((table, fields) -> sources.add(new LineageVO.SourceItem(table, fields)));

        List<LineageVO.RefItem> referencedBy = dashboardsReferencing(tenantId, metricId);
        return new LineageVO(
                metric.getId(),
                metric.getAlias() != null && !metric.getAlias().isBlank() ? metric.getAlias() : metric.getName(),
                metric.getDefinition(),
                sources,
                referencedBy,
                referencedBy.size());
    }

    /** 字段被哪些指标引用（语义管理「被引用指标」列与浮层的数据源）。 */
    public FieldReferenceVO fieldReferences(Long fieldId) {
        requireEnabled();
        Long tenantId = requireTenant();
        SemanticMeta meta = semanticMetaMapper.selectById(fieldId);
        if (meta == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "语义字段不存在：" + fieldId);
        }

        LambdaQueryWrapper<LineageEdge> q = new LambdaQueryWrapper<>();
        q.eq(LineageEdge::getTenantId, tenantId);
        q.eq(LineageEdge::getToType, TYPE_FIELD);
        q.eq(LineageEdge::getToId, fieldId);
        q.eq(LineageEdge::getRelation, REL_DEPENDS_ON);
        List<LineageEdge> edges = edgeMapper.selectList(q);

        List<LineageVO.RefItem> metrics = new ArrayList<>();
        for (LineageEdge e : edges) {
            Metric m = metricMapper.selectById(e.getFromId());
            if (m == null) {
                continue;
            }
            metrics.add(new LineageVO.RefItem(m.getId(),
                    m.getAlias() != null && !m.getAlias().isBlank() ? m.getAlias() : m.getName(),
                    TYPE_METRIC, null));
        }
        return new FieldReferenceVO(
                meta.getId(),
                meta.getTableName(),
                meta.getColumnName(),
                meta.getAliasCn() != null && !meta.getAliasCn().isBlank() ? meta.getAliasCn() : meta.getColumnName(),
                metrics,
                metrics.size());
    }

    /**
     * 语义字段变更的影响面（P1「血缘变更影响提示」）。
     *
     * <p>修改字段别名 / 口径 / 列角色前调用：字段 → 引用它的指标 → 引用这些指标的看板。
     * 影响面为 0 时可直接保存；非 0 时前端应弹窗让用户确认后再保存。
     *
     * <p>血缘边未生成（指标从未重建过）时，退化为空影响面——宁可少报不误报，
     * 因为误报会让管理员频繁被无意义弹窗打断。
     *
     * @param datasourceId 数据源 id
     * @param tableName    物理表名
     * @param columnName   物理列名
     */
    public FieldImpactVO fieldImpact(Long datasourceId, String tableName, String columnName) {
        requireEnabled();
        Long tenantId = requireTenant();
        if (isBlank(tableName) || isBlank(columnName)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "表名与列名不能为空");
        }
        SemanticMeta meta = findMeta(datasourceId, tableName, columnName);

        List<LineageVO.RefItem> metrics = new ArrayList<>();
        List<LineageVO.RefItem> dashboards = new ArrayList<>();
        if (meta != null) {
            LambdaQueryWrapper<LineageEdge> q = new LambdaQueryWrapper<>();
            q.eq(LineageEdge::getTenantId, tenantId);
            q.eq(LineageEdge::getToType, TYPE_FIELD);
            q.eq(LineageEdge::getToId, meta.getId());
            q.eq(LineageEdge::getRelation, REL_DEPENDS_ON);
            for (LineageEdge e : edgeMapper.selectList(q)) {
                Metric m = metricMapper.selectById(e.getFromId());
                if (m == null) {
                    continue;
                }
                String label = m.getAlias() != null && !m.getAlias().isBlank() ? m.getAlias() : m.getName();
                metrics.add(new LineageVO.RefItem(m.getId(), label, TYPE_METRIC, null));
                for (LineageVO.RefItem d : dashboardsReferencing(tenantId, m.getId())) {
                    if (dashboards.stream().noneMatch(x -> x.id().equals(d.id()))) {
                        dashboards.add(d);
                    }
                }
            }
        }
        return new FieldImpactVO(
                meta == null ? null : meta.getId(),
                tableName,
                columnName,
                meta != null && meta.getAliasCn() != null && !meta.getAliasCn().isBlank()
                        ? meta.getAliasCn()
                        : columnName,
                metrics,
                dashboards,
                metrics.size() + dashboards.size());
    }

    /**
     * 看板血缘卡（P1）：看板 → 引用的指标 → 指标追溯到的来源表与字段。
     *
     * <p>方向与 {@link #metricLineage(Long)} 相反：指标血缘自下而上回答"影响谁"，
     * 看板血缘自上而下回答"数据从哪来"。审计一张对外报表是否引用敏感字段、
     * 或是否依赖了口径已变更的指标时，必须从看板往下展开——逐个指标反查做不到。
     *
     * <p>指标引用按 layout JSON 的 {@code "metricId"} 词法解析，与
     * {@link #dashboardsReferencing} 同一口径，保证"指标→看板"与"看板→指标"互为逆运算。
     * 多个指标共用同一字段时，来源字段按字段 id 去重（血缘卡回答"涉及哪些列"，重复列无意义）。
     */
    public DashboardLineageVO dashboardLineage(Long dashboardId) {
        requireEnabled();
        Long tenantId = requireTenant();

        LambdaQueryWrapper<Dashboard> dq = new LambdaQueryWrapper<>();
        dq.eq(Dashboard::getTenantId, tenantId);
        dq.eq(Dashboard::getId, dashboardId);
        Dashboard dashboard = dashboardMapper.selectOne(dq);
        if (dashboard == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "看板不存在：" + dashboardId);
        }

        List<Long> metricIds = metricIdsOf(dashboard.getLayout());
        List<LineageVO.RefItem> metrics = new ArrayList<>();
        Map<String, List<LineageVO.FieldItem>> grouped = new LinkedHashMap<>();
        Set<Long> seenFields = new HashSet<>();

        for (Long mid : metricIds) {
            Metric m = metricMapper.selectById(mid);
            if (m == null) {
                continue;
            }
            metrics.add(new LineageVO.RefItem(m.getId(),
                    m.getAlias() != null && !m.getAlias().isBlank() ? m.getAlias() : m.getName(),
                    TYPE_METRIC, null));

            List<LineageEdge> fieldEdges = edgesFrom(tenantId, TYPE_METRIC, m.getId());
            if (fieldEdges.isEmpty()) {
                // 与指标血缘一致：尚未解析过则补一次，保证页面永远有内容
                rebuildMetric(m.getId());
                fieldEdges = edgesFrom(tenantId, TYPE_METRIC, m.getId());
            }
            Map<Long, SemanticMeta> metaMap = metasOf(m.getDatasourceId());
            for (LineageEdge e : fieldEdges) {
                if (!TYPE_FIELD.equals(e.getToType()) || e.getToId() == null) {
                    continue;
                }
                if (!seenFields.add(e.getToId())) {
                    continue;
                }
                SemanticMeta meta = metaMap.get(e.getToId());
                if (meta == null) {
                    continue;
                }
                String table = isBlank(meta.getTableName()) ? "（未登记表）" : meta.getTableName();
                grouped.computeIfAbsent(table, k -> new ArrayList<>()).add(new LineageVO.FieldItem(
                        meta.getId(),
                        meta.getColumnName(),
                        !isBlank(meta.getAliasCn()) ? meta.getAliasCn() : meta.getColumnName(),
                        meta.getColumnRole(),
                        meta.getSensitive()));
            }
        }

        List<LineageVO.SourceItem> sources = new ArrayList<>(grouped.size());
        grouped.forEach((table, fields) -> sources.add(new LineageVO.SourceItem(table, fields)));
        return new DashboardLineageVO(dashboard.getId(), dashboard.getName(), dashboard.getIsScreen(),
                metrics, sources, metrics.size());
    }

    /** 从看板 layout JSON 解析引用的指标 id（去重、保序）；非法数字字面量跳过。 */
    private List<Long> metricIdsOf(String layout) {
        List<Long> ids = new ArrayList<>();
        if (layout == null || layout.isBlank()) {
            return ids;
        }
        Matcher m = Pattern.compile("\"metricId\"\\s*:\\s*(\\d+)").matcher(layout);
        while (m.find()) {
            try {
                long v = Long.parseLong(m.group(1));
                if (ids.stream().noneMatch(x -> x.longValue() == v)) {
                    ids.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 非法字面量跳过
            }
        }
        return ids;
    }

    private SemanticMeta findMeta(Long datasourceId, String tableName, String columnName) {
        LambdaQueryWrapper<SemanticMeta> q = new LambdaQueryWrapper<>();
        q.eq(SemanticMeta::getDatasourceId, datasourceId);
        q.eq(SemanticMeta::getTableName, tableName);
        q.eq(SemanticMeta::getColumnName, columnName);
        q.last("LIMIT 1");
        return semanticMetaMapper.selectOne(q);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 重建某指标的血缘边（指标保存 / 口径变更后调用）。
     *
     * <p>先删后插，天然幂等：同一指标无论被保存多少次，边表都不会堆积重复记录。
     */
    public int rebuildMetric(Long metricId) {
        Metric metric = requireMetric(metricId);
        Long tenantId = requireTenant();

        LambdaQueryWrapper<LineageEdge> del = new LambdaQueryWrapper<>();
        del.eq(LineageEdge::getTenantId, tenantId);
        del.eq(LineageEdge::getFromType, TYPE_METRIC);
        del.eq(LineageEdge::getFromId, metricId);
        edgeMapper.delete(del);

        String text = (metric.getDefinition() == null ? "" : metric.getDefinition())
                + " " + (metric.getSqlText() == null ? "" : metric.getSqlText());
        if (text.isBlank()) {
            return 0;
        }

        Map<Long, SemanticMeta> metaMap = metasOf(metric.getDatasourceId());
        String metricName = metric.getAlias() != null && !metric.getAlias().isBlank()
                ? metric.getAlias() : metric.getName();
        int count = 0;
        for (SemanticMeta meta : metaMap.values()) {
            String column = meta.getColumnName();
            if (column == null || column.isBlank() || !containsIdentifier(text, column)) {
                continue;
            }
            String fieldLabel = meta.getAliasCn() != null && !meta.getAliasCn().isBlank()
                    ? meta.getAliasCn() : column;
            edgeMapper.insert(edge(tenantId, TYPE_METRIC, metricId, metricName,
                    TYPE_FIELD, meta.getId(), fieldLabel, REL_DEPENDS_ON));
            count++;
            if (meta.getTableName() != null && !meta.getTableName().isBlank()) {
                edgeMapper.insert(edge(tenantId, TYPE_FIELD, meta.getId(), fieldLabel,
                        TYPE_TABLE, null, meta.getTableName(), REL_SOURCE));
                count++;
            }
        }
        log.info("血缘重建 指标[{}] 边数[{}]", metricId, count);
        return count;
    }

    /** 重建某数据源下所有指标的血缘（语义层批量标记后的一次性刷新）。 */
    public int rebuildByDatasource(Long datasourceId) {
        requireEnabled();
        Long tenantId = requireTenant();
        LambdaQueryWrapper<Metric> q = new LambdaQueryWrapper<>();
        q.eq(Metric::getTenantId, tenantId);
        q.eq(Metric::getDatasourceId, datasourceId);
        List<Metric> metrics = metricMapper.selectList(q);
        int total = 0;
        for (Metric m : metrics) {
            total += rebuildMetric(m.getId());
        }
        return total;
    }

    // ---------------- 内部工具 ----------------

    private void requireEnabled() {
        if (!aibiConfig.lineageEnabled()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "血缘模块已关闭，请联系管理员开启");
        }
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "缺少租户上下文，无法读取血缘");
        }
        return tenantId;
    }

    private Metric requireMetric(Long id) {
        Metric metric = metricMapper.selectById(id);
        if (metric == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "指标不存在：" + id);
        }
        return metric;
    }

    private List<LineageEdge> edgesFrom(Long tenantId, String fromType, Long fromId) {
        LambdaQueryWrapper<LineageEdge> q = new LambdaQueryWrapper<>();
        q.eq(LineageEdge::getTenantId, tenantId);
        q.eq(LineageEdge::getFromType, fromType);
        q.eq(LineageEdge::getFromId, fromId);
        return edgeMapper.selectList(q);
    }

    /** 某数据源已登记的语义字段（id → 元数据）。 */
    private Map<Long, SemanticMeta> metasOf(Long datasourceId) {
        LambdaQueryWrapper<SemanticMeta> q = new LambdaQueryWrapper<>();
        q.eq(SemanticMeta::getDatasourceId, datasourceId);
        List<SemanticMeta> metas = semanticMetaMapper.selectList(q);
        Map<Long, SemanticMeta> map = new LinkedHashMap<>();
        for (SemanticMeta m : metas) {
            map.put(m.getId(), m);
        }
        return map;
    }

    /** 引用了该指标的看板（layout JSON 里带该 metricId）。 */
    private List<LineageVO.RefItem> dashboardsReferencing(Long tenantId, Long metricId) {
        return dashboardsReferencingBatch(tenantId, Set.of(metricId)).getOrDefault(metricId, List.of());
    }

    /**
     * 批量计算"哪些看板引用了这些指标"：一次取出租户内布局含 metricId 的看板，
     * 在内存里用正则按各 metricId 匹配，避免 N 个指标触发 N 次全表扫看板。
     */
    private Map<Long, List<LineageVO.RefItem>> dashboardsReferencingBatch(Long tenantId, Set<Long> metricIds) {
        Map<Long, List<LineageVO.RefItem>> result = new LinkedHashMap<>();
        if (metricIds.isEmpty()) {
            return result;
        }
        LambdaQueryWrapper<Dashboard> q = new LambdaQueryWrapper<>();
        q.eq(Dashboard::getTenantId, tenantId);
        q.like(Dashboard::getLayout, "metricId");
        List<Dashboard> dashboards = dashboardMapper.selectList(q);
        Map<Long, Pattern> patterns = new LinkedHashMap<>();
        for (Long mid : metricIds) {
            patterns.put(mid, Pattern.compile("\"metricId\"\\s*:\\s*" + mid + "\\b"));
        }
        for (Dashboard d : dashboards) {
            if (d.getLayout() == null) {
                continue;
            }
            for (Map.Entry<Long, Pattern> en : patterns.entrySet()) {
                if (en.getValue().matcher(d.getLayout()).find()) {
                    result.computeIfAbsent(en.getKey(), k -> new ArrayList<>())
                            .add(new LineageVO.RefItem(d.getId(), d.getName(), TYPE_DASHBOARD, d.getIsScreen()));
                }
            }
        }
        return result;
    }

    /**
     * 数据源级血缘总览（问数调优「数据血缘」tab）：该数据源下所有 COLUMN 字段 → 各自被哪些指标引用 →
     * 这些指标又进了哪些看板。一次查询聚合完成，避免前端对字段逐个调 {@code /field/{id}/references} 形成 N+1。
     *
     * <p>只统计 COLUMN 类型字段（表字段才参与指标口径词边界匹配，与 {@link #fieldReferences} 口径一致）。
     */
    public DatasourceLineageVO datasourceLineage(Long datasourceId) {
        requireEnabled();
        Long tenantId = requireTenant();
        Map<Long, SemanticMeta> metaMap = metasOf(datasourceId);

        List<SemanticMeta> columns = new ArrayList<>();
        for (SemanticMeta m : metaMap.values()) {
            if ("COLUMN".equals(m.getObjectType())) {
                columns.add(m);
            }
        }

        List<Long> fieldIds = new ArrayList<>();
        for (SemanticMeta c : columns) {
            fieldIds.add(c.getId());
        }

        Map<Long, List<Long>> fieldToMetrics = new LinkedHashMap<>();
        if (!fieldIds.isEmpty()) {
            LambdaQueryWrapper<LineageEdge> q = new LambdaQueryWrapper<>();
            q.eq(LineageEdge::getTenantId, tenantId);
            q.eq(LineageEdge::getFromType, TYPE_METRIC);
            q.eq(LineageEdge::getToType, TYPE_FIELD);
            q.eq(LineageEdge::getRelation, REL_DEPENDS_ON);
            q.in(LineageEdge::getToId, fieldIds);
            for (LineageEdge e : edgeMapper.selectList(q)) {
                fieldToMetrics.computeIfAbsent(e.getToId(), k -> new ArrayList<>()).add(e.getFromId());
            }
        }

        Set<Long> metricIdSet = new HashSet<>();
        for (List<Long> ids : fieldToMetrics.values()) {
            metricIdSet.addAll(ids);
        }
        Map<Long, Metric> metricMap = new LinkedHashMap<>();
        if (!metricIdSet.isEmpty()) {
            for (Metric m : metricMapper.selectBatchIds(new ArrayList<>(metricIdSet))) {
                metricMap.put(m.getId(), m);
            }
        }
        Map<Long, List<LineageVO.RefItem>> metricDashboards = dashboardsReferencingBatch(tenantId, metricIdSet);

        List<DatasourceLineageVO.FieldLineageItem> items = new ArrayList<>();
        for (SemanticMeta col : columns) {
            List<Long> mids = fieldToMetrics.getOrDefault(col.getId(), List.of());
            List<DatasourceLineageVO.MetricRefItem> metricItems = new ArrayList<>();
            for (Long mid : mids) {
                Metric m = metricMap.get(mid);
                if (m == null) {
                    continue;
                }
                String name = m.getAlias() != null && !m.getAlias().isBlank() ? m.getAlias() : m.getName();
                metricItems.add(new DatasourceLineageVO.MetricRefItem(
                        mid, name, metricDashboards.getOrDefault(mid, List.of())));
            }
            items.add(new DatasourceLineageVO.FieldLineageItem(
                    col.getId(),
                    isBlank(col.getTableName()) ? null : col.getTableName(),
                    col.getColumnName(),
                    !isBlank(col.getAliasCn()) ? col.getAliasCn() : col.getColumnName(),
                    col.getColumnRole(),
                    col.getSensitive(),
                    metricItems));
        }
        return new DatasourceLineageVO(datasourceId, items.size(), items);
    }

    /** 词边界匹配：避免 "amount" 命中 "total_amount" 这类误报。 */
    private boolean containsIdentifier(String text, String identifier) {
        try {
            return Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(identifier) + "(?![A-Za-z0-9_])")
                    .matcher(text).find();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private LineageEdge edge(Long tenantId, String fromType, Long fromId, String fromName,
                             String toType, Long toId, String toName, String relation) {
        LineageEdge e = new LineageEdge();
        e.setTenantId(tenantId);
        e.setFromType(fromType);
        e.setFromId(fromId);
        e.setFromName(fromName);
        e.setToType(toType);
        e.setToId(toId);
        e.setToName(toName);
        e.setRelation(relation);
        e.setCreatedAt(java.time.LocalDateTime.now());
        return e;
    }
}
