package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 租户自定义角色（映射表 {@code custom_role}，设计文档《组织治理专项设计》§2）。
 *
 * <p>派生自一个内置组织角色（{@code baseRoleCode}），其有效权限集必须<b>窄化不宽于</b> base：
 * 有效 = base 权限 ∪ GRANT(仅 base 内) − DENY，由 {@code role_permission_override} 承载差异。
 * 内置角色字典本身只读，组织管理员只能建/改/删本表的租户自定义角色。
 *
 * <p>本表带 {@code tenant_id}，由租户拦截器自动注入隔离条件（未列入 IGNORE 名单），
 * 所有读写均在本组织上下文内发生。
 */
@TableName("custom_role")
public class CustomRole {

    /** 启用。 */
    public static final int STATUS_ENABLED = 1;
    /** 停用（仅作软开关，不影响已授予成员，重新启用即恢复）。 */
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织（隔离键）。 */
    private Long tenantId;
    /** 角色名称，租户内唯一（uk_tenant_name）。 */
    private String name;
    /** 派生自哪个内置组织角色（角色码）。 */
    private String baseRoleCode;
    /** 数据范围（可窄化，不可宽于 base）；v1 解析沿用 base 角色的 data_scope，此处仅作登记。 */
    private String dataScope;
    private String description;
    private Integer enabled;
    private Long createdBy;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseRoleCode() {
        return baseRoleCode;
    }

    public void setBaseRoleCode(String baseRoleCode) {
        this.baseRoleCode = baseRoleCode;
    }

    public String getDataScope() {
        return dataScope;
    }

    public void setDataScope(String dataScope) {
        this.dataScope = dataScope;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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

    /** 是否启用（默认启用）。 */
    public boolean isEnabled() {
        return enabled == null || enabled == STATUS_ENABLED;
    }
}
