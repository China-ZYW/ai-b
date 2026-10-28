package com.aibi.module.dac.dto;

/**
 * 数据授权项视图（设计文档《组织治理专项设计》§3）。
 *
 * <p>合并「成员直授」与「角色继承」两类、数据源可见性 / 字段脱敏 / 表级可见性三类，
 * 前端按 {@code targetType}/{@code kind} 分组呈现。
 */
public record DataAclItemVO(
        /** 授权主体类型：USER / ROLE。 */
        String targetType,
        /** 成员 uid 或自定义角色 id。 */
        Long targetId,
        /** 数据源 id。 */
        Long datasourceId,
        /** 字段名；数据源/表级可见性项为 null。 */
        String columnName,
        /** 表名；数据源/字段脱敏项为 null。 */
        String tableName,
        /** 效果：HIDDEN/ALLOW（数据源/表）或 MASK/UNMASK（字段）。 */
        String effect,
        /** 种类：DATASOURCE（可见性）/ FIELD（脱敏）/ TABLE（表级可见性）。 */
        String kind
) {
}
