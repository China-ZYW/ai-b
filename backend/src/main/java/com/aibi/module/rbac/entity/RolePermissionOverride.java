package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 自定义角色权限增减（映射表 {@code role_permission_override}，设计文档《组织治理专项设计》§2.2）。
 *
 * <p>每行描述「某自定义角色对某个权限点是 GRANT（增）还是 DENY（减）」。有效权限 =
 * {@code base 权限 ∪ GRANT − DENY}，<b>DENY 优先且不可被 base 的 GRANT 反转</b>
 * （见 {@code metadata-schema.sql} 注释）。GRANT 仅允许落在 base 角色已有的权限点内，
 * 服务层据此保证「窄化不宽于 base」的不变式。
 */
@TableName("role_permission_override")
public class RolePermissionOverride {

    /** 增：在 base 权限基础上放开（仅限 base 内，否则被服务层拒绝）。 */
    public static final String EFFECT_GRANT = "GRANT";
    /** 减：从 base 权限中移除（优先于 base 的授予）。 */
    public static final String EFFECT_DENY = "DENY";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long customRoleId;
    private String permissionCode;
    private String effect;
    private LocalDateTime createdAt;

    public RolePermissionOverride() {
    }

    public RolePermissionOverride(Long tenantId, Long customRoleId, String permissionCode, String effect) {
        this.tenantId = tenantId;
        this.customRoleId = customRoleId;
        this.permissionCode = permissionCode;
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

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
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

    /** 是否为 DENY 行。 */
    public boolean isDeny() {
        return EFFECT_DENY.equals(effect);
    }

    /** 是否为 GRANT 行。 */
    public boolean isGrant() {
        return EFFECT_GRANT.equals(effect);
    }
}
