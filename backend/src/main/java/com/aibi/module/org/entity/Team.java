package com.aibi.module.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 团队实体（映射表 {@code team}，租户隔离表，CRUD 自动注入 {@code tenant_id}）。
 *
 * <p>团队是组织内的部门/项目组，也是<b>资源归属与可见性收敛的单位</b>（见设计文档 2.1）：
 * {@code metric}/{@code dashboard}/{@code data_source} 等资源的 {@code team_id} 指向本表，
 * {@code visibility=TEAM} 时仅本团队可见。
 *
 * <p>{@code parent_id} 构成树形；{@code TEAM_AND_CHILD} 数据范围据此展开子团队集合。
 * 每个组织在回填时都会获得一个 {@code code=default} 的默认团队（个人版即"单人组织 + 默认团队"），
 * 该团队不可删除、不可改码，用于承接未显式归属团队的资源与成员。
 */
@TableName("team")
public class Team {

    /** 默认团队编码：组织创建/回填时自动生成，不可删除、不可改码。 */
    public static final String CODE_DEFAULT = "default";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织，隔离键。 */
    private Long tenantId;
    /** 父团队（NULL=顶级）。 */
    private Long parentId;
    private String name;
    /** 组织内唯一编码。 */
    private String code;
    /** 团队负责人 uid（{@code app_user.id}）。 */
    private Long ownerUid;
    private String description;
    /** 1=正常 0=停用。 */
    private Integer status;
    /** 同级排序。 */
    private Integer sort;
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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getOwnerUid() {
        return ownerUid;
    }

    public void setOwnerUid(Long ownerUid) {
        this.ownerUid = ownerUid;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
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

    public boolean isDefault() {
        return CODE_DEFAULT.equals(code);
    }

    public boolean isEnabled() {
        return status == null || status == 1;
    }
}
