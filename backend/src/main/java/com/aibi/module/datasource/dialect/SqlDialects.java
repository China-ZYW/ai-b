package com.aibi.module.datasource.dialect;

import com.aibi.module.datasource.DataSourceType;

import java.util.regex.Pattern;

/**
 * 方言工厂：按数据源类型的连接族返回对应方言实现（见设计文档 6 / 8）。
 *
 * <p>同族数据库共用一套方言，新增类型通常无需新增方言类。
 */
public final class SqlDialects {

    private SqlDialects() {
    }

    private static final SqlDialect MYSQL = new LimitSuffixDialect("MySQL", '`');
    private static final SqlDialect CLICKHOUSE = new LimitSuffixDialect("ClickHouse", '`');
    private static final SqlDialect HIVE = new LimitSuffixDialect("Hive/Presto/Trino", '`');
    private static final SqlDialect SQLITE = new LimitSuffixDialect("SQLite", '"');
    private static final SqlDialect POSTGRES = new LimitSuffixDialect("PostgreSQL", '"');
    private static final SqlDialect SQLSERVER = new SqlServerDialect();
    private static final SqlDialect ORACLE = new OracleDialect();

    public static SqlDialect of(DataSourceType type) {
        return switch (type.family()) {
            case MYSQL -> MYSQL;
            case POSTGRESQL -> POSTGRES;
            case SQLSERVER -> SQLSERVER;
            case ORACLE -> ORACLE;
            case CLICKHOUSE -> CLICKHOUSE;
            case HIVE, PRESTO -> HIVE;
            case SQLITE, FILE -> SQLITE;
        };
    }

    /** 是否已包含行数限制子句（LIMIT/TOP/FETCH FIRST/ROWNUM/OFFSET）。 */
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("\\b(LIMIT|TOP|FETCH\\s+FIRST|ROWNUM|OFFSET)\\b", Pattern.CASE_INSENSITIVE);

    private static boolean hasLimit(String sql) {
        return LIMIT_PATTERN.matcher(sql).find();
    }

    private static String trimTrailingSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** 末尾追加 {@code LIMIT n} 的一族：MySQL/ClickHouse/Hive/Presto/SQLite/PostgreSQL。 */
    static class LimitSuffixDialect implements SqlDialect {
        private final String name;
        private final char quoteChar;

        LimitSuffixDialect(String name, char quoteChar) {
            this.name = name;
            this.quoteChar = quoteChar;
        }

        @Override
        public String quote(String identifier) {
            String doubled = String.valueOf(quoteChar) + quoteChar;
            return quoteChar + identifier.replace(String.valueOf(quoteChar), doubled) + quoteChar;
        }

        @Override
        public String applyLimit(String sql, long maxRows) {
            String s = trimTrailingSemicolon(sql);
            return hasLimit(s) ? s : s + " LIMIT " + maxRows;
        }

        @Override
        public String llmHint() {
            return name + " 方言（标识符用 " + quoteChar + " 引用，行数限制用 LIMIT n）";
        }
    }

    /** SQL Server：{@code SELECT} 后插入 {@code TOP n}，方括号引用。 */
    static class SqlServerDialect implements SqlDialect {
        @Override
        public String quote(String identifier) {
            return "[" + identifier.replace("]", "]]") + "]";
        }

        @Override
        public String applyLimit(String sql, long maxRows) {
            String s = trimTrailingSemicolon(sql);
            if (hasLimit(s)) {
                return s;
            }
            return s.replaceFirst("(?i)^\\s*SELECT", "SELECT TOP " + maxRows);
        }

        @Override
        public String llmHint() {
            return "SQL Server 方言（标识符用 [方括号]，行数限制用 SELECT TOP n）";
        }
    }

    /** Oracle：末尾追加 {@code FETCH FIRST n ROWS ONLY}，双引号大写引用，探活用 DUAL。 */
    static class OracleDialect implements SqlDialect {
        @Override
        public String quote(String identifier) {
            return "\"" + identifier.toUpperCase().replace("\"", "\"\"") + "\"";
        }

        @Override
        public String applyLimit(String sql, long maxRows) {
            String s = trimTrailingSemicolon(sql);
            if (hasLimit(s)) {
                return s;
            }
            return s + " FETCH FIRST " + maxRows + " ROWS ONLY";
        }

        @Override
        public String validationQuery() {
            return "SELECT 1 FROM DUAL";
        }

        @Override
        public String llmHint() {
            return "Oracle 方言（标识符用双引号大写，行数限制用 FETCH FIRST n ROWS ONLY）";
        }
    }
}
