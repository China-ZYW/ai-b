package com.aibi.module.datasource.adapter;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.TenantDataSourceManager;
import com.aibi.module.datasource.cost.CostGuard;
import com.aibi.module.datasource.cost.CostVerdict;
import com.aibi.module.datasource.dialect.SqlDialect;
import com.aibi.module.datasource.dialect.SqlDialects;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.datasource.model.TableMeta.FkMeta;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 通用 JDBC 数据源适配器：一套代码覆盖关系型 + OLAP 全部类型，数据库差异全部下沉到 {@link SqlDialect}。
 *
 * <p>三重只读护栏：① 连接池 {@code readOnly=true}；② 方言 {@code applyLimit} 软限制；
 * ③ JDBC {@code setMaxRows + setQueryTimeout} 硬限制（与 SQL 语法无关，跨库通用）。
 * schema 扫描统一走 JDBC {@link DatabaseMetaData}，可移植性优于各库的 INFORMATION_SCHEMA。
 *
 * <p><b>跨库联合分析</b>：配了 {@code extraCatalogs} 时，除主库外还会扫这些库/模式（同一连接、
 * 同一个账号，因此只能联合同一服务器上有权限的库）。主库表名<b>不加前缀</b>，与升级前完全一致——
 * 已存指标的 SQL、语义层按表名的键均不受影响；额外库的表名则带上限定前缀（见 {@link #display}），
 * 使 LLM 写出的跨库 join 能直接执行。
 */
@Component
public class JdbcDataSourceAdapter implements DataSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataSourceAdapter.class);

    /** 单次扫描的最大表数（主库 + 全部额外库共享该额度），防止超大库把提示词撑爆。 */
    private static final int MAX_TABLES = 200;

    /** 候选库清单上限：共享实例上往往挂着上百个库，全量回传只会把响应体撑大。 */
    private static final int MAX_CATALOGS = 100;

    /** 单表最多采集的外键数：宽表上百个约束时只需前几条就够拼【关联关系】段，避免提示词被键信息淹没。 */
    private static final int MAX_KEYS_PER_TABLE = 20;

    /**
     * 系统库/模式：当跨库候选没意义，只会把清单撑长。这是“降噪”而非权限边界——
     * 真需要查它们时用户仍可手填库名（{@link #extraTargets} 不做黑名单校验）。
     */
    private static final Set<String> SYSTEM_CATALOGS = Set.of(
            "information_schema", "performance_schema", "mysql", "sys",
            "pg_catalog", "pg_toast", "template0", "template1",
            "sysaux", "outln", "dbsnmp", "appqossys", "xdb", "mdsys",
            "ctxsys", "orddata", "wmsys", "exfsys", "olapsys", "sysman");

    private final TenantDataSourceManager dataSourceManager;

    /** 执行前代价预估门面（T10-1）：在所有 SQL 下发数据库之前做一次静态扫描量判定。 */
    private final CostGuard costGuard;

    public JdbcDataSourceAdapter(TenantDataSourceManager dataSourceManager, CostGuard costGuard) {
        this.dataSourceManager = dataSourceManager;
        this.costGuard = costGuard;
    }

    @Override
    public boolean supports(DataSourceType type) {
        return !type.isFile();
    }

    @Override
    public void testConnection(DataSourceConfig config) {
        SqlDialect dialect = SqlDialects.of(config.type());
        // 走瞬时池：测试连接（尤其 id 为 null 的草稿配置）不得进入托管池，避免以 tenant:null 误建常驻池或泄漏连接
        try (HikariDataSource ds = dataSourceManager.createTransientDataSource(config);
             Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(10);
            stmt.execute(dialect.validationQuery());
        } catch (Exception e) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "数据源连接失败: " + rootMessage(e));
        }
    }

    @Override
    public List<String> listCatalogs(DataSourceConfig config) {
        boolean asSchema = targetIsSchema(config.type().family());
        try (Connection conn = dataSourceManager.getConnection(config);
             ResultSet rs = asSchema ? conn.getMetaData().getSchemas() : conn.getMetaData().getCatalogs()) {
            String column = asSchema ? "TABLE_SCHEM" : "TABLE_CAT";
            List<String> names = new ArrayList<>();
            while (rs.next() && names.size() < MAX_CATALOGS) {
                String name = rs.getString(column);
                if (name != null && !name.isBlank()
                        && !SYSTEM_CATALOGS.contains(name.toLowerCase(Locale.ROOT))) {
                    names.add(name);
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);   // 驱动回传顺序不保证，排一下使下拉框稳定可找
            return names;
        } catch (Exception e) {
            // 列不出候选不该影响连通性测试的结论：降级为空清单，前端回落到手填库名
            log.warn("列出可选库/模式失败: {} - {}", config.safeDescriptor(), rootMessage(e));
            return List.of();
        }
    }

    @Override
    public List<TableMeta> scanSchema(DataSourceConfig config) {
        try (Connection conn = dataSourceManager.getConnection(config)) {
            DatabaseMetaData metaData = conn.getMetaData();
            String primaryCatalog = conn.getCatalog();
            String primarySchema = conn.getSchema();
            if (primarySchema == null && config.type().family() == DataSourceType.Family.POSTGRESQL) {
                primarySchema = "public";
            }

            List<TableMeta> tables = new ArrayList<>();
            Set<String> emitted = new HashSet<>();
            KeyProbe probe = new KeyProbe(config);
            // ① 主库：表名不加前缀（qualified=false），行为与升级前一致
            scanTarget(metaData, config.type(), primaryCatalog, primarySchema, false,
                    MAX_TABLES, emitted, tables, probe);

            // ② 额外库/模式：主库先扫，故 MAX_TABLES 的剩余额度才给它们——一个巨大的额外库
            //    不会把主库的表挤出提示词（主库才是用户提问的主战场）
            for (String extra : extraTargets(config, primaryCatalog, primarySchema)) {
                if (tables.size() >= MAX_TABLES) {
                    log.warn("表数已达上限 {}，跳过额外库: {}", MAX_TABLES, extra);
                    break;
                }
                boolean asSchema = targetIsSchema(config.type().family());
                int before = tables.size();
                scanTarget(metaData, config.type(), asSchema ? null : extra, asSchema ? extra : null,
                        true, MAX_TABLES - before, emitted, tables, probe);
                if (tables.size() == before) {
                    // 不报错只记日志：库名写错/无权限时，主库问数仍应可用，不能整体扫表失败
                    log.warn("额外库未扫到任何表（库名不存在或账号无权限）: {}", extra);
                }
            }
            return tables;
        } catch (Exception e) {
            log.error("扫描表结构失败: {}", config.safeDescriptor(), e);
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "读取表结构失败: " + rootMessage(e));
        }
    }

    /**
     * 扫描一个库/模式下的表与列（含主键 / 外键），追加到 {@code out}。
     *
     * @param qualified 是否给表名加限定前缀（主库 false、额外库 true）
     * @param budget    本次最多追加几张表（受全局 {@link #MAX_TABLES} 约束）
     * @param emitted   已产出的表名（小写），跨库同名时只保留先扫到的那份（主库优先）
     * @param probe     主键 / 外键采集能力探针（一次扫表共享，驱动不支持时整体停用）
     */
    private void scanTarget(DatabaseMetaData metaData, DataSourceType type, String catalog, String schema,
                            boolean qualified, int budget, Set<String> emitted, List<TableMeta> out,
                            KeyProbe probe)
            throws SQLException {
        if (budget <= 0) {
            return;
        }
        // ① 表清单（含注释），限制数量
        Map<String, String> tableComments = new LinkedHashMap<>();
        // 展示名 → 库内真实表名：查主键/外键必须用真实表名（带限定前缀的展示名驱动认不出来）
        Map<String, String> rawNames = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next() && tableComments.size() < budget) {
                String tableName = rs.getString("TABLE_NAME");
                if (isSystemTable(tableName)) {
                    continue;
                }
                String display = display(type, qualified, catalog,
                        rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), tableName);
                if (!emitted.add(display.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                tableComments.put(display, rs.getString("REMARKS"));
                rawNames.put(display, tableName);
            }
        }
        if (tableComments.isEmpty()) {
            return;
        }

        // ② 各表的列（仅保留已选中的表）；按同样的限定规则重建表名才能对上号
        Map<String, List<ColumnMeta>> columnsByTable = new LinkedHashMap<>();
        tableComments.keySet().forEach(t -> columnsByTable.put(t, new ArrayList<>()));
        try (ResultSet rs = metaData.getColumns(catalog, schema, "%", "%")) {
            while (rs.next()) {
                String display = display(type, qualified, catalog,
                        rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"));
                List<ColumnMeta> columns = columnsByTable.get(display);
                if (columns == null) {
                    continue;
                }
                columns.add(new ColumnMeta(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getString("REMARKS")));
            }
        }

        // ③ 主键 / 外键（仅服务于多表问数：Prompt 的【关联关系】段与 SchemaLinker 的关系扩散）。
        //    逐表两次元数据查询，无约束概念的库（Hive / ClickHouse / Presto）与部分驱动会报错或恒返回空，
        //    故由 probe 统一控制：首次异常即整体停用，不会让 200 张表 × 2 次无效往返拖慢扫表。
        Map<String, List<String>> pkByTable = new LinkedHashMap<>();
        Map<String, List<FkMeta>> fkByTable = new LinkedHashMap<>();
        if (probe.enabled()) {
            for (Map.Entry<String, String> e : rawNames.entrySet()) {
                String display = e.getKey();
                String raw = e.getValue();
                try {
                    List<String> pks = primaryKeysOf(metaData, catalog, schema, raw);
                    if (!pks.isEmpty()) {
                        pkByTable.put(display, pks);
                    }
                    List<FkMeta> fks = foreignKeysOf(metaData, type, qualified, catalog, schema, raw);
                    if (!fks.isEmpty()) {
                        fkByTable.put(display, fks);
                    }
                } catch (SQLException | RuntimeException ex) {
                    probe.disable(raw, ex);
                    break;
                }
            }
        }

        tableComments.forEach((name, comment) -> out.add(new TableMeta(name, comment,
                columnsByTable.getOrDefault(name, List.of()),
                pkByTable.getOrDefault(name, List.of()),
                fkByTable.getOrDefault(name, List.of()))));
    }

    /** 主键列名（按 KEY_SEQ 排序，复合主键为多列）；驱动未回传序号时保持回传顺序。 */
    private static List<String> primaryKeysOf(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Map<Integer, String> bySeq = new TreeMap<>();
        int fallback = 1000;   // KEY_SEQ 恒为 0 的驱动：用递增位占座，否则复合主键会被归并到同一键只剩一列
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col == null || col.isBlank()) {
                    continue;
                }
                int seq = rs.getInt("KEY_SEQ");
                bySeq.putIfAbsent(seq > 0 ? seq : fallback++, col);
            }
        }
        return new ArrayList<>(bySeq.values());
    }

    /** 外键（本表列 → 引用表.列）；引用表名按与表名一致的规则限定，使之能与提示词里的表名对得上号。 */
    private static List<FkMeta> foreignKeysOf(DatabaseMetaData metaData, DataSourceType type, boolean qualified,
                                              String catalog, String schema, String table) throws SQLException {
        List<FkMeta> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (ResultSet rs = metaData.getImportedKeys(catalog, schema, table)) {
            while (rs.next() && out.size() < MAX_KEYS_PER_TABLE) {
                String column = rs.getString("FKCOLUMN_NAME");
                String refColumn = rs.getString("PKCOLUMN_NAME");
                String refTable = display(type, qualified, catalog,
                        rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"));
                if (column == null || column.isBlank() || refColumn == null || refColumn.isBlank()
                        || refTable == null || refTable.isBlank()) {
                    continue;
                }
                String key = (column + "->" + refTable + "." + refColumn).toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    out.add(new FkMeta(column, refTable, refColumn));
                }
            }
        }
        return out;
    }

    /**
     * 主键 / 外键采集能力探针：一次扫表（含全部额外库）共享一个。
     *
     * <p>为什么需要：键采集是逐表额外往返，而很多数仓/OLAP 驱动根本不支持（或表上没约束），
     * 一旦失败就会逐表抛异常。首次失败即整体停用并只记一条日志，
     * 保证“采不到关联关系”只意味着回到升级前的行为，绝不影响扫表成败与耗时。
     */
    private static final class KeyProbe {

        private final DataSourceConfig config;
        private boolean enabled = true;

        KeyProbe(DataSourceConfig config) {
            this.config = config;
        }

        boolean enabled() {
            return enabled;
        }

        void disable(String table, Exception e) {
            if (enabled) {
                log.warn("采集主键/外键失败（表 {}），本次扫表跳过关联关系：{} - {}",
                        table, config.safeDescriptor(), rootMessage(e));
            }
            enabled = false;
        }
    }

    /**
     * 需要一并扫描的额外库/模式：去空白、去重，并剔除与主库同名者（同名重扫只会在提示词里出现两份同表）。
     */
    private static List<String> extraTargets(DataSourceConfig config, String primaryCatalog, String primarySchema) {
        if (config.extraCatalogs() == null || config.extraCatalogs().isEmpty()) {
            return List.of();
        }
        String primary = targetIsSchema(config.type().family()) ? primarySchema : primaryCatalog;
        List<String> out = new ArrayList<>();
        for (String raw : config.extraCatalogs()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            if (primary != null && name.equalsIgnoreCase(primary)) {
                continue;   // 就是主库本身
            }
            if (out.stream().noneMatch(name::equalsIgnoreCase)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * 额外目标落在 JDBC 元数据的哪一栏：PostgreSQL / Oracle 不支持同一连接跨数据库，
     * 它们的“多库”实为同一库内的多模式，故目标名当作 schema 传；其余库当作 catalog。
     */
    private static boolean targetIsSchema(DataSourceType.Family family) {
        return family == DataSourceType.Family.POSTGRESQL || family == DataSourceType.Family.ORACLE;
    }

    /**
     * 表名在提示词里的呈现形式：主库原样，额外库带限定前缀。
     *
     * <p>前缀必须就是该库写进 SQL 能直接执行的引用形式，否则 LLM 照着提示词写出的跨库 join 会报表不存在：
     * MySQL 系与 ClickHouse/Hive 是 {@code 库.表}，PostgreSQL/Oracle 是 {@code 模式.表}，
     * SQL Server 与 Presto/Trino 需三段式 {@code 库.模式.表}（SQL Server 的默认模式 dbo 可省）。
     */
    private static String display(DataSourceType type, boolean qualified, String fallbackCatalog,
                                  String rowCatalog, String rowSchema, String table) {
        if (!qualified) {
            return table;
        }
        String catalog = rowCatalog != null && !rowCatalog.isBlank() ? rowCatalog : fallbackCatalog;
        return switch (type.family()) {
            case POSTGRESQL, ORACLE -> qualify(rowSchema, table);
            case SQLSERVER -> (rowSchema == null || rowSchema.isBlank() || "dbo".equalsIgnoreCase(rowSchema))
                    ? qualify(catalog, table) : catalog + "." + rowSchema + "." + table;
            case PRESTO -> (rowSchema == null || rowSchema.isBlank())
                    ? qualify(catalog, table) : catalog + "." + rowSchema + "." + table;
            default -> qualify(catalog, table);
        };
    }

    private static String qualify(String prefix, String table) {
        return prefix == null || prefix.isBlank() ? table : prefix + "." + table;
    }

    @Override
    public QueryResult execute(DataSourceConfig config, String sql, long maxRows, int timeoutSeconds) {
        enforceCostGuard(config, sql);
        SqlDialect dialect = SqlDialects.of(config.type());
        String limitedSql = dialect.applyLimit(sql, maxRows);
        long start = System.currentTimeMillis();
        try (Connection conn = dataSourceManager.getConnection(config)) {
            try {
                conn.setReadOnly(true);
            } catch (Exception ignore) {
                // 个别驱动不支持只读标记，忽略；连接池层已设 readOnly
            }
            try (PreparedStatement ps = conn.prepareStatement(limitedSql)) {
                ps.setQueryTimeout((int) timeoutSeconds);
                ps.setMaxRows((int) maxRows);
                try (ResultSet rs = ps.executeQuery()) {
                    return buildResult(limitedSql, rs, start, maxRows);
                }
            }
        } catch (SQLTimeoutException e) {
            throw new BizException(ErrorCode.QUERY_TIMEOUT, "查询超时，请缩小范围或增加时间过滤");
        } catch (Exception e) {
            log.error("执行 SQL 失败 [{}]: {}", config.safeDescriptor(), limitedSql, e);
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "查询执行失败: " + rootMessage(e));
        }
    }

    /**
     * 带位置参数的只读执行（V4.44 下钻切片用）：在 {@link #execute(DataSourceConfig, String, long, int)} 基础上
     * 把 {@code params} 按顺序绑定到 SQL 的 {@code ?} 占位符。值由 JDBC 参数化绑定，绝不拼进 SQL 字符串。
     */
    @Override
    public QueryResult execute(DataSourceConfig config, String sql, List<Object> params, long maxRows, int timeoutSeconds) {
        enforceCostGuard(config, sql);
        SqlDialect dialect = SqlDialects.of(config.type());
        String limitedSql = dialect.applyLimit(sql, maxRows);
        long start = System.currentTimeMillis();
        try (Connection conn = dataSourceManager.getConnection(config)) {
            try {
                conn.setReadOnly(true);
            } catch (Exception ignore) {
                // 个别驱动不支持只读标记，忽略；连接池层已设 readOnly
            }
            try (PreparedStatement ps = conn.prepareStatement(limitedSql)) {
                bindParams(ps, params);
                ps.setQueryTimeout((int) timeoutSeconds);
                ps.setMaxRows((int) maxRows);
                try (ResultSet rs = ps.executeQuery()) {
                    return buildResult(limitedSql, rs, start, maxRows);
                }
            }
        } catch (SQLTimeoutException e) {
            throw new BizException(ErrorCode.QUERY_TIMEOUT, "查询超时，请缩小范围或增加时间过滤");
        } catch (Exception e) {
            log.error("执行 SQL 失败 [{}]: {}", config.safeDescriptor(), limitedSql, e);
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "查询执行失败: " + rootMessage(e));
        }
    }

    /** 把位置参数按顺序绑定到 PreparedStatement；空/null 跳过（等价于无参）。 */
    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null || params.isEmpty()) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /**
     * 执行前代价预估（T10-1）：在方言 {@code applyLimit} <b>之前</b>对原始 SQL 做一次静态扫描量判定。
     *
     * <p>命中的是「扫多少行」维度（非「返回多少行」），故必须在追加 LIMIT 之前评估——LIMIT 会让每条 SQL
     * 看起来都有界，掩盖全表扫描/笛卡尔积的真实代价。{@code REJECT} 抛 {@link ErrorCode#QUERY_COST_TOO_HIGH}
     * 带拆分建议；{@code WARN} 仅记日志不阻断；总开关关闭或无法判定时 {@code assess} 恒放行，行为与现状等价。
     */
    private void enforceCostGuard(DataSourceConfig config, String sql) {
        CostVerdict verdict = costGuard.assess(config, sql);
        if (verdict.blocked()) {
            log.warn("代价熔断拦截 [{}]: {}", config.safeDescriptor(), verdict.reason());
            // 组合枚举默认语（含“代价”）+ 拆分建议：既对用户友好，又保证失败历史能被
            // T11-1 IssueDistiller 的 COST_GUARD_MARKS 稳定识别为“代价熔断”信号。
            String base = ErrorCode.QUERY_COST_TOO_HIGH.getMessage();
            String advice = verdict.advice();
            String msg = advice != null && !advice.isBlank() ? base + "：" + advice : base;
            throw new BizException(ErrorCode.QUERY_COST_TOO_HIGH, msg);
        }
        if (verdict.level() == CostVerdict.Level.WARN) {
            log.warn("代价预估告警 [{}]: est={} {}", config.safeDescriptor(),
                    verdict.estimatedScanRows(), verdict.reason());
        }
    }

    /**
     * 把 ResultSet 读成行列结构。
     *
     * <p><b>OOM 防护（问题#2）</b>：除 {@code setMaxRows} 与方言 {@code LIMIT} 两层软限制外，这里再做
     * 应用层硬截断——一旦已读行数达到 {@code maxRows} 立即停止，即便驱动无视 {@code setMaxRows}
     * 或 SQL 的 {@code LIMIT}，也不会把超大结果集无限堆积进内存。
     */
    private QueryResult buildResult(String sql, ResultSet rs, long start, long maxRows) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        int columnCount = md.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(md.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            if (maxRows > 0 && rows.size() >= maxRows) {
                break;   // 应用层硬截断
            }
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return QueryResult.of(sql, columns, rows, rows.size(), System.currentTimeMillis() - start);
    }

    /** 过滤系统/内部表，避免污染提示词。 */
    private boolean isSystemTable(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.toLowerCase();
        return lower.startsWith("pg_") || lower.startsWith("sql_") || lower.contains("$");
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
