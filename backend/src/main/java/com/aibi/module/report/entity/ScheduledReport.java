package com.aibi.module.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 定时报表任务（映射表 {@code scheduled_report}，租户隔离表，T12-1）。
 *
 * <p>把已存查询模板（{@code saved_query}）固化为日/周/小时定时任务：调度器按
 * {@link #freq} + {@link #runTime}/{@link #weekdays} 做纯函数到期判定（
 * {@code ReportDueJudge}），到点后以<b>创建人 {@link #uid}</b> 身份上下文重跑模板，
 * 结果经 {@code ReportRenderer} 渲染为文本并按 {@link #channel} 投递站内信/邮件。
 *
 * <p>本表不在 {@code IGNORE_TABLES}，{@code tenant_id} 由 MyBatis-Plus 拦截器自动注入；
 * 跨租户扫描入口见 {@code ScheduledReportMapper#selectAllEnabled}。
 *
 * <p>{@code lastRunAt} 既是展示位也是<b>到期幂等锚点</b>：跑过即早于本周期界，同分钟重复扫描天然去重。
 */
@TableName("scheduled_report")
public class ScheduledReport {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织（拦截器自动注入）。 */
    private Long tenantId;
    /** 创建人 {@code app_user.id}：执行时按其身份过模板归属校验。 */
    private Long uid;
    /** 报表名（(tenant_id, uid, name) 唯一，同模板口径）。 */
    private String name;
    /** 引用的 {@code saved_query.id}。 */
    private Long templateId;
    /** 模板参数固定值 JSON（{@code {"name":value}}）；空参模板留空。 */
    private String paramsJson;
    /** 预置频率闭集：DAILY / WEEKLY / HOURLY。 */
    private String freq;
    /** 执行时刻 {@code HH:mm}（DAILY/WEEKLY 用；HOURLY 忽略）。 */
    private String runTime;
    /** ISO 周几集合 {@code 1,3,5}（WEEKLY 用；其余忽略）。 */
    private String weekdays;
    /** 推送渠道：INAPP / EMAIL / BOTH（默认 INAPP）。 */
    private String channel;
    /** 站内信接收人 user id 逗号分隔；空=只发创建人。 */
    private String inappUids;
    /** 邮件收件人（EMAIL/BOTH 必填；分隔符宽容解析同告警口径）。 */
    private String emailTargets;
    /** 是否启用。 */
    private Boolean enabled;
    /** 上轮执行时刻（到期判定与幂等锚点）。 */
    private LocalDateTime lastRunAt;
    /** 上轮结果：OK / FAILED / SKIPPED（模板被删/越权）。 */
    private String lastStatus;
    /** 上轮失败摘要（只存异常 message，不含收件人）。 */
    private String lastError;
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

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    public String getFreq() {
        return freq;
    }

    public void setFreq(String freq) {
        this.freq = freq;
    }

    public String getRunTime() {
        return runTime;
    }

    public void setRunTime(String runTime) {
        this.runTime = runTime;
    }

    public String getWeekdays() {
        return weekdays;
    }

    public void setWeekdays(String weekdays) {
        this.weekdays = weekdays;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getInappUids() {
        return inappUids;
    }

    public void setInappUids(String inappUids) {
        this.inappUids = inappUids;
    }

    public String getEmailTargets() {
        return emailTargets;
    }

    public void setEmailTargets(String emailTargets) {
        this.emailTargets = emailTargets;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(LocalDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
