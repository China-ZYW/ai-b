package com.aibi.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 智能预警触发记录（映射表 {@code alert_record}，租户隔离表）。
 *
 * <p>规则越界时由 {@code AlertEngineService.fire} 写入一条 {@code FIRED} 记录，并标记 {@code notified}；
 * 后续处置（ACK/RESOLVED）可由前端更新 {@code status}（本期仅落库，处置接口留作下一步）。
 *
 * <p>本表<b>不在</b>租户拦截器 IGNORE 名单，CRUD 自动注入 {@code tenant_id}，天然按租户隔离。
 */
@TableName("alert_record")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织（自动注入）。 */
    private Long tenantId;
    /** 命中规则 id。 */
    private Long ruleId;
    /** 命中规则名（冗余，便于记录独立可读）。 */
    private String ruleName;
    /** 触发指标键。 */
    private String metricKey;
    /** 触发时实测值。 */
    private BigDecimal value;
    /** 比较算子（GT/LT/GTE/LTE）。 */
    private String operator;
    /** 阈值。 */
    private BigDecimal threshold;
    /** INFO/WARN/CRITICAL。 */
    private String severity;
    /** FIRED/ACK/RESOLVED。 */
    private String status;
    /** 触发说明。 */
    private String message;
    /** 是否已通知（当前仅 LOG，通知即置 1）。 */
    private Boolean notified;
    /**
     * 触发时预跑的归因摘要（P1，{@code AlertAttributionVO} 序列化 JSON）。
     *
     * <p>仅在规则开启「自动归因」且指标可归因时写入；存量记录与未开启的规则为 NULL。
     * 落 JSON 而非拆列：归因结论是"一份结果"（维度/期间/总变化/头部贡献/解读），
     * 拆成十几列既难扩展也无法随归因 VO 演进而兼容。读取时由 {@code AlertRecordVO} 反序列化回结构化对象。
     */
    private String attribution;
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

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getNotified() {
        return notified;
    }

    public void setNotified(Boolean notified) {
        this.notified = notified;
    }

    public String getAttribution() {
        return attribution;
    }

    public void setAttribution(String attribution) {
        this.attribution = attribution;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
