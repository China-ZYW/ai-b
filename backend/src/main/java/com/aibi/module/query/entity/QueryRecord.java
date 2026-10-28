package com.aibi.module.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一次查询的执行记录（SQL 编辑器「历史」页，映射表 {@code query_history}）。
 *
 * <p>复用既有 {@code query_history} 而非新建表：它的列（tenant_id/uid/datasource_id/sql_text/
 * row_count/elapsed_ms/status）本就描述"一次查询跑完了什么"，SQL 编辑器只是新增了三种来源维度
 * （{@code skill_code} 技能命中、{@code chart_type} 采用的图型、{@code template_id} 是否来自模板）。
 *
 * <p>带 {@code tenant_id}，由租户拦截器自动注入隔离条件。
 */
@TableName("query_history")
public class QueryRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long uid;
    private Long datasourceId;
    private String question;
    /** 实际执行的 SQL（模板已渲染、占位符已绑定化之后的主干）。 */
    private String sqlText;
    private Integer rowCount;
    private Long elapsedMs;
    private String status;
    private String skillCode;
    private String chartType;
    private Long templateId;
    /** 构造器规格 JSON（BuilderSpec）；来自可视化构造器运行时才有，SQL 编辑器来源为 null。 */
    private String spec;
    private LocalDateTime createdAt;

    public QueryRecord() {
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

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSqlText() {
        return sqlText;
    }

    public void setSqlText(String sqlText) {
        this.sqlText = sqlText;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSkillCode() {
        return skillCode;
    }

    public void setSkillCode(String skillCode) {
        this.skillCode = skillCode;
    }

    public String getChartType() {
        return chartType;
    }

    public void setChartType(String chartType) {
        this.chartType = chartType;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
