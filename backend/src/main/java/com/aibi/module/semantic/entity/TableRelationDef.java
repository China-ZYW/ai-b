package com.aibi.module.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 表关联登记实体（MyBatis-Plus 映射表 {@code table_relation}）：人工维护的"哪两张表用哪对键 JOIN"权威口径。
 *
 * <p><b>为什么需要它</b>：语义层原本只有表级与列级两级语义，缺第三级「关系语义」。多表问数下 LLM 知道
 * "可以 JOIN"，却不知道"该用哪个键 JOIN"——库中没建外键约束时（业务库与分析型库的常态）它只能拿同名列硬凑
 * （{@code orders.id = order_item.id}）或逗号连接产生笛卡尔积；而一对多关联后直接 SUM 会造成金额翻倍这类
 * "SQL 跑通、结果非空、数值错"的<b>静默错数</b>，比报错更难发现。
 *
 * <p>命名推断（{@code JoinGraph}）能补上一部分，但它是启发式、可能推错，且推不出多对多。本表的行以
 * <b>最高可信度</b>并入关联图谱：同一对连接键既被登记又被推断时，只保留登记的口径与基数声明。
 *
 * <p>{@code tenant_id} 由租户拦截器自动注入（本表为租户隔离表）。
 */
@TableName("table_relation")
public class TableRelationDef {

    /** 多对一：左表多行对应右表一行（最常见，也是 fan-out 的来源）。 */
    public static final String TYPE_MANY_TO_ONE = "MANY_TO_ONE";
    /** 一对一：关联不放大行数。 */
    public static final String TYPE_ONE_TO_ONE = "ONE_TO_ONE";
    /** 多对多：两侧行数都会翻倍，聚合前必须先去重（只能由人工声明）。 */
    public static final String TYPE_MANY_TO_MANY = "MANY_TO_MANY";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long datasourceId;
    /** 多方表名（持有连接键的一侧）。跨库联合分析下须与提示词里的呈现形式一致（带「库.」前缀）。 */
    private String leftTable;
    private String leftColumn;
    /** 一方表名。 */
    private String rightTable;
    private String rightColumn;
    /** 基数：MANY_TO_ONE / ONE_TO_ONE / MANY_TO_MANY。 */
    private String relationType;
    /** 业务口径说明（为何这样关联），随【关联关系】段一并进 Prompt 时可帮 LLM 理解业务含义。 */
    private String description;
    private Integer enabled;
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

    public String getLeftTable() {
        return leftTable;
    }

    public void setLeftTable(String leftTable) {
        this.leftTable = leftTable;
    }

    public String getLeftColumn() {
        return leftColumn;
    }

    public void setLeftColumn(String leftColumn) {
        this.leftColumn = leftColumn;
    }

    public String getRightTable() {
        return rightTable;
    }

    public void setRightTable(String rightTable) {
        this.rightTable = rightTable;
    }

    public String getRightColumn() {
        return rightColumn;
    }

    public void setRightColumn(String rightColumn) {
        this.rightColumn = rightColumn;
    }

    public String getRelationType() {
        return relationType;
    }

    public void setRelationType(String relationType) {
        this.relationType = relationType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
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
