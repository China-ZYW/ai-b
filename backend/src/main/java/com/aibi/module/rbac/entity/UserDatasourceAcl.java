package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 成员直授 · 数据源可见性（映射表 {@code user_datasource_acl}，设计文档《组织治理专项设计》§3）。
 *
 * <p>黑名单模型：无行 = 可见；{@code effect=HIDDEN} 才隐藏（schema 省略），{@code ALLOW} 为显式放行。
 * 与角色继承表 {@code role_datasource_acl} 合并解析时取最宽（ALLOW 胜 HIDDEN）。
 *
 * <p>本表带 {@code tenant_id}，由租户拦截器自动注入隔离条件。
 */
@TableName("user_datasource_acl")
public class UserDatasourceAcl {

    /** 隐藏数据源（schema 省略）。 */
    public static final String EFFECT_HIDDEN = "HIDDEN";
    /** 显式放行（覆盖 HIDDEN）。 */
    public static final String EFFECT_ALLOW = "ALLOW";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long uid;
    private Long datasourceId;
    private String effect;
    private Long grantedBy;
    private LocalDateTime createdAt;

    public UserDatasourceAcl() {
    }

    public UserDatasourceAcl(Long tenantId, Long uid, Long datasourceId, String effect, Long grantedBy) {
        this.tenantId = tenantId;
        this.uid = uid;
        this.datasourceId = datasourceId;
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

    public boolean isHidden() {
        return EFFECT_HIDDEN.equals(effect);
    }

    public boolean isAllow() {
        return EFFECT_ALLOW.equals(effect);
    }
}
