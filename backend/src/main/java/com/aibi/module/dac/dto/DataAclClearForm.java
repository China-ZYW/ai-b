package com.aibi.module.dac.dto;

/**
 * 清除数据授权表单（设计文档《组织治理专项设计》§3）。语义与 {@link DataAclForm} 对齐，
 * 仅去掉 {@code effect}（清除即删行，恢复默认：可见 / 按语义层默认掩码）。
 */
public record DataAclClearForm(
        /** 授权主体类型：USER / ROLE。 */
        String targetType,
        /** 成员 uid 或自定义角色 id。 */
        Long targetId,
        /** 数据源 id。 */
        Long datasourceId,
        /** 字段名；与 {@code tableName} 互斥，二者皆空表示「数据源可见性」。 */
        String columnName,
        /** 表名；非空表示「表级可见性」。 */
        String tableName
) {
}
