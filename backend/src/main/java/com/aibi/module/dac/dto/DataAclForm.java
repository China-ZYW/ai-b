package com.aibi.module.dac.dto;

/**
 * 设置数据授权表单（设计文档《组织治理专项设计》§3）。
 *
 * <p>{@code targetType=USER} 时 {@code targetId} 为成员 uid；{@code =ROLE} 时为自定义角色 id。
 * 三选一决定授权维度：{@code tableName} 非空 → 表级可见性（HIDDEN/ALLOW）；
 * {@code columnName} 非空 → 字段脱敏（MASK/UNMASK）；二者皆空 → 数据源可见性（HIDDEN/ALLOW）。
 */
public record DataAclForm(
        /** 授权主体类型：USER（成员直授）/ ROLE（角色继承）。 */
        String targetType,
        /** 成员 uid 或自定义角色 id。 */
        Long targetId,
        /** 数据源 id。 */
        Long datasourceId,
        /** 字段名；与 {@code tableName} 互斥，二者皆空表示「数据源可见性」。 */
        String columnName,
        /** 表名；非空表示「表级可见性」（与字段脱敏互斥）。 */
        String tableName,
        /** 效果：数据源/表维度 HIDDEN/ALLOW；字段维度 MASK/UNMASK。 */
        String effect
) {
}
