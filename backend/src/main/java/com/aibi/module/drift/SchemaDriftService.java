package com.aibi.module.drift;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.dashboard.entity.Dashboard;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.DashboardMapper;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.rbac.entity.RoleFieldAcl;
import com.aibi.module.rbac.entity.RoleTableAcl;
import com.aibi.module.rbac.entity.UserFieldAcl;
import com.aibi.module.rbac.entity.UserTableAcl;
import com.aibi.module.rbac.mapper.RoleFieldAclMapper;
import com.aibi.module.rbac.mapper.RoleTableAclMapper;
import com.aibi.module.rbac.mapper.UserFieldAclMapper;
import com.aibi.module.rbac.mapper.UserTableAclMapper;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.entity.TableRelationDef;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.aibi.module.semantic.mapper.TableRelationDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SubSelect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结构对账服务（Schema Drift Detection）。
 *
 * <p><b>问题背景</b>：下游元数据（语义层 {@code semantic_meta} / 关联 {@code table_relation} / 字段&表级权限六表 /
 * 指标 SQL 与图表规格 / 驾驶舱 widget）全部靠「表名 + 列名」字符串引用实时 schema。物理库列被改名或删除后，
 * JDBC 元数据无法告知「哪一列改名叫哪一列」，故系统不会自动联动——这些引用会静默失配，表现为问数报
 * {@code Unknown column}、图表空白、权限失效但不报错。
 *
 * <p><b>本服务职责</b>：取数据源<b>当前全量 schema</b>（{@link DataSourceService#scanSchemaRaw} 直扫、不过 ACL、不写缓存），
 * 与五类下游引用做精确字符串比对，产出一份「失配清单」。检测只读、对取数链路零副作用。
 *
 * <p><b>比对约定</b>：表名取限定名末段小写、列名小写，与实时 schema 同口径归一（兼容 {@code db.table} 跨库写法）；
 * 中文标识符 SQL 解析不可靠，按 JoinGuard 哲学<b>跳过 SQL 级检测</b>（不误报，仅漏检）。
 * 语义行/关联/ACL 是结构化字符串，零误报；指标 SQL 用 JSqlParser 抽取表/列引用，仅在「引用了不存在的表/列」时判定坏引用。
 */
@Service
public class SchemaDriftService {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftService.class);

    private final DataSourceService dataSourceService;
    private final SemanticMetaMapper semanticMetaMapper;
    private final TableRelationDefMapper tableRelationDefMapper;
    private final UserFieldAclMapper userFieldAclMapper;
    private final UserTableAclMapper userTableAclMapper;
    private final RoleFieldAclMapper roleFieldAclMapper;
    private final RoleTableAclMapper roleTableAclMapper;
    private final MetricMapper metricMapper;
    private final DashboardMapper dashboardMapper;
    private final ObjectMapper objectMapper;

    public SchemaDriftService(DataSourceService dataSourceService,
                              SemanticMetaMapper semanticMetaMapper,
                              TableRelationDefMapper tableRelationDefMapper,
                              UserFieldAclMapper userFieldAclMapper,
                              UserTableAclMapper userTableAclMapper,
                              RoleFieldAclMapper roleFieldAclMapper,
                              RoleTableAclMapper roleTableAclMapper,
                              MetricMapper metricMapper,
                              DashboardMapper dashboardMapper,
                              ObjectMapper objectMapper) {
        this.dataSourceService = dataSourceService;
        this.semanticMetaMapper = semanticMetaMapper;
        this.tableRelationDefMapper = tableRelationDefMapper;
        this.userFieldAclMapper = userFieldAclMapper;
        this.userTableAclMapper = userTableAclMapper;
        this.roleFieldAclMapper = roleFieldAclMapper;
        this.roleTableAclMapper = roleTableAclMapper;
        this.metricMapper = metricMapper;
        this.dashboardMapper = dashboardMapper;
        this.objectMapper = objectMapper;
    }

    // ==================== 检测 ====================

    /**
     * 对指定数据源做全量结构对账，产出失配清单（只读）。
     *
     * @param datasourceId 数据源 id（租户归属由 {@link DataSourceService#scanSchemaRaw} 内部校验）
     */
    public DriftReport check(Long datasourceId) {
        List<TableMeta> schema = dataSourceService.scanSchemaRaw(datasourceId);
        SchemaIndex idx = new SchemaIndex(schema);

        // ① 语义层孤儿行
        List<OrphanSemantic> orphanSemantics = new ArrayList<>();
        for (SemanticMeta m : semanticMetaMapper.selectList(
                new QueryWrapper<SemanticMeta>().eq("datasource_id", datasourceId))) {
            boolean tableMissing = !idx.tableExists(m.getTableName());
            // 表级语义行（column_name 为空，如表别名）只对账表名；列级行才校验列存在性。
            // 否则 null 列名归一成空串去匹配列集合必失败，误报「已无列 [null]」。
            boolean colMissing = !tableMissing && !isBlank(m.getColumnName())
                    && !idx.colInTable(m.getTableName(), m.getColumnName());
            if (tableMissing || colMissing) {
                String reason = tableMissing
                        ? "表「" + m.getTableName() + "」已不存在"
                        : "表「" + m.getTableName() + "」中已无列「" + m.getColumnName() + "」";
                orphanSemantics.add(new OrphanSemantic(m.getId(), m.getTableName(), m.getColumnName(),
                        m.getAliasCn(), tableMissing, colMissing, reason));
            }
        }

        // ② 悬空关联（四个端点逐一比对）
        List<DanglingRelation> danglingRelations = new ArrayList<>();
        for (TableRelationDef r : tableRelationDefMapper.selectList(
                new QueryWrapper<TableRelationDef>().eq("datasource_id", datasourceId))) {
            List<String> problems = new ArrayList<>();
            checkEndpoint(idx, "左表", r.getLeftTable(), r.getLeftColumn(), problems);
            checkEndpoint(idx, "右表", r.getRightTable(), r.getRightColumn(), problems);
            if (!problems.isEmpty()) {
                danglingRelations.add(new DanglingRelation(r.getId(), r.getLeftTable(), r.getLeftColumn(),
                        r.getRightTable(), r.getRightColumn(), String.join("；", problems)));
            }
        }

        // ③ 悬空字段 & 表级权限（fail-open 降级点）
        List<DanglingAclItem> danglingAcls = new ArrayList<>();
        for (UserFieldAcl a : userFieldAclMapper.selectList(
                new QueryWrapper<UserFieldAcl>().eq("datasource_id", datasourceId))) {
            if (!idx.anyTableHasCol(a.getColumnName())) {
                danglingAcls.add(new DanglingAclItem(a.getId(), "USER", "FIELD", null,
                        a.getColumnName(), a.getEffect(), a.getUid()));
            }
        }
        for (UserTableAcl a : userTableAclMapper.selectList(
                new QueryWrapper<UserTableAcl>().eq("datasource_id", datasourceId))) {
            if (!idx.tableExists(a.getTableName())) {
                danglingAcls.add(new DanglingAclItem(a.getId(), "USER", "TABLE",
                        a.getTableName(), null, a.getEffect(), a.getUid()));
            }
        }
        for (RoleFieldAcl a : roleFieldAclMapper.selectList(
                new QueryWrapper<RoleFieldAcl>().eq("datasource_id", datasourceId))) {
            if (!idx.anyTableHasCol(a.getColumnName())) {
                danglingAcls.add(new DanglingAclItem(a.getId(), "ROLE", "FIELD", null,
                        a.getColumnName(), a.getEffect(), a.getCustomRoleId()));
            }
        }
        for (RoleTableAcl a : roleTableAclMapper.selectList(
                new QueryWrapper<RoleTableAcl>().eq("datasource_id", datasourceId))) {
            if (!idx.tableExists(a.getTableName())) {
                danglingAcls.add(new DanglingAclItem(a.getId(), "ROLE", "TABLE",
                        a.getTableName(), null, a.getEffect(), a.getCustomRoleId()));
            }
        }

        // ④ 指标坏引用（SQL 表/列 + 图表规格字段）
        List<BrokenMetric> brokenMetrics = new ArrayList<>();
        for (Metric m : metricMapper.selectList(
                new QueryWrapper<Metric>().eq("datasource_id", datasourceId))) {
            List<BrokenMetricRef> refs = new ArrayList<>();
            SqlRefs sqlRefs = extractSqlRefs(m.getSqlText());
            for (String tbl : sqlRefs.tables) {
                if (!idx.tableExists(tbl)) {
                    refs.add(new BrokenMetricRef(tbl, null, true));
                }
            }
            for (String qc : sqlRefs.qualifiedCols) {
                int at = qc.indexOf('@');
                String tbl = qc.substring(0, at);
                String col = qc.substring(at + 1);
                if (idx.tableExists(tbl) && !idx.colInTable(tbl, col)) {
                    refs.add(new BrokenMetricRef(tbl, col, false));
                }
            }
            collectChartSpecRefs(m.getChartSpec(), idx, refs);
            if (!refs.isEmpty()) {
                brokenMetrics.add(new BrokenMetric(m.getId(), m.getName(),
                        buildMetricReason(refs), refs));
            }
        }

        // ⑤ 受影响的驾驶舱（widget 引用了坏引用 / 已不存在的指标）
        Set<Long> brokenMetricIds = brokenMetrics.stream().map(BrokenMetric::id).collect(Collectors.toSet());
        Set<Long> allMetricIds = metricMapper.selectList(new QueryWrapper<Metric>())
                .stream().map(Metric::getId).collect(Collectors.toSet());
        List<AffectedDashboard> affectedDashboards = new ArrayList<>();
        for (Dashboard d : dashboardMapper.selectList(new QueryWrapper<Dashboard>())) {
            List<Long> broken = new ArrayList<>();
            List<Long> missing = new ArrayList<>();
            for (Long mid : parseWidgetMetricIds(d.getLayout())) {
                if (brokenMetricIds.contains(mid)) {
                    broken.add(mid);
                } else if (!allMetricIds.contains(mid)) {
                    missing.add(mid);
                }
            }
            if (!broken.isEmpty() || !missing.isEmpty()) {
                affectedDashboards.add(new AffectedDashboard(d.getId(), d.getName(), broken, missing));
            }
        }

        return new DriftReport(datasourceId, idx.tableCount(), idx.columnCount(),
                orphanSemantics, danglingRelations, danglingAcls, brokenMetrics, affectedDashboards);
    }

    /**
     * 一键清理孤儿元数据（语义行 / 关联 / 四类权限），仅删元数据、不动业务库。
     * 各清单为空/缺失表示该项不清理；删除由租户拦截器强制按 {@code tenant_id} 隔离。
     */
    @Transactional
    public Map<String, Integer> prune(DriftPruneRequest req) {
        if (req.datasourceId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "datasourceId 必填");
        }
        // 归属校验：数据源不存在 / 不属于本租户 → loadConfig 抛 DATASOURCE_NOT_FOUND
        dataSourceService.loadConfig(req.datasourceId());

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("semantic", deleteIds(semanticMetaMapper, req.semanticIds()));
        counts.put("relation", deleteIds(tableRelationDefMapper, req.relationIds()));
        counts.put("userFieldAcl", deleteIds(userFieldAclMapper, req.userFieldAclIds()));
        counts.put("userTableAcl", deleteIds(userTableAclMapper, req.userTableAclIds()));
        counts.put("roleFieldAcl", deleteIds(roleFieldAclMapper, req.roleFieldAclIds()));
        counts.put("roleTableAcl", deleteIds(roleTableAclMapper, req.roleTableAclIds()));
        log.info("结构对账一键清理 ds={}, 删除计数={}", req.datasourceId(), counts);
        return counts;
    }

    // ==================== 私有：端点 / 原因 ====================

    private void checkEndpoint(SchemaIndex idx, String label, String table, String column, List<String> problems) {
        if (isBlank(table)) {
            return;
        }
        if (!idx.tableExists(table)) {
            problems.add(label + "「" + table + "」已不存在");
            return;
        }
        if (!isBlank(column) && !idx.colInTable(table, column)) {
            problems.add(label + "「" + table + "." + column + "」列已不存在");
        }
    }

    private String buildMetricReason(List<BrokenMetricRef> refs) {
        List<String> parts = new ArrayList<>();
        for (BrokenMetricRef r : refs) {
            if (r.tableMissing()) {
                parts.add("引用了不存在的表「" + r.table() + "」");
            } else {
                parts.add("引用了不存在的列「" + r.table() + "." + r.column() + "」");
            }
        }
        return String.join("；", parts);
    }

    private static <T> int deleteIds(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> safe = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (safe.isEmpty()) {
            return 0;
        }
        return mapper.deleteBatchIds(safe);
    }

    // ==================== 私有：图表规格字段抽取 ====================

    /** 解析图表规格 JSON，对带「表.列」限定的字段做存在性校验（裸字段名=结果别名，跳过避免误报）。 */
    private void collectChartSpecRefs(String chartSpec, SchemaIndex idx, List<BrokenMetricRef> refs) {
        if (isBlank(chartSpec)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(chartSpec);
            if (node == null || node.isNull()) {
                return;
            }
            checkChartField(node.get("xField"), idx, refs);
            checkChartField(node.get("seriesField"), idx, refs);
            JsonNode yFields = node.get("yFields");
            if (yFields != null && yFields.isArray()) {
                for (JsonNode y : yFields) {
                    checkChartField(y, idx, refs);
                }
            }
        } catch (Exception e) {
            log.debug("图表规格解析失败，跳过图表字段级校验: {}", e.getMessage());
        }
    }

    private void checkChartField(JsonNode field, SchemaIndex idx, List<BrokenMetricRef> refs) {
        if (field == null || field.isNull() || field.asText().isBlank()) {
            return;
        }
        String f = field.asText().trim();
        int dot = f.lastIndexOf('.');
        if (dot <= 0) {
            return;   // 裸字段名（结果别名）→ 跳过
        }
        String tbl = f.substring(0, dot);
        String col = f.substring(dot + 1);
        if (!idx.tableExists(tbl)) {
            refs.add(new BrokenMetricRef(tbl, null, true));
        } else if (!idx.colInTable(tbl, col)) {
            refs.add(new BrokenMetricRef(tbl, col, false));
        }
    }

    // ==================== 私有：驾驶舱 widget 解析 ====================

    private List<Long> parseWidgetMetricIds(String layout) {
        List<Long> ids = new ArrayList<>();
        if (isBlank(layout)) {
            return ids;
        }
        try {
            JsonNode arr = objectMapper.readTree(layout);
            if (arr == null || !arr.isArray()) {
                return ids;
            }
            for (JsonNode w : arr) {
                JsonNode mid = w.get("metricId");
                if (mid != null && !mid.isNull() && mid.canConvertToLong()) {
                    ids.add(mid.asLong());
                }
            }
        } catch (Exception e) {
            log.debug("驾驶舱 layout 解析失败，跳过该驾驶舱: {}", e.getMessage());
        }
        return ids;
    }

    // ==================== 私有：JSqlParser 表/列抽取（镜像 JoinGuard 归一口径） ====================

    /**
     * 从指标 SQL 抽取引用的表（末段小写）与带限定的列（"表@列"），供失配比对。
     * 非纯 ASCII（中文标识符）或解析失败时返回空集（不误报）。
     */
    private static SqlRefs extractSqlRefs(String sql) {
        SqlRefs refs = new SqlRefs();
        if (isBlank(sql) || !isAscii(sql)) {
            return refs;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql.trim());
            if (!(statement instanceof Select)) {
                return refs;
            }
            SelectBody body = ((Select) statement).getSelectBody();
            if (!(body instanceof PlainSelect ps)) {
                return refs;   // UNION / 集合操作：各分支结构不同，跳过
            }
            Map<String, String> alias = new LinkedHashMap<>();
            String fromT = registerFrom(ps.getFromItem(), alias);
            if (fromT != null) {
                refs.tables.add(fromT);
            }
            if (ps.getJoins() != null) {
                for (Join j : ps.getJoins()) {
                    String rt = registerFrom(j.getRightItem(), alias);
                    if (rt != null) {
                        refs.tables.add(rt);
                    }
                    if (j.getOnExpressions() != null) {
                        for (Expression e : j.getOnExpressions()) {
                            collectColumns(e, alias, refs);
                        }
                    }
                }
            }
            if (ps.getSelectItems() != null) {
                for (SelectItem si : ps.getSelectItems()) {
                    collectColumns(selectItemExpr(si), alias, refs);
                }
            }
            collectColumns(ps.getWhere(), alias, refs);
            collectColumns(ps.getHaving(), alias, refs);
            GroupByElement groupBy = ps.getGroupBy();
            if (groupBy != null && groupBy.getGroupByExpressions() != null) {
                for (Expression e : groupBy.getGroupByExpressions()) {
                    collectColumns(e, alias, refs);
                }
            }
            if (ps.getOrderByElements() != null) {
                for (OrderByElement o : ps.getOrderByElements()) {
                    collectColumns(o.getExpression(), alias, refs);
                }
            }
        } catch (Exception e) {
            log.debug("指标 SQL 解析失败，跳过 SQL 级检测: {}", e.getMessage());
        }
        return refs;
    }

    private static Expression selectItemExpr(SelectItem si) {
        if (si instanceof SelectExpressionItem sei) {
            return sei.getExpression();
        }
        return null;
    }

    private static void collectColumns(Expression e, Map<String, String> alias, SqlRefs refs) {
        if (e == null) {
            return;
        }
        if (e instanceof Column c) {
            Table t = c.getTable();
            String col = lower(unquote(c.getColumnName()));
            if (t != null && !isBlank(t.getName())) {
                String nameOrAlias = lower(unquote(t.getName()));
                String real = alias.get(nameOrAlias);
                if (real != null) {
                    refs.qualifiedCols.add(real + "@" + col);
                } else {
                    // 直接以表名限定（未起别名）：按末段小写当真实表名
                    refs.qualifiedCols.add(shortLower(nameOrAlias) + "@" + col);
                }
            }
            // 无限定列名 → 无法归属到具体表，跳过（避免误报）
            return;
        }
        if (e instanceof Parenthesis p) {
            collectColumns(p.getExpression(), alias, refs);
            return;
        }
        if (e instanceof BinaryExpression b) {
            collectColumns(b.getLeftExpression(), alias, refs);
            collectColumns(b.getRightExpression(), alias, refs);
            return;
        }
        if (e instanceof Function f) {
            ExpressionList pl = f.getParameters();
            if (pl != null && pl.getExpressions() != null) {
                for (Expression ex : pl.getExpressions()) {
                    collectColumns(ex, alias, refs);
                }
            }
            return;
        }
        if (e instanceof SubSelect ss) {
            collectFromBody(ss.getSelectBody(), alias, refs);
            return;
        }
        if (e instanceof InExpression in) {
            collectColumns(in.getLeftExpression(), alias, refs);
            collectColumns(in.getRightExpression(), alias, refs);
            return;
        }
        if (e instanceof Between bet) {
            collectColumns(bet.getLeftExpression(), alias, refs);
            collectColumns(bet.getBetweenExpressionStart(), alias, refs);
            collectColumns(bet.getBetweenExpressionEnd(), alias, refs);
        }
    }

    private static void collectFromBody(SelectBody body, Map<String, String> alias, SqlRefs refs) {
        if (!(body instanceof PlainSelect ps)) {
            return;
        }
        registerFrom(ps.getFromItem(), alias);
        collectColumns(ps.getWhere(), alias, refs);
        if (ps.getSelectItems() != null) {
            for (SelectItem si : ps.getSelectItems()) {
                collectColumns(selectItemExpr(si), alias, refs);
            }
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                registerFrom(j.getRightItem(), alias);
                if (j.getOnExpressions() != null) {
                    for (Expression e : j.getOnExpressions()) {
                        collectColumns(e, alias, refs);
                    }
                }
            }
        }
    }

    private static String registerFrom(FromItem item, Map<String, String> alias) {
        if (!(item instanceof Table t)) {
            return null;   // 子查询 / 派生表：不参与比对
        }
        String real = shortLower(unquote(t.getName()));
        if (real.isEmpty()) {
            return null;
        }
        if (t.getAlias() != null && !isBlank(t.getAlias().getName())) {
            alias.put(lower(unquote(t.getAlias().getName())), real);
        }
        alias.putIfAbsent(real, real);
        return real;
    }

    // ==================== 小工具 ====================

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    private static String unquote(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"') || (first == '[' && last == ']')) {
                v = v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    private static String shortLower(String name) {
        String v = lower(name);
        int last = v.lastIndexOf('.');
        return last < 0 ? v : v.substring(last + 1);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ==================== 内部类型 ====================

    /** 实时 schema 的归一化索引：表名(末段小写) → 列名小写集合。 */
    private static class SchemaIndex {
        private final Map<String, Set<String>> colsByShort = new LinkedHashMap<>();
        private final Set<String> allTablesShort = new HashSet<>();
        private final Set<String> allCols = new HashSet<>();

        SchemaIndex(List<TableMeta> schema) {
            for (TableMeta t : schema) {
                if (t == null || t.name() == null) {
                    continue;
                }
                String shortName = shortLower(t.name());
                allTablesShort.add(shortName);
                Set<String> cols = colsByShort.computeIfAbsent(shortName, k -> new HashSet<>());
                if (t.columns() != null) {
                    for (ColumnMeta c : t.columns()) {
                        if (c != null && c.name() != null) {
                            cols.add(lower(c.name()));
                            allCols.add(lower(c.name()));
                        }
                    }
                }
            }
        }

        boolean tableExists(String name) {
            return !isBlank(name) && allTablesShort.contains(shortLower(name));
        }

        boolean colInTable(String table, String col) {
            Set<String> cols = colsByShort.get(shortLower(table));
            return cols != null && cols.contains(lower(col));
        }

        boolean anyTableHasCol(String col) {
            return !isBlank(col) && allCols.contains(lower(col));
        }

        int tableCount() {
            return allTablesShort.size();
        }

        int columnCount() {
            return allCols.size();
        }
    }

    /** JSqlParser 抽取结果（仅承载比对所需的表/列引用）。 */
    private static class SqlRefs {
        final Set<String> tables = new HashSet<>();
        final Set<String> qualifiedCols = new HashSet<>();
    }

    // ==================== 响应 DTO ====================

    public record OrphanSemantic(Long id, String tableName, String columnName, String aliasCn,
                                 boolean tableMissing, boolean columnMissing, String reason) {
    }

    public record DanglingRelation(Long id, String leftTable, String leftColumn,
                                   String rightTable, String rightColumn, String reason) {
    }

    public record DanglingAclItem(Long id, String target, String kind,
                                  String refTable, String refColumn, String effect, Long principalId) {
    }

    public record BrokenMetricRef(String table, String column, boolean tableMissing) {
    }

    public record BrokenMetric(Long id, String name, String reason, List<BrokenMetricRef> refs) {
    }

    public record AffectedDashboard(Long id, String name, List<Long> brokenMetricIds, List<Long> missingMetricIds) {
    }

    public record DriftReport(Long datasourceId, int schemaTableCount, int schemaColumnCount,
                              List<OrphanSemantic> orphanSemantics,
                              List<DanglingRelation> danglingRelations,
                              List<DanglingAclItem> danglingAcls,
                              List<BrokenMetric> brokenMetrics,
                              List<AffectedDashboard> affectedDashboards) {
    }
}
