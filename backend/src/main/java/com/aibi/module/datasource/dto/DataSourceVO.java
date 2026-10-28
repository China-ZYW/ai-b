package com.aibi.module.datasource.dto;

import java.util.List;

/**
 * 数据源视图对象（对外安全，一律不含密码或密文）。
 *
 * @param hasPassword 是否已配置凭证（用于前端展示"已设置/未设置"，不回填明文）
 * @param sheetNames  文件类数据源已勾选的工作表名；空/null 表示导入全部（前端编辑时据此回显勾选态）
 * @param sheetMerge  文件类数据源是否合并列结构完全一致的工作表
 * @param extraCatalogs 数据库类数据源额外扫表的库/模式名；空 = 只扫主库（前端编辑时据此回显）
 * @param jdbcParams    额外连接参数（k=v&k=v）。对象存储类复用传 region（如 region=us-east-1）；
 *                      前端编辑时据此回显并解析出 region，避免保存时丢失
 * @param lastSchemaSyncAt 最近一次表结构同步时间（null=从未同步）；前端据此提示结构可能过期
 */
public record DataSourceVO(
        Long id,
        String name,
        String type,
        String host,
        Integer port,
        String database,
        String username,
        String filePath,
        boolean hasPassword,
        List<String> sheetNames,
        boolean sheetMerge,
        List<String> extraCatalogs,
        String jdbcParams,
        java.time.LocalDateTime lastSchemaSyncAt) {
}
