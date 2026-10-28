package com.aibi.module.datasource.adapter;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.dialect.SqlDialect;
import com.aibi.module.datasource.dialect.SqlDialects;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.datasource.service.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件类数据源适配器（CSV/Excel/Parquet/对象存储 S3·OSS·MinIO）。
 *
 * <p>把<b>每个选中的工作表 / 文件</b>的首行作为列名、载入一个按 {@code (租户, 数据源)} 命名的 SQLite 临时库，
 * 之后所有问数都以标准 SQL 查询这些表——从而复用与关系型库完全一致的 NL2SQL 与执行链路（多表 join、
 * 子查询、聚合都由 SQLite 承担）。解析由 {@link FileTableReader} 统一承担（CSV 流式、Excel 走 Apache POI、
 * Parquet 走 AvroParquetReader），列统一按 TEXT、不做类型推断。
 *
 * <p><b>对象存储类</b>（S3/OSS/MinIO）不持有本地文件：{@link #ensureLoaded} 先经 {@link ObjectStorageService}
 * 把目标对象下载到临时目录（按 ETag 缓存复用），之后的载入/查询与本地文件完全一致。底层格式由 objectKey 扩展名推断
 * （.csv/.xlsx/.xls/.parquet），故同一套链路覆盖本地文件与对象存储。
 *
 * <p><b>表名规则</b>：只有一张表时固定叫 {@value #SINGLE_TABLE}（与升级前一致，已存指标的 SQL 不会失效）；
 * 多张表时按工作表名规范化生成（见 {@link #sanitizeTableName}）。{@code sheetMerge=true} 时，列结构完全一致的
 * 工作表会合并成一张表——多个月度 sheet 同构的场景下，问"全年合计"不必让 LLM 手写跨十几张表的 UNION ALL。
 *
 * <p>建表顺序与来源工作表名记在内部元表 {@value #META_TABLE} 里，{@link #scanSchema} 据此还原表注释
 * （LLM 在多表场景下靠注释选对表）；该元表不对外暴露。
 *
 * <p>替换/删除文件、或改动工作表勾选后由 {@link #evictCache} 删除临时库（与对象缓存），令下次查询基于最新配置重建。
 */
@Component
public class CsvFileAdapter implements DataSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(CsvFileAdapter.class);

    /** 单表场景的固定表名（升级前的唯一表名，保持兼容）。 */
    static final String SINGLE_TABLE = "data";

    /** 内部元表：记录建表顺序、来源工作表与注释，仅供 {@link #scanSchema} 使用，不对外暴露。 */
    static final String META_TABLE = "aibi_sheet_meta";

    private static final String SQLITE_DRIVER = "org.sqlite.JDBC";
    private static final int BATCH_SIZE = 500;

    /** 表名最大长度（SQLite 标识符上限远高于此，取小值只为让 Prompt 里的表结构紧凑可读）。 */
    private static final int MAX_TABLE_NAME = 48;

    /**
     * 常见的 SQLite 关键字：工作表若正好叫 {@code order}/{@code group} 之类，规范化后仍是不加引号就无法
     * 写进 SQL 的表名（LLM 生成的 SQL 基本不会给表名加引号），故命中时统一加 {@code t_} 前缀。
     */
    private static final Set<String> RESERVED = Set.of(
            "order", "group", "select", "from", "table", "where", "join", "left", "right", "inner", "outer",
            "index", "key", "default", "union", "values", "null", "and", "or", "not", "as", "by", "on", "in",
            "is", "limit", "offset", "desc", "asc", "distinct", "all", "having", "case", "when", "then", "else",
            "end", "between", "like", "exists", "primary", "unique", "check", "column", "constraint", "data");

    private final FileTableReader reader;
    private final ObjectStorageService objectStorage;

    public CsvFileAdapter(FileTableReader reader, ObjectStorageService objectStorage) {
        this.reader = reader;
        this.objectStorage = objectStorage;
    }

    @Override
    public boolean supports(DataSourceType type) {
        return type.isFile();
    }

    @Override
    public void testConnection(DataSourceConfig config) {
        ensureLoaded(config);
    }

    @Override
    public List<TableMeta> scanSchema(DataSourceConfig config) {
        ensureLoaded(config);
        try (Connection conn = open(config)) {
            List<SheetMeta> metas = readSheetMeta(conn);
            if (metas.isEmpty()) {
                return legacySchema(conn, config);
            }
            List<TableMeta> out = new ArrayList<>(metas.size());
            for (SheetMeta m : metas) {
                out.add(new TableMeta(m.tableName(), m.comment(), columnsOf(conn, m.tableName())));
            }
            return out;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "读取文件表结构失败: " + rootMessage(e));
        }
    }

    @Override
    public QueryResult execute(DataSourceConfig config, String sql, long maxRows, int timeoutSeconds) {
        ensureLoaded(config);
        SqlDialect dialect = SqlDialects.of(DataSourceType.SQLITE);
        String limitedSql = dialect.applyLimit(sql, maxRows);
        long start = System.currentTimeMillis();
        try (Connection conn = open(config);
             PreparedStatement ps = conn.prepareStatement(limitedSql)) {
            ps.setQueryTimeout((int) timeoutSeconds);
            ps.setMaxRows((int) maxRows);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int columnCount = md.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(md.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return QueryResult.of(limitedSql, columns, rows, rows.size(), System.currentTimeMillis() - start);
            }
        } catch (SQLTimeoutException e) {
            throw new BizException(ErrorCode.QUERY_TIMEOUT, "查询超时");
        } catch (Exception e) {
            log.error("文件查询执行失败: {}", limitedSql, e);
            throw new BizException(ErrorCode.SQL_GENERATE_FAILED, "查询执行失败: " + rootMessage(e));
        }
    }

    /**
     * 带位置参数的只读执行（V4.44 下钻切片用）：把 {@code params} 按顺序绑定到 SQL 的 {@code ?} 占位符。
     * 文件库（SQLite）同样走 PreparedStatement 参数化，值绝不拼进 SQL 字符串。
     */
    @Override
    public QueryResult execute(DataSourceConfig config, String sql, List<Object> params, long maxRows, int timeoutSeconds) {
        ensureLoaded(config);
        SqlDialect dialect = SqlDialects.of(DataSourceType.SQLITE);
        String limitedSql = dialect.applyLimit(sql, maxRows);
        long start = System.currentTimeMillis();
        try (Connection conn = open(config);
             PreparedStatement ps = conn.prepareStatement(limitedSql)) {
            bindParams(ps, params);
            ps.setQueryTimeout((int) timeoutSeconds);
            ps.setMaxRows((int) maxRows);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int columnCount = md.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(md.getColumnLabel(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return QueryResult.of(limitedSql, columns, rows, rows.size(), System.currentTimeMillis() - start);
            }
        } catch (SQLTimeoutException e) {
            throw new BizException(ErrorCode.QUERY_TIMEOUT, "查询超时");
        } catch (Exception e) {
            log.error("文件查询执行失败: {}", limitedSql, e);
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

    /** 替换/删除文件类数据源、或改动工作表勾选后失效其 SQLite 临时库与对象缓存，令下次查询重新载入。 */
    @Override
    public void evictCache(Long tenantId, Long datasourceId) {
        Path db = sqlitePath(tenantId, datasourceId);
        try {
            if (Files.deleteIfExists(db)) {
                log.info("已失效文件数据源临时库: {}", db);
            }
        } catch (IOException e) {
            log.warn("失效文件数据源临时库失败: {} - {}", db, e.getMessage());
        }
        objectStorage.evict(tenantId, datasourceId);
    }

    // ==================== 载入 ====================

    /**
     * 首次使用时把选中的工作表载入 SQLite 临时库；已存在则跳过（改配置/换文件后需 evictCache 才会重载）。
     *
     * <p>对象存储类先经 {@link ObjectStorageService#resolveLocalPath} 下载到本地临时文件（按 ETag 复用本地副本），
     * 之后与本地文件走完全相同的解析链路；底层格式（CSV/Excel/Parquet）由 {@link #effectiveFileType} 推断。
     */
    private synchronized void ensureLoaded(DataSourceConfig config) {
        Path db = sqlitePath(config);
        if (Files.exists(db)) {
            return;
        }
        Path file = objectStorage.resolveLocalPath(config);
        DataSourceType fileType = effectiveFileType(config);
        try {
            List<FileTableReader.SheetInfo> sheets =
                    selectSheets(reader.listSheets(file, fileType), config.sheetNames());
            if (sheets.isEmpty()) {
                throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                        "文件中没有可导入的工作表（请确认首行是列名，且勾选的工作表名仍然存在）");
            }
            List<TablePlan> plans = plan(sheets, config.sheetMerge());
            try (Connection conn = open(config)) {
                SqliteLoader loader = new SqliteLoader(conn, plans);
                List<Integer> indexes = sheets.stream().map(FileTableReader.SheetInfo::index).toList();
                int loaded = reader.readSheets(file, fileType, indexes, 0, loader);
                Map<String, Integer> counts = loader.finish();
                writeSheetMeta(conn, plans, counts, config);
                log.info("文件载入完成: {} → {} ({} 张工作表 → {} 张表, 共 {} 行)",
                        file, db, sheets.size(), plans.size(), loaded);
            }
        } catch (BizException e) {
            deleteQuietly(db);   // 半途失败：清掉残缺临时库，避免下次误判"已载入"
            throw e;
        } catch (Exception e) {
            deleteQuietly(db);
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "文件载入失败: " + rootMessage(e));
        }
    }

    /**
     * 把数据源类型映射为「底层文件格式」（供 {@link FileTableReader} 选择解析器）。
     * 本地 CSV/Excel/Parquet 原样映射；对象存储类按 objectKey 扩展名推断（.csv/.xlsx/.xls/.parquet）。
     */
    private DataSourceType effectiveFileType(DataSourceConfig config) {
        return switch (config.type()) {
            case CSV -> DataSourceType.CSV;
            case EXCEL -> DataSourceType.EXCEL;
            case PARQUET -> DataSourceType.PARQUET;
            default -> {
                if (!config.type().isObjectStorage()) {
                    throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                            "不支持的文件类数据源类型: " + config.type());
                }
                String key = config.filePath() == null ? "" : config.filePath().toLowerCase(Locale.ROOT);
                if (key.endsWith(".parquet")) {
                    yield DataSourceType.PARQUET;
                }
                if (key.endsWith(".xlsx") || key.endsWith(".xls")) {
                    yield DataSourceType.EXCEL;
                }
                if (key.endsWith(".csv")) {
                    yield DataSourceType.CSV;
                }
                throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED,
                        "对象存储文件格式不支持，仅支持 .csv/.xlsx/.xls/.parquet: " + config.filePath());
            }
        };
    }

    /**
     * 按勾选的工作表名过滤；勾选为空表示全部。
     *
     * <p>名字比对忽略首尾空白；勾了但文件里已不存在的名字静默忽略（用户换了文件却没重选，
     * 此时按剩下的表继续问数比整体报错更有用）。无表头的工作表（封面/说明页）一律排除。
     */
    private static List<FileTableReader.SheetInfo> selectSheets(List<FileTableReader.SheetInfo> all,
                                                                List<String> selected) {
        List<FileTableReader.SheetInfo> importable = all.stream()
                .filter(FileTableReader.SheetInfo::importable)
                .toList();
        if (selected == null || selected.isEmpty()) {
            return importable;
        }
        Set<String> want = new LinkedHashSet<>();
        for (String s : selected) {
            if (s != null && !s.isBlank()) {
                want.add(s.trim());
            }
        }
        if (want.isEmpty()) {
            return importable;
        }
        List<FileTableReader.SheetInfo> out = new ArrayList<>();
        for (FileTableReader.SheetInfo info : importable) {
            if (want.contains(info.name().trim())) {
                out.add(info);
            }
        }
        return out;
    }

    /**
     * 生成建表计划：{@code merge=false} 时一张工作表一张表；{@code merge=true} 时把<b>列结构完全一致</b>
     * 的工作表归为一组共用一张表（组内保持工作簿顺序，表名取组内第一个工作表名）。
     *
     * <p>只有一张目标表时固定命名为 {@value #SINGLE_TABLE}——既兼容升级前建的指标 SQL，也让单表文件的
     * Prompt 保持最简。
     */
    private static List<TablePlan> plan(List<FileTableReader.SheetInfo> sheets, boolean merge) {
        List<List<FileTableReader.SheetInfo>> groups = new ArrayList<>();
        if (merge) {
            Map<String, List<FileTableReader.SheetInfo>> byColumns = new LinkedHashMap<>();
            for (FileTableReader.SheetInfo s : sheets) {
                // 列名已规范化（空列名补位、重名加序号），拼接串即"结构签名"
                byColumns.computeIfAbsent(String.join("\u0001", s.headers()), k -> new ArrayList<>()).add(s);
            }
            groups.addAll(byColumns.values());
        } else {
            for (FileTableReader.SheetInfo s : sheets) {
                groups.add(List.of(s));
            }
        }

        boolean single = groups.size() == 1;
        Set<String> used = new HashSet<>();
        List<TablePlan> plans = new ArrayList<>(groups.size());
        for (List<FileTableReader.SheetInfo> group : groups) {
            String base = single ? SINGLE_TABLE : sanitizeTableName(group.get(0).name(), plans.size() + 1);
            String name = base;
            int n = 2;
            while (!used.add(name)) {
                name = base + "_" + n++;
            }
            plans.add(new TablePlan(name,
                    group.stream().map(FileTableReader.SheetInfo::name).toList(),
                    group.get(0).headers(),
                    group.stream().map(FileTableReader.SheetInfo::index).toList()));
        }
        return plans;
    }

    /**
     * 工作表名 → SQLite 表名。
     *
     * <p>只保留字母、数字、下划线与非 ASCII 字符（中文表名可原样使用，SQLite 允许 &gt;= 0x80 的字符作标识符），
     * 其余一律折叠为下划线；以数字开头时加 {@code s_} 前缀、命中关键字时加 {@code t_} 前缀，
     * 保证<b>不加引号也能写进 SQL</b>——LLM 生成的语句基本不会给表名加引号。
     *
     * @param fallbackIndex 规范化后为空时的兜底序号（{@code sheet_N}）
     */
    static String sanitizeTableName(String raw, int fallbackIndex) {
        String src = raw == null ? "" : raw.trim();
        StringBuilder sb = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c >= 0x80;
            sb.append(ok ? c : '_');
        }
        // 折叠连续下划线并去掉首尾下划线，避免 "1 月 报表" 变成 "1___报表"
        String name = sb.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (name.length() > MAX_TABLE_NAME) {
            name = name.substring(0, MAX_TABLE_NAME);
        }
        if (name.isEmpty()) {
            return "sheet_" + fallbackIndex;
        }
        char first = name.charAt(0);
        if (first >= '0' && first <= '9') {
            return "s_" + name;
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            return "t_" + name;
        }
        return name;
    }

    /** 边读边批插的流式载入器：按工作表下标把数据路由到目标表，每张表独立批量提交。 */
    private static final class SqliteLoader implements FileTableReader.SheetHandler {
        private final Map<Integer, String> tableOfSheet = new HashMap<>();
        private final Map<String, PreparedStatement> statements = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Connection conn;
        private PreparedStatement current;
        private String currentTable;
        private int columnCount;

        SqliteLoader(Connection conn, List<TablePlan> plans) throws SQLException {
            this.conn = conn;
            for (TablePlan p : plans) {
                createTable(conn, p.tableName(), p.headers());
                String placeholders = String.join(",", Collections.nCopies(p.headers().size(), "?"));
                statements.put(p.tableName(), conn.prepareStatement(
                        "INSERT INTO " + quote(p.tableName()) + " VALUES (" + placeholders + ")"));
                counts.put(p.tableName(), 0);
                for (int idx : p.sheetIndexes()) {
                    tableOfSheet.put(idx, p.tableName());
                }
            }
            conn.setAutoCommit(false);
        }

        @Override
        public void onTable(String sheetName, int sheetIndex, List<String> headers) {
            currentTable = tableOfSheet.get(sheetIndex);
            current = currentTable == null ? null : statements.get(currentTable);
            columnCount = headers.size();
        }

        @Override
        public void onRow(List<String> row) throws SQLException {
            if (current == null) {
                return;   // 未勾选的工作表：整表丢弃
            }
            for (int i = 0; i < columnCount; i++) {
                current.setString(i + 1, i < row.size() ? row.get(i) : null);
            }
            current.addBatch();
            if (counts.merge(currentTable, 1, Integer::sum) % BATCH_SIZE == 0) {
                current.executeBatch();
            }
        }

        @Override
        public void onTableEnd() throws SQLException {
            if (current != null) {
                current.executeBatch();   // 合并表的每张来源工作表读完即刷一批，避免批无限增长
            }
        }

        /** 收尾：刷完全部批次并提交，返回每张表的实际行数（写入内部元表）。 */
        Map<String, Integer> finish() throws SQLException {
            for (PreparedStatement ps : statements.values()) {
                ps.executeBatch();
                ps.close();
            }
            conn.commit();
            conn.setAutoCommit(true);
            return counts;
        }
    }

    // ==================== 内部元表 ====================

    /**
     * 内部元表里的一行：目标表名 + 供 LLM 选表的注释。
     */
    private record SheetMeta(String tableName, String comment) {
    }

    /**
     * 建表计划：一张目标 SQLite 表 ← 一个或多个同构工作表。
     *
     * @param tableName    目标表名（已规范化，可直接写进 SQL）
     * @param sheetNames   来源工作表名（合并时多个），用于生成表注释
     * @param headers      列名（同组各工作表一致）
     * @param sheetIndexes 来源工作表下标，用于把流式回调的数据路由到本表
     */
    private record TablePlan(String tableName, List<String> sheetNames, List<String> headers,
                             List<Integer> sheetIndexes) {
    }

    private void writeSheetMeta(Connection conn, List<TablePlan> plans, Map<String, Integer> counts,
                                DataSourceConfig config) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + quote(META_TABLE));
            st.execute("CREATE TABLE " + quote(META_TABLE) + " ("
                    + "ord INTEGER, table_name TEXT, comment TEXT, sheet_names TEXT, row_count INTEGER)");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + quote(META_TABLE) + " VALUES (?,?,?,?,?)")) {
            int ord = 0;
            for (TablePlan p : plans) {
                ps.setInt(1, ord++);
                ps.setString(2, p.tableName());
                ps.setString(3, commentOf(p, config));
                ps.setString(4, String.join("、", p.sheetNames()));
                ps.setInt(5, counts.getOrDefault(p.tableName(), 0));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 表注释：多表场景下这是 LLM 选对表的唯一线索，故必须写清数据来自哪些工作表/文件。
     * CSV 单表时沿用数据源名（与升级前一致，不额外增加 Prompt 噪声）；Parquet / 对象存储写明其来源。
     */
    private static String commentOf(TablePlan plan, DataSourceConfig config) {
        if (config.type() == DataSourceType.CSV) {
            return config.name();
        }
        if (config.type() == DataSourceType.PARQUET) {
            return "Parquet 文件: " + config.name();
        }
        if (config.type().isObjectStorage()) {
            return "对象存储(" + config.type() + ") 文件: " + config.filePath();
        }
        if (plan.sheetNames().size() == 1) {
            return "Excel 工作表: " + plan.sheetNames().get(0);
        }
        return "Excel 工作表合并(" + plan.sheetNames().size() + " 张同构表): "
                + String.join("、", plan.sheetNames());
    }

    /** 读内部元表；表不存在（升级前建的临时库）或为空时返回空列表，由调用方走兜底。 */
    private List<SheetMeta> readSheetMeta(Connection conn) {
        List<SheetMeta> out = new ArrayList<>();
        String sql = "SELECT table_name, comment FROM " + quote(META_TABLE) + " ORDER BY ord";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new SheetMeta(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            log.debug("内部元表不可用，按旧临时库处理: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /** 老临时库（升级前建的：只有单表 {@value #SINGLE_TABLE}、无内部元表）兜底扫表，保证升级后不白屏。 */
    private List<TableMeta> legacySchema(Connection conn, DataSourceConfig config) throws SQLException {
        List<String> names = tableNames(conn);
        if (names.isEmpty()) {
            names = List.of(SINGLE_TABLE);
        }
        List<TableMeta> out = new ArrayList<>(names.size());
        for (String n : names) {
            out.add(new TableMeta(n, config.name(), columnsOf(conn, n)));
        }
        return out;
    }

    private List<String> tableNames(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String n = rs.getString("TABLE_NAME");
                if (n != null && !META_TABLE.equals(n) && !n.startsWith("sqlite_")) {
                    out.add(n);
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * 用一次零行查询取列元数据。
     *
     * <p>不用 {@code DatabaseMetaData.getColumns}：它的表名参数是 LIKE 模式，表名里含 {@code _}（如工作表
     * {@code 1_月}）会误匹配到别的表，返回串了列的结构。
     */
    private List<ColumnMeta> columnsOf(Connection conn, String table) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + quote(table) + " WHERE 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                columns.add(new ColumnMeta(md.getColumnName(i), md.getColumnTypeName(i), null));
            }
        }
        return columns;
    }

    // ==================== 工具 ====================

    private static void deleteQuietly(Path db) {
        try {
            Files.deleteIfExists(db);
        } catch (IOException ignore) {
            // 清理失败不影响主流程
        }
    }

    private static void createTable(Connection conn, String table, List<String> headers) throws SQLException {
        if (headers.isEmpty()) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "文件无表头，无法建表");
        }
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(table)).append(" (");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(quote(headers.get(i))).append(" TEXT");
        }
        ddl.append(")");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + quote(table));
            stmt.execute(ddl.toString());
        }
    }

    /** SQLite 标识符引用：双引号包裹并转义内部双引号（表名/列名都来自用户上传的文件，不可裸拼进 SQL）。 */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private Connection open(DataSourceConfig config) throws SQLException {
        try {
            Class.forName(SQLITE_DRIVER);
        } catch (ClassNotFoundException e) {
            throw new SQLException("缺少 SQLite 驱动（sqlite-jdbc），请在 pom.xml 放开依赖", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + sqlitePath(config).toAbsolutePath());
    }

    private Path sqlitePath(DataSourceConfig config) {
        return sqlitePath(config.tenantId(), config.id());
    }

    private Path sqlitePath(Long tenantId, Long datasourceId) {
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "aibi-csv-" + tenantId + "-" + datasourceId + ".sqlite");
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
