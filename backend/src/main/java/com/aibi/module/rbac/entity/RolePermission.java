package com.aibi.module.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 角色→权限点关联实体（映射表 {@code role_permission}，全局表，列入租户拦截器 IGNORE 名单）。
 *
 * <p>只承载<b>内置角色</b>的授权事实源；租户自定义角色的增减走 {@code role_permission_override}
 * （DENY 优先，见设计文档 3.5）。用角色码而非角色 id 关联，使字典播种与代码常量可直接比对，
 * 不依赖自增 id 的稳定性。
 */
@TableName("role_permission")
public class RolePermission {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String roleCode;
    private String permissionCode;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
