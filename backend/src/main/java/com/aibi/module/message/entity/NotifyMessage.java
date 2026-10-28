package com.aibi.module.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;

import java.time.LocalDateTime;

/**
 * 站内信（映射表 {@code notify_message}，租户隔离表）。
 *
 * <p>当前唯一写入来源是预警引擎的 {@code INAPP} 渠道：规则命中时按 {@code alert_rule.notify_target}
 * （用户 id 列表，逗号/分号分隔）逐一写一条，每人一条。不做租户全员广播——只发给规则指定的人。
 *
 * <p>本表带 {@code tenant_id} 列且不在租户拦截器 IGNORE 名单，CRUD 自动注入 {@code tenant_id}，
 * 收件箱接口再以 {@code uid} 二次收敛，保证"只能读到自己的信"。
 *
 * <p>{@code is_read} 用 {@code TINYINT} 而非 Boolean 列。Java 属性名必须为 {@code isRead} 而不能是 {@code read}：
 * MyBatis-Plus 在 {@code column(is_read) != property(read)} 时会生成 {@code SELECT ... is_read AS read}，
 * 而 {@code read} 是 MySQL 保留字会直接报 SQL 语法错；用 {@code isRead} 别名即非保留字，正常。
 */
@TableName("notify_message")
public class NotifyMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织（自动注入）。 */
    private Long tenantId;
    /** 接收人 {@code app_user.id}。 */
    private Long uid;
    /** 来源预警规则 id（非预警写入时为 NULL）。 */
    private Long ruleId;
    /** 来源预警触发记录 id（非预警写入时为 NULL）。 */
    private Long recordId;
    /** INFO/WARN/CRITICAL。 */
    private String severity;
    /** 站内信标题。 */
    private String title;
    /** 站内信正文。 */
    private String content;
    /** 0=未读 1=已读。列名 is_read；属性名用 isRead（避免 MP 别名 read 命中 MySQL 保留字）。 */
    @TableField("is_read")
    private Boolean isRead;
    /** 创建时间：写时由代码赋当前时间，更新操作不回写（避免 ON UPDATE 失效与冻结）。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
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

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
