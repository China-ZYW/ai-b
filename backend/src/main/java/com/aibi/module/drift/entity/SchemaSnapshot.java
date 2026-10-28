package com.aibi.module.drift.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 结构变更感知快照（T12-3，表 {@code schema_snapshot}）。
 *
 * <p>每数据源一行「上轮扫到的表→列集合」序列化（键值均小写归一），下轮重扫与之 diff。
 * {@code updated_at} 即最近一次成功感知时刻（diff 基准锚点）；租户隔离由拦截器自动注入。
 */
@TableName("schema_snapshot")
public class SchemaSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织（拦截器自动注入）。 */
    private Long tenantId;
    /** {@code data_source.id}。 */
    private Long datasourceId;
    /** 表→列集合序列化 JSON（小写归一，键=表名、值=列数组）。 */
    private String schemaJson;
    /** 表数（摘要展示用，免解 JSON）。 */
    private Integer tableCount;
    /** 列数（摘要展示用）。 */
    private Integer colCount;
    /** 创建时间：写时由代码赋当前时间。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    /** 最近一次成功扫描时刻（DB 端 ON UPDATE 维护）。 */
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

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getSchemaJson() {
        return schemaJson;
    }

    public void setSchemaJson(String schemaJson) {
        this.schemaJson = schemaJson;
    }

    public Integer getTableCount() {
        return tableCount;
    }

    public void setTableCount(Integer tableCount) {
        this.tableCount = tableCount;
    }

    public Integer getColCount() {
        return colCount;
    }

    public void setColCount(Integer colCount) {
        this.colCount = colCount;
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
