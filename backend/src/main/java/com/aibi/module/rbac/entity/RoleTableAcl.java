package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 角色继承 · 表级可见性（映射表 {@code role_table_acl}，设计文档《组织治理专项设计》§3，v2）。
 *
 * <p>挂在自定义角色 {@code custom_role} 上：拥有该角色的全部成员继承其表级可见性策略，
 * 与成员直授表 {@code user_table_acl} 合并解析时取最宽（ALLOW 胜 HIDDEN）。
 *
 * <p>本表带 {@code tenant_id}，由租户拦截器自动注入隔离条件。
 */
@TableName("role_table_acl")
public class RoleTableAcl {

    public static final String EFFECT_HIDDEN = "HIDDEN";
    public static final String EFFECT_ALLOW = "ALLOW";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long customRoleId;
    private Long datasourceId;
    private String tableName;
    private String effect;
    private LocalDateTime createdAt;

    public RoleTableAcl() {
    }

    public RoleTableAcl(Long tenantId, Long customRoleId, Long datasourceId, String tableName, String effect) {
        this.tenantId = tenantId;
        this.customRoleId = customRoleId;
        this.datasourceId = datasourceId;
        this.tableName = tableName;
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

    public boolean isHidden() {
        return EFFECT_HIDDEN.equals(effect);
    }

    public boolean isAllow() {
        return EFFECT_ALLOW.equals(effect);
    }
}
