package com.aibi.module.dashboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 指标实体（映射表 {@code metric}）：把一次问数结果保存为可复用图表指标。
 *
 * <p>{@code metric} 为租户隔离表，{@code tenant_id} 由租户拦截器自动注入（勿手动设置）；{@code uid} 显式携带创建者。
 * 刷新时直接重跑已存 {@code sql_text}（经 {@code SqlValidator} 复校），不消耗 LLM、不计入问数配额。
 * {@code chart_spec} 存图表规格 JSON（前端 {@code buildChartOption} 可直接消费）。
 *
 * <p>{@code display_config} 存结果展示配置 JSON（当前仅 {@code columnLabels}：物理列名 → 自定义展示名）。
 * 汇总/派生列（{@code SUM(amount) AS total_amount}）在语义层没有对应物理列行，靠这里按指标自定义叫法；
 * 展示名优先级为「指标级 &gt; 语义层别名 &gt; 物理列名」，且只影响呈现，字段身份仍用物理列名。
 */
@TableName("metric")
public class Metric {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long uid;
    private String name;
    private String description;
    private String alias;
    /**
     * 所属分组（{@code resource_group.id}，与驾驶舱共用同一张分组表，本侧固定 resource_type=METRIC；空=未分组）。
     * 移动归属走专用端点，不经指标编辑的全量 update（后者会静默清空未回传字段）。
     */
    private Long groupId;
    /** canonical 指标口径表达式（如 {@code SUM(amount)}）；趋势/量价因子/维度归因三处共用此单一口径，避免口径漂移。空=未沉淀口径（归因前需补全）。 */
    private String definition;
    /** 归因默认维度（物理列名，P1：保存指标时一并沉淀，下次打开自动执行归因）。空=由用户选择。 */
    private String attributionDimension;
    /** 归因默认对比基准（MOM/YOY/PEAK，P1）。空=回落 MOM。 */
    private String baselineType;
    /**
     * 数据最后成功刷新时间（P2 新鲜度）。**不能与 {@code updatedAt} 混用**：后者在改别名/改口径等
     * 纯元数据编辑时也会刷新，若用它判新鲜度会出现"数据三天没拉、页面显示刚刚更新"的误导。
     * NULL=从未刷新过（与"已过期"区分，前端按 never 单独呈现）。仅 {@code refresh()} 成功取数后写入。
     */
    private LocalDateTime refreshedAt;
    /** 该指标的新鲜度 SLA 覆盖值（小时，P2）。空=回落全局 {@code FRESHNESS / sla-hours}。 */
    private Integer freshnessHours;
    private Long datasourceId;
    private String question;
    private String sqlText;
    /** 来源标记：AI 自动分析生成='AI'；NULL/空=人工维护（MANUAL）。前端据此展示 AI 自动识别标识。 */
    private String source;
    private String chartSpec;
    private String displayConfig;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getAttributionDimension() {
        return attributionDimension;
    }

    public void setAttributionDimension(String attributionDimension) {
        this.attributionDimension = attributionDimension;
    }

    public String getBaselineType() {
        return baselineType;
    }

    public void setBaselineType(String baselineType) {
        this.baselineType = baselineType;
    }

    public LocalDateTime getRefreshedAt() {
        return refreshedAt;
    }

    public void setRefreshedAt(LocalDateTime refreshedAt) {
        this.refreshedAt = refreshedAt;
    }

    public Integer getFreshnessHours() {
        return freshnessHours;
    }

    public void setFreshnessHours(Integer freshnessHours) {
        this.freshnessHours = freshnessHours;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getChartSpec() {
        return chartSpec;
    }

    public void setChartSpec(String chartSpec) {
        this.chartSpec = chartSpec;
    }

    public String getDisplayConfig() {
        return displayConfig;
    }

    public void setDisplayConfig(String displayConfig) {
        this.displayConfig = displayConfig;
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
