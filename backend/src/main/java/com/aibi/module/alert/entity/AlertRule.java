package com.aibi.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 智能预警规则（映射表 {@code alert_rule}，租户隔离表）。
 *
 * <p>每条规则表达「某指标（{@code metricKey}）当前值 经 {@code operator} 比较 {@code threshold} 即触发」，
 * 触发后写 {@code alert_record} 并经 {@code AlertNotifier} 通知（当前仅 LOG，见 F：通知渠道仅 LOG）。
 * 指标当前值由 {@code MetricValueResolver} 解析：内置支持 {@code query.count.24h} / {@code query.fail.count.24h} /
 * {@code query.fail.rate.24h} / {@code datasource.count}，并支持业务指标键 {@code metric:&lt;id&gt;}
 * （取指标库该指标查询结果首行首列数值）；未知 key 跳过（不报错）。
 *
 * <p>本表<b>不在</b>租户拦截器 IGNORE 名单，CRUD 自动注入 {@code tenant_id}，天然按租户隔离。
 */
@TableName("alert_rule")
public class AlertRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织（自动注入）。 */
    private Long tenantId;
    private String name;
    private String description;
    /** 指标键（如 query.count.24h）；由解析器决定如何取值。 */
    private String metricKey;
    /** GT/LT/GTE/LTE。 */
    private String operator;
    private BigDecimal threshold;
    /** INFO/WARN/CRITICAL。 */
    private String severity;
    /** 是否启用。 */
    private Boolean enabled;
    /** LOG/EMAIL/INAPP（已接入）/ SMS/WEBHOOK（扩展位）。 */
    private String notifyChannel;
    /**
     * 通知目标（随渠道而异）：EMAIL=收件人邮箱（逗号/分号分隔）；INAPP=接收人用户 id（逗号/分号分隔，
     * 见 {@code InAppAlertNotifier}，只发给指定用户）；预留 WEBHOOK=回调 URL、SMS=手机号。
     * 为空时该规则无法外发（引擎会把记录的 notified 记为 false）。
     */
    private String notifyTarget;
    /** 去重窗口（分钟）：窗口内已存在 FIRED 记录则不再重复触发。 */
    private Integer windowMinutes;
    /**
     * 触发时是否自动预跑归因（P1）：1=触发即跑一次单维归因并把结论随记录落库，
     * 让管理员收到告警的同时看到"是谁拖累的"。仅对业务指标键 {@code metric:<id>} 生效，
     * 内置平台键（query.count.24h 等）无法归因，会自动跳过。
     */
    private Boolean autoAttribution;
    /**
     * 归因维度（物理列名）；为空时回落到该指标在指标库沉淀的归因默认维度
     * （{@code metric.attribution_dimension}），两者皆空则不归因。
     */
    private String attributionDimension;
    /** 创建人 {@code app_user.id}。 */
    private Long createdBy;
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

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
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

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getNotifyChannel() {
        return notifyChannel;
    }

    public void setNotifyChannel(String notifyChannel) {
        this.notifyChannel = notifyChannel;
    }

    public String getNotifyTarget() {
        return notifyTarget;
    }

    public void setNotifyTarget(String notifyTarget) {
        this.notifyTarget = notifyTarget;
    }

    public Integer getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(Integer windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public Boolean getAutoAttribution() {
        return autoAttribution;
    }

    public void setAutoAttribution(Boolean autoAttribution) {
        this.autoAttribution = autoAttribution;
    }

    public String getAttributionDimension() {
        return attributionDimension;
    }

    public void setAttributionDimension(String attributionDimension) {
        this.attributionDimension = attributionDimension;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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
