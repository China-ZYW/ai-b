package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 角色授予实体（映射表 {@code user_role}）。
 *
 * <p><b>隔离特例</b>：本表带 {@code tenant_id}，却<b>必须</b>列入 {@code MybatisPlusConfig.IGNORE_TABLES}
 * ——登录链路要在 {@code TenantContext} 建立之前按 {@code uid} 反查角色，若自动注入 {@code tenant_id}
 * 则值为 NULL 恒不命中，导致"登录后角色为空集 → 全站 403"。列入后本表的每次读写都必须
 * 显式带 {@code tenant_id} 条件（平台角色固定为 {@code 0}），并在读出口用
 * {@code TenantAssertion.assertMatch} 兜底。
 *
 * <p>{@code scopeType}/{@code scopeId} 表达授予范围：TENANT（组织内全局生效，{@code scopeId=0}）
 * 或 TEAM（仅该团队及按 {@code inherit_to_child} 决定的子团队生效，{@code scopeId=teamId}）。
 */
@TableName("user_role")
public class UserRole {

    /** 授予范围：平台域（{@code tenant_id=0}）。 */
    public static final String SCOPE_PLATFORM = "PLATFORM";
    /** 授予范围：组织全域。 */
    public static final String SCOPE_TENANT = "TENANT";
    /** 授予范围：指定团队（{@code scopeId} = team_id）。 */
    public static final String SCOPE_TEAM = "TEAM";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织；平台角色为 0。 */
    private Long tenantId;
    /** {@code app_user.id}。 */
    private Long uid;
    private String roleCode;
    /** PLATFORM/TENANT/TEAM。 */
    private String scopeType;
    /** TEAM 时为 team_id；其余为 0。 */
    private Long scopeId;
    /** 非空表示授予的是租户自定义角色（{@code custom_role.id}），{@code roleCode} 存其 base 码。 */
    private Long customRoleId;
    private Long grantedBy;
    /** 临时授权到期时间（空=永久）；过期授权在解析时按无效处理。 */
    private LocalDateTime expireAt;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public Long getCustomRoleId() {
        return customRoleId;
    }

    public void setCustomRoleId(Long customRoleId) {
        this.customRoleId = customRoleId;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(Long grantedBy) {
        this.grantedBy = grantedBy;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 授权是否仍然有效：{@code status=1} 且未过期（临时授权到期即自动失效，无需清理任务）。 */
    public boolean isEffective(LocalDateTime now) {
        if (status != null && status != 1) {
            return false;
        }
        return expireAt == null || expireAt.isAfter(now);
    }

    public boolean isTeamScoped() {
        return SCOPE_TEAM.equals(scopeType);
    }
}
