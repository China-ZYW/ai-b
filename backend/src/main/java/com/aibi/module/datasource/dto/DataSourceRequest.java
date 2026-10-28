package com.aibi.module.datasource.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 数据源创建 / 更新请求（T2.1）。
 *
 * <p>{@code type} 为 {@code DataSourceType} 枚举名（MYSQL/POSTGRESQL/CLICKHOUSE/CSV...）。
 * 数据库类需 host+database；文件类需 filePath。更新时 {@code password} 留空表示保持原凭证不变。
 *
 * @param jdbcParams 额外 JDBC 参数，{@code "k=v&k=v"} 形式（如 useSSL、serverTimezone）
 * @param sheetNames 文件类数据源勾选的工作表名；空/null 表示导入全部工作表。
 *                   按名而非下标传递——用户日后调整工作簿内 sheet 顺序不会静默错选
 * @param sheetMerge 是否把<b>列结构完全一致</b>的工作表合并为一张表（如 12 个月度 sheet 同构时，
 *                   合并后问"全年销售额"无需 LLM 手写跨 12 张表的 UNION ALL）
 * @param extraCatalogs 数据库类数据源除主库（{@code database}）外还要一并扫表的额外库/模式名；
 *                   空/null = 只扫主库。仅当同一连接能跨库引用时生效（MySQL/SQL Server/ClickHouse 等的
 *                   “库”，PostgreSQL/Oracle 的“模式”），这些库的表名会带库前缀，从而支持跨库 join 问数
 */
public record DataSourceRequest(
        @NotBlank(message = "名称不能为空") String name,
        @NotBlank(message = "类型不能为空") String type,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        String filePath,
        String jdbcParams,
        List<String> sheetNames,
        Boolean sheetMerge,
        List<String> extraCatalogs) {
}
