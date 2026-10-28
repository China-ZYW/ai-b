package com.aibi.module.datasource.dialect;

/**
 * SQL 方言：把不同数据库的语法差异（标识符引用、分页上限、探活语句）收敛到一处，
 * 使通用适配器 {@code JdbcDataSourceAdapter} 无需为每种库写分支（见设计文档 8）。
 */
public interface SqlDialect {

    /** 引用标识符（表名/列名），用于安全转义，如 MySQL 用反引号、SQL Server 用方括号。 */
    String quote(String identifier);

    /**
     * 为 SQL 追加"最大返回行数"上限子句（若已包含则原样返回）。
     *
     * <p>注意：这是减少数据库计算/传输的"软限制"；适配器还会用 JDBC {@code setMaxRows} 做"硬限制"兜底，
     * 后者与 SQL 语法无关、跨库通用。
     */
    String applyLimit(String sql, long maxRows);

    /** 连接探活/校验的轻量 SQL。 */
    default String validationQuery() {
        return "SELECT 1";
    }

    /** 该方言面向的 LLM 提示（告知模型使用何种 SQL 风格）。 */
    default String llmHint() {
        return "标准 SQL";
    }
}
