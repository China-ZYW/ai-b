package com.aibi.module.chart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 图表推荐规则实体（MyBatis-Plus 映射表 {@code chart_rule}，配置化升级 V2）。
 *
 * <p>两类规则：{@code KEYWORD}=问题关键词 → 目标图型（pattern 支持逗号分隔同义词组，
 * priority 小者先匹配）；{@code THRESHOLD}=结构阈值覆盖（pattern=阈值键，priority=阈值值，
 * 如 max-pie-slices/max-measures）。
 *
 * <p>{@code tenant_id} 可空（NULL=全局规则），本表列入租户拦截器 IGNORE 名单，
 * 由 {@code ChartRuleService} 显式按"租户行覆盖全局行"解析（同 llm_config 先例）。
 */
@TableName("chart_rule")
public class ChartRule {

    public static final String TYPE_KEYWORD = "KEYWORD";
    public static final String TYPE_THRESHOLD = "THRESHOLD";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String ruleType;
    private String pattern;
    private String chartType;
    private Integer priority;
    private Integer enabled;
    private String description;
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

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getChartType() {
        return chartType;
    }

    public void setChartType(String chartType) {
        this.chartType = chartType;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
