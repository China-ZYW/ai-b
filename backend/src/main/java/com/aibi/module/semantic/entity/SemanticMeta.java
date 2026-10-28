package com.aibi.module.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 语义层元数据实体（MyBatis-Plus 映射表 {@code semantic_meta}，配置化升级 V2）。
 *
 * <p>为数据源的表/列补充"业务语义"：中文别名、业务描述、同义词、列角色（维度/度量/时间）、
 * 度量单位、枚举值采样与业务口径表达式。这些信息由管理员维护（或骨架自动初始化后补全），并注入
 * NL2SQL Prompt、SchemaLinker 相关性打分与图表角色判定——是问数/图表准确率的核心抓手。
 *
 * <p>{@code tenant_id} 由租户拦截器自动注入（本表为租户隔离表）。
 *
 * <p><b>字段级权限（2026-09-15）</b>：{@code sensitive} / {@code visibleRoles} 用于标记敏感列并声明
 * 可查看角色，由 {@code FieldPermissionService} 在问数结果层按当前角色脱敏。
 */
@TableName("semantic_meta")
public class SemanticMeta {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long datasourceId;
    private String objectType;
    private String tableName;
    private String columnName;
    private String aliasCn;
    private String description;
    private String synonyms;
    private String columnRole;
    private String unit;
    private String enumValues;
    /**
     * 枚举字典（码值 → 中文标签的 JSON 对象，如 {@code {"1":"已支付","2":"已取消"}}），与
     * {@code enum_values} 的取值列表配套。业务库里 status/type/level 这类列常以 1234 存码值，
     * 只采样取值模型无从得知含义；配了字典后 Prompt 会输出「枚举:1,2,3,4；字典:1=已支付，2=已取消」。
     * 空=未配置（Prompt 行为同升级前）。
     */
    private String enumLabels;
    private String sqlExpr;
    private Integer enabled;
    /**
     * 是否敏感列：1=需角色可见，0/null=普通（无字段级脱敏）。
     * <p><b>注意</b>：{@code SENSITIVE} 是 MySQL 保留字，MyBatis-Plus 默认不会给列名加反引号，
     * 因此必须用 {@code @TableField("`sensitive`")} 显式加反引号，否则所有 MP 生成的
     * SELECT/INSERT/UPDATE 都会报 {@code BadSqlGrammarException}（near 'sensitive'）。
     */
    @TableField("`sensitive`")
    private Integer sensitive;
    /** 允许查看的角色码（逗号分隔，如 OWNER,ADMIN,DATA_STEWARD）；空=仅 OWNER/ADMIN 可见。 */
    private String visibleRoles;
    /** 来源标记：MANUAL=人工维护 / SKELETON=骨架自动初始化 / AI=指标后台自动分析 Skill 识别；空=人工维护。 */
    private String source;
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

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getAliasCn() {
        return aliasCn;
    }

    public void setAliasCn(String aliasCn) {
        this.aliasCn = aliasCn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(String synonyms) {
        this.synonyms = synonyms;
    }

    public String getColumnRole() {
        return columnRole;
    }

    public void setColumnRole(String columnRole) {
        this.columnRole = columnRole;
    }

    /** 度量单位（元/件/%/次 …）：图表轴名与数值标注据此拼接，一处维护全租户图表复用。 */
    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getEnumValues() {
        return enumValues;
    }

    public void setEnumValues(String enumValues) {
        this.enumValues = enumValues;
    }

    public String getEnumLabels() {
        return enumLabels;
    }

    public void setEnumLabels(String enumLabels) {
        this.enumLabels = enumLabels;
    }

    public String getSqlExpr() {
        return sqlExpr;
    }

    public void setSqlExpr(String sqlExpr) {
        this.sqlExpr = sqlExpr;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getSensitive() {
        return sensitive;
    }

    public void setSensitive(Integer sensitive) {
        this.sensitive = sensitive;
    }

    public String getVisibleRoles() {
        return visibleRoles;
    }

    public void setVisibleRoles(String visibleRoles) {
        this.visibleRoles = visibleRoles;
    }

    /** 来源标记：MANUAL=人工维护 / SKELETON=骨架自动初始化 / AI=指标后台自动分析 Skill 识别；空=人工维护。 */
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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
