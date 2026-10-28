package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 成员直授 · 字段脱敏（映射表 {@code user_field_acl}，设计文档《组织治理专项设计》§3）。
 *
 * <p>白名单模型：无行 = 按语义层 {@code sensitive} 默认掩码（fail-closed）；{@code UNMASK} 为显式放行，
 * 把原本会被掩码的敏感列对<b>该成员</b>放开。{@code MASK} 为显式封堵（等同默认但可写为策略）。
 * 与角色继承表 {@code role_field_acl} 合并解析时取最宽（UNMASK 胜 MASK）。
 *
 * <p>本表带 {@code tenant_id}，由租户拦截器自动注入隔离条件。
 */
@TableName("user_field_acl")
public class UserFieldAcl {

    /** 显式封堵（等同默认，可写为策略）。 */
    public static final String EFFECT_MASK = "MASK";
    /** 显式放行（覆盖默认掩码）。 */
    public static final String EFFECT_UNMASK = "UNMASK";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long uid;
    private Long datasourceId;
    private String columnName;
    /** 表名（库前缀可选）；NULL=作用于该数据源所有同名列（全局列名级），非 NULL=特定表.列级。 */
    @TableField("table_name")
    private String tableName;
    private String effect;
    private Long grantedBy;
    private LocalDateTime createdAt;

    public UserFieldAcl() {
    }

    public UserFieldAcl(Long tenantId, Long uid, Long datasourceId, String tableName, String columnName, String effect, Long grantedBy) {
        this.tenantId = tenantId;
        this.uid = uid;
        this.datasourceId = datasourceId;
        this.tableName = tableName;
        this.columnName = columnName;
        this.effect = effect;
        this.grantedBy = grantedBy;
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

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
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

    public Long getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(Long grantedBy) {
        this.grantedBy = grantedBy;
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
