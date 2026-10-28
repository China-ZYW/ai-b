package com.aibi.module.dashboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 资源分组实体（映射表 {@code resource_group}）：多层级目录树，驾驶舱与指标库<b>共用一套分组机制</b>。
 *
 * <p><b>为什么要加 {@code resourceType}</b>：分组是"目录容器"，但驾驶舱与指标是两类不同资源——
 * 各自需要一棵独立的树（把指标塞进"经营分析"这种看板目录没有意义）。共用一张表、一个服务，
 * 靠 {@code resourceType} 分域（{@code DASHBOARD} / {@code METRIC}），比复制两份表与服务更不容易漂移。
 * <b>该表此前名为 {@code dashboard_group}，因尚未在任何生产库建过（迁移从未执行）而得以自由重命名，
 * 无数据迁移负担。</b>
 *
 * <p>{@code parentId} 为空即根级；移动时禁止挂到自身或后代（成环检测，见 {@code ResourceGroupService}）。
 * 表为租户隔离表，{@code tenantId} 由租户拦截器自动注入（勿手动设置）。
 */
@TableName("resource_group")
public class ResourceGroup {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** 创建者 uid（展示/归属记录用；分组为组织级可见，不做个人私有）。 */
    private Long uid;
    /**
     * 分组所属资源域：{@code DASHBOARD} / {@code METRIC}
     * （取值见 {@code ResourceGroupService} 的 {@code TYPE_*} 常量）。
     * 所有查询与校验都带此条件——否则一个域的接口能改到另一个域的分组。
     */
    private String resourceType;
    private String name;
    /** 父分组 id（空=根级）。 */
    private Long parentId;
    /** 同级展示顺序（升序）。 */
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

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
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
}
