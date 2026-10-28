package com.aibi.common.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迁移步骤的幂等 DDL/DML 助手集合（自 SchemaMigration 原样平移，逻辑零改动）。
 *
 * <p>所有"增列 / 补索引 / 放宽列宽 / 扩唯一键 / 补种配置"必须经本类助手执行：
 * 先查 information_schema 判定再动手，保证对已演进库重复执行为 no-op——
 * 这是"失败不记账、下次启动重试"容错语义成立的前提。
 *
 * <p>步骤内需要直接 UPDATE/CREATE TABLE 时经 {@link #jdbc()} 取原始模板。
 */
public class MigrationContext {

    private static final Logger log = LoggerFactory.getLogger(MigrationContext.class);

    private final JdbcTemplate metaJdbc;

    public MigrationContext(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
    }

    /** 原始 JDBC 模板：建表 / 回填 UPDATE / COUNT 查询等步骤内直操场景使用。 */
    public JdbcTemplate jdbc() {
        return metaJdbc;
    }

    /** 幂等增列助手：列已存在返回 false（步骤据此决定是否做配套回填）。 */
    public boolean addColumnIfMissing(String table, String column, String columnDdl) {
        if (columnExists(table, column)) {
            return false;
        }
        metaJdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + columnDdl);
        log.info("Schema 迁移: 表 {} 增列 {}", table, column);
        return true;
    }

    /** 幂等补索引助手：先查 information_schema.STATISTICS 再 CREATE INDEX。 */
    public boolean createIndexIfMissing(String table, String indexName, String indexDdl) {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName);
        if (count != null && count > 0) {
            return false;
        }
        metaJdbc.execute("CREATE INDEX " + indexName + " ON " + table + " " + indexDdl);
        log.info("Schema 迁移: 表 {} 增索引 {}", table, indexName);
        return true;
    }

    /**
     * 字段脱敏表唯一键扩列 table_name（幂等）。
     *
     * <p>判定依据：uk 是否已含 {@code table_name} 列成员（查 information_schema.STATISTICS）。若已含则跳过；
     * 否则 {@code DROP KEY + ADD UNIQUE KEY} 重建为含 table_name 的复合键。旧行 table_name 默认 NULL，
     * 在 MySQL 唯一键中多个 NULL 视为互异，不会与既有 (tenant,uid,ds,column) 唯一约束冲突，可安全重建。
     *
     * @param uniqueColsDdl 含 table_name 的唯一键列清单，如 {@code "tenant_id, uid, datasource_id, table_name, column_name"}
     */
    public void ensureFieldAclTableScope(String table, String ukName, String uniqueColsDdl) {
        Integer hasCol = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? AND COLUMN_NAME = 'table_name'",
                Integer.class, table, ukName);
        if (hasCol != null && hasCol > 0) {
            return;
        }
        metaJdbc.execute("ALTER TABLE " + table + " DROP KEY " + ukName
                + ", ADD UNIQUE KEY " + ukName + " (" + uniqueColsDdl + ")");
        log.info("Schema 迁移: 表 {} 唯一键 {} 扩列 table_name", table, ukName);
    }

    /**
     * 放宽列宽（MODIFY COLUMN）：目标定义须与基础 DDL 除长度外完全一致，避免误改默认值/注释。
     *
     * <p>先比对 {@code information_schema.CHARACTER_MAXIMUM_LENGTH}，已达标则跳过——MODIFY 本身可重复执行，
     * 但会重建表并抹掉列上的自定义属性，故仅在确实偏窄时动手。
     */
    public boolean widenColumn(String table, String column, String columnDdl) {
        Integer current = metaJdbc.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        if (current == null) {
            return false;   // 列不存在（表尚未就绪）：交由基础 DDL 与后续迁移处理
        }
        int target = targetLength(columnDdl);
        if (current >= target) {
            return false;
        }
        metaJdbc.execute("ALTER TABLE " + table + " MODIFY COLUMN " + columnDdl);
        log.info("Schema 迁移: 表 {} 列 {} 放宽 {} -> {}", table, column, current, target);
        return true;
    }

    /** 从列定义中取出 VARCHAR(n) 的 n；解析不出时返回 0（视为无需放宽）。 */
    private static int targetLength(String columnDdl) {
        Matcher m = Pattern
                .compile("(?i)VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)").matcher(columnDdl);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * 幂等补种平台级（GLOBAL scope）配置项：已存在同分类同键的行则跳过。
     *
     * <p>用于向已运行过的库补上新增的配置项，使管理页可见可改；未补种也不影响功能
     * （{@code AibiConfig} 每个读取点都带代码默认值兜底）。
     */
    public void seedGlobalConfigIfAbsent(String category, String key, String valueJson, String description) {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_config WHERE scope_type = 'GLOBAL' AND scope_id IS NULL "
                        + "AND category = ? AND config_key = ?", Integer.class, category, key);
        if (count != null && count > 0) {
            return;
        }
        metaJdbc.update("INSERT INTO sys_config(scope_type, scope_id, category, config_key, value_json, "
                        + "description, version, enabled) VALUES ('GLOBAL', NULL, ?, ?, ?, ?, 1, 1)",
                category, key, valueJson, description);
        log.info("Schema 迁移: 补种平台默认配置 {}/{}", category, key);
    }

    public boolean columnExists(String table, String column) {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }
}
