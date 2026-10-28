package com.aibi.module.lineage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 数据血缘边（轻量实体关系表，不做 ETL 任务血缘）。
 *
 * <p><b>为什么单独建表而不是实时反查</b>：指标口径表达式、看板布局都是 JSON/自由文本，
 * 实时反查需要全表扫描 + JSON 解析，语义管理每翻一页就会被拖垮。这里把"引用关系"
 * 在指标保存 / 语义变更时解析一次固化成边，读期全部是单表按 id 查，负载可忽略。
 *
 * <p><b>租户隔离</b>：血缘属平台元数据，但必须按租户隔离——否则 A 租户能看到 B 租户的
 * 指标血缘。本表显式带 {@code tenantId}，且所有查询都显式拼接租户条件，不依赖拦截器的
 * 表名单（依赖名单一旦漏配就是跨租户泄漏）。
 *
 * <p><b>边方向</b>：{@code from → to} 表示"from 依赖/引用 to"。
 * 例：metric → field（depends_on）、field → table（source）、dashboard → metric（references）。
 */
@TableName("lineage_edge")
public class LineageEdge {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** 起点实体类型：metric / field / table / dashboard / alert。 */
    private String fromType;
    private Long fromId;
    /** 终点实体类型。 */
    private String toType;
    private Long toId;
    /** 关系：depends_on（指标依赖字段）/ source（字段来源表）/ references（看板引用指标）。 */
    private String relation;
    /** 冗余展示名（如指标名/字段名/看板名），避免读期再 join 多张表。 */
    private String fromName;
    private String toName;
    private LocalDateTime createdAt;

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

    public String getFromType() {
        return fromType;
    }

    public void setFromType(String fromType) {
        this.fromType = fromType;
    }

    public Long getFromId() {
        return fromId;
    }

    public void setFromId(Long fromId) {
        this.fromId = fromId;
    }

    public String getToType() {
        return toType;
    }

    public void setToType(String toType) {
        this.toType = toType;
    }

    public Long getToId() {
        return toId;
    }

    public void setToId(Long toId) {
        this.toId = toId;
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getToName() {
        return toName;
    }

    public void setToName(String toName) {
        this.toName = toName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
