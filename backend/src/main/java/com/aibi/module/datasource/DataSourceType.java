package com.aibi.module.datasource;

/**
 * 支持的数据源类型（见设计文档 7 数据库矩阵）。
 *
 * <p>每个类型登记连接族、JDBC 驱动类、默认端口与 URL 模板；新增一种 JDBC 数据库通常只需在此加一行 +
 * 在 pom.xml 放开对应驱动依赖，业务代码零改动。文件类（CSV/Excel/Parquet）与对象存储文件源
 * （S3/OSS/MinIO）走 {@code CsvFileAdapter}：先把文件（或下载后的对象）载入一个按
 * {@code (租户, 数据源)} 命名的 SQLite 临时库，之后所有问数都以标准 SQL 查询这些表。
 *
 * <p><b>对象存储字段映射</b>（无需新增表字段，直接复用 {@code DataSource} 现有列）：
 * <ul>
 *   <li>{@code host} = endpoint（如 {@code https://oss-cn-hangzhou.aliyuncs.com} 或 {@code http://localhost:9000}）</li>
 *   <li>{@code database} = bucket</li>
 *   <li>{@code username} = AccessKey</li>
 *   <li>{@code password} = SecretKey</li>
 *   <li>{@code filePath} = objectKey（对象在桶内的路径）</li>
 *   <li>{@code params.region} = 区域（缺省 {@code us-east-1}）</li>
 * </ul>
 * S3 / OSS / MinIO 均兼容 S3 协议，由 {@code ObjectStorageService} 统一经 AWS SDK v2 下载后载入。
 */
public enum DataSourceType {

    // ── 主流关系型 ──────────────────────────────────────────────
    MYSQL(Family.MYSQL, "com.mysql.cj.jdbc.Driver", 3306, "jdbc:mysql://%s:%d/%s"),
    MARIADB(Family.MYSQL, "org.mariadb.jdbc.Driver", 3306, "jdbc:mariadb://%s:%d/%s"),
    POSTGRESQL(Family.POSTGRESQL, "org.postgresql.Driver", 5432, "jdbc:postgresql://%s:%d/%s"),
    SQLSERVER(Family.SQLSERVER, "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, "jdbc:sqlserver://%s:%d;databaseName=%s"),
    ORACLE(Family.ORACLE, "oracle.jdbc.OracleDriver", 1521, "jdbc:oracle:thin:@//%s:%d/%s"),
    SQLITE(Family.SQLITE, "org.sqlite.JDBC", 0, "jdbc:sqlite:%s"),

    // ── OLAP / 数仓（Doris、StarRocks 兼容 MySQL 协议）───────────
    CLICKHOUSE(Family.CLICKHOUSE, "com.clickhouse.jdbc.ClickHouseDriver", 8123, "jdbc:clickhouse://%s:%d/%s"),
    DORIS(Family.MYSQL, "com.mysql.cj.jdbc.Driver", 9030, "jdbc:mysql://%s:%d/%s"),
    STARROCKS(Family.MYSQL, "com.mysql.cj.jdbc.Driver", 9030, "jdbc:mysql://%s:%d/%s"),
    HIVE(Family.HIVE, "org.apache.hive.jdbc.HiveDriver", 10000, "jdbc:hive2://%s:%d/%s"),
    PRESTO(Family.PRESTO, "com.facebook.presto.jdbc.PrestoDriver", 8080, "jdbc:presto://%s:%d/%s"),
    TRINO(Family.PRESTO, "io.trino.jdbc.TrinoDriver", 8080, "jdbc:trino://%s:%d/%s"),

    // ── 文件类（无 JDBC 协议，载入内存库后以 SQL 查询）───────────
    CSV(Family.FILE, null, 0, null),
    EXCEL(Family.FILE, null, 0, null),

    // ── 列式文件源（本地 Parquet，载入内存库后以 SQL 查询）────────
    PARQUET(Family.FILE, null, 0, null),

    // ── 对象存储文件源（S3 协议兼容，下载后载入内存库以 SQL 查询）──
    // 字段复用见类注释：host=endpoint、database=bucket、username=accessKey、password=secretKey、
    // filePath=objectKey、params.region=区域。无需新增表字段。
    S3(Family.FILE, null, 0, null),
    OSS(Family.FILE, null, 0, null),
    MINIO(Family.FILE, null, 0, null);

    /** 连接族：同族共用方言与驱动策略。 */
    public enum Family {
        MYSQL, POSTGRESQL, SQLSERVER, ORACLE, CLICKHOUSE, HIVE, PRESTO, SQLITE, FILE
    }

    private final Family family;
    private final String driverClass;
    private final int defaultPort;
    private final String urlTemplate;

    DataSourceType(Family family, String driverClass, int defaultPort, String urlTemplate) {
        this.family = family;
        this.driverClass = driverClass;
        this.defaultPort = defaultPort;
        this.urlTemplate = urlTemplate;
    }

    public Family family() {
        return family;
    }

    public String driverClass() {
        return driverClass;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String urlTemplate() {
        return urlTemplate;
    }

    /** 是否为文件类数据源（走文件适配器而非通用 JDBC 适配器）。 */
    public boolean isFile() {
        return family == Family.FILE;
    }

    /** 是否为对象存储文件源（S3/OSS/MinIO）：需经对象存储下载后载入，字段映射见类注释。 */
    public boolean isObjectStorage() {
        return this == S3 || this == OSS || this == MINIO;
    }

    /** 静态重载：无实例时判断某类型是否为对象存储文件源（供 {@code DataSourceServiceImpl} 等静态调用）。 */
    public static boolean isObjectStorage(DataSourceType type) {
        return type != null && type.isObjectStorage();
    }
}
