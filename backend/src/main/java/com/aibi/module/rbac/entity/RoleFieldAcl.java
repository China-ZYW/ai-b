package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 角色继承 · 字段脱敏（映射表 {@code role_field_acl}，设计文档《组织治理专项设计》§3）。
 *
 * <p>挂在自定义角色 {@code custom_role} 上：拥有该角色的全部成员继承其字段脱敏策略，
 * 与成员直授表 {@code user_field_acl} 合并解析时取最宽（UNMASK 胜 MASK）。
 *
 * <p>本表带 {@code tenant_id}，由租户拦截器自动注入隔离条件。
 */
@TableName("role_field_acl")
public class RoleFieldAcl {

    public static final String EFFECT_MASK = "MASK";
    public static final String EFFECT_UNMASK = "UNMASK";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long customRoleId;
    private Long datasourceId;
    private String columnName;
    /** 表名（库前缀可选）；NULL=作用于该数据源所有同名列（全局列名级），非 NULL=特定表.列级。 */
    @TableField("table_name")
    private String tableName;
    private String effect;
    private LocalDateTime createdAt;

    public RoleFieldAcl() {
    }

    public RoleFieldAcl(Long tenantId, Long customRoleId, Long datasourceId, String tableName, String columnName, String effect) {
        this.tenantId = tenantId;
        this.customRoleId = customRoleId;
        this.datasourceId = datasourceId;
        this.tableName = tableName;
        this.columnName = columnName;
        this.effect = effect;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getCustomRoleId() {
        return customRoleId;
    }

    public void setCustomRoleId(Long customRoleId) {
        this.customRoleId = customRoleId;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isUnmask() {
        return EFFECT_UNMASK.equals(effect);
    }

    public boolean isMask() {
        return EFFECT_MASK.equals(effect);
    }
}
