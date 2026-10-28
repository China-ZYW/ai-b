package com.aibi.module.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 问数历史实体（MyBatis-Plus 映射表 {@code query_history}）。
 *
 * <p>落库有双重价值：① 审计与"我的历史"回看；② 为少样本（{@code FewShotStore}）与多轮上下文
 * （{@code recentTurns}）提供数据源，形成自我增强闭环。
 *
 * <p>{@code tenant_id} 由租户拦截器自动注入；{@code uid} 为用户级过滤键，由 Service 显式携带。
 * 字段名按 MP 默认下划线↔驼峰规则映射（{@code sqlText}↔{@code sql_text}、{@code rowCount}↔{@code row_count} 等）。
 */
@TableName("query_history")
public class QueryHistory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long uid;
    private Long datasourceId;
    private String conversationId;
    private String question;
    private String sqlText;
    private Integer rowCount;
    private Long elapsedMs;
    private String status;
    private LocalDateTime createdAt;
    /** 用户反馈：1=赞 -1=踩（空=未反馈）；赞且 OK 的历史可晋升少样本（反馈闭环）。 */
    private Integer feedback;
    private LocalDateTime feedbackAt;
    /** 问题中显式指定的图表类型（如 LINE），供图表准确率统计。 */
    private String chartRequested;
    /** 最终推荐/使用的图表类型。 */
    private String chartUsed;
    /** 失败原因（status=FAILED 时）。 */
    private String errorMsg;
    /** 结果快照 JSON（{columns, rows 前 1000 行}）；老记录为空，回放时重跑 SQL 补齐。 */
    private String resultJson;
    /** 图表规格 JSON（问数当时的推荐）；老记录为空，回放时由规则引擎重建。 */
    private String chartJson;
    /**
     * AI 数据解读 JSON（{@code Insight}：summary/keyPoints/anomalies）；问数当时落库，历史回放据此还原解读。
     * 老记录或关闭 AI 解读时为空，回放时不重算（解读是"问数当时"的结论，重跑 LLM 既耗配额又可能与当时不一致）。
     */
    private String insightJson;
    /** AI 质量校验 JSON（{@code Verify}：answered/score/issues/suggestion）；同上，供回放还原校验结论。 */
    private String verifyJson;
    /**
     * 维度波动归因结果 JSON（{@code AttributionVO}）；归因跑完时按请求携带的 {@code historyId} 回写本行，
     * 历史回放据此还原归因面板——与 insightJson / verifyJson 同一套"问数当次产物"口径
     * （归因依赖当时的口径与维度选择，重跑既耗资源也可能与当时不一致，故只存不重算）。
     */
    private String attributionJson;
    /**
     * 本次答问所用技能编码（V4 Skill 模式）：{@code direct_sql} / {@code metric} / {@code fewshot} / {@code nl2sql}。
     * 用于统计各技能命中率与"是否走了大模型"，老记录为空。
     */
    private String skill;

    /**
     * 点踩时用户填的期望正确 SQL（T11-2，可空）：仅 {@code feedback=-1} 且用户主动提交建议时有值。
     * 绝不自动进少样本/指标，只供治理转写抽屉作预填草稿（必经人工复核）。
     */
    private String expectedSql;
    /** 点踩时用户的口径说明/错因描述（T11-2，可空）。 */
    private String feedbackNote;
    /** 点踩错因分类（T11-2，可空）：WRONG_NUMBER/WRONG_METRIC/WRONG_FILTER/OTHER。 */
    private String feedbackReason;

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

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getFeedback() {
        return feedback;
    }

    public void setFeedback(Integer feedback) {
        this.feedback = feedback;
    }

    public LocalDateTime getFeedbackAt() {
        return feedbackAt;
    }

    public void setFeedbackAt(LocalDateTime feedbackAt) {
        this.feedbackAt = feedbackAt;
    }

    public String getChartRequested() {
        return chartRequested;
    }

    public void setChartRequested(String chartRequested) {
        this.chartRequested = chartRequested;
    }

    public String getChartUsed() {
        return chartUsed;
    }

    public void setChartUsed(String chartUsed) {
        this.chartUsed = chartUsed;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getChartJson() {
        return chartJson;
    }

    public void setChartJson(String chartJson) {
        this.chartJson = chartJson;
    }

    public String getInsightJson() {
        return insightJson;
    }

    public void setInsightJson(String insightJson) {
        this.insightJson = insightJson;
    }

    public String getVerifyJson() {
        return verifyJson;
    }

    public void setVerifyJson(String verifyJson) {
        this.verifyJson = verifyJson;
    }

    public String getAttributionJson() {
        return attributionJson;
    }

    public void setAttributionJson(String attributionJson) {
        this.attributionJson = attributionJson;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public String getExpectedSql() {
        return expectedSql;
    }

    public void setExpectedSql(String expectedSql) {
        this.expectedSql = expectedSql;
    }

    public String getFeedbackNote() {
        return feedbackNote;
    }

    public void setFeedbackNote(String feedbackNote) {
        this.feedbackNote = feedbackNote;
    }

    public String getFeedbackReason() {
        return feedbackReason;
    }

    public void setFeedbackReason(String feedbackReason) {
        this.feedbackReason = feedbackReason;
    }
}
