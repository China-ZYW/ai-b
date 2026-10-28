package com.aibi.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 业务术语词典实体（MyBatis-Plus 映射表 {@code term_map}）。
 *
 * <p>把用户口语词（{@code term}，如"流水""营收额"）映射到规范名（{@code canonical}，如"营收"），
 * 在技能路由之前对提问做归一化，使指标直达 / 样例复用 / 数据字典问答等<b>免 LLM 技能</b>命中率提升——
 * 用户说"本月流水多少"也能命中已沉淀的指标"营收"，从而跳过一次大模型调用、口径与指标卡一致。
 *
 * <p>{@code tenant_id} 由租户拦截器自动注入（本表为租户隔离表）；词典按数据源维度维护，
 * 避免不同库的"流水"指向不同规范口径时串义。
 */
@TableName("term_map")
public class TermMap {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long datasourceId;
    private String term;
    private String canonical;
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

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getCanonical() {
        return canonical;
    }

    public void setCanonical(String canonical) {
        this.canonical = canonical;
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
