package com.aibi.module.share.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分享链接实体（MyBatis-Plus 映射表 {@code share_link}）。
 *
 * <p>分享的是<b>结果快照</b>（{@code resultSnapshot} / {@code chartSpec} 为 JSON 文本），公开访问只读快照、不回查业务库。
 * {@code password} 存 BCrypt 哈希；{@code watermark} 用 {@code Integer}（TINYINT，1=加水印）。
 *
 * <p>创建/列出/删除走租户拦截器自动注入 {@code tenant_id}；公开访问按 {@code token} 直查（能力令牌），
 * 该路径在 {@code ShareLinkMapper} 上用 {@code @InterceptorIgnore} 关闭注入。
 */
@TableName("share_link")
public class ShareLink {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long uid;
    private String token;
    private Long datasourceId;
    private String question;
    private String sqlText;
    private String resultSnapshot;
    private String chartSpec;
    private String password;
    private LocalDateTime expireAt;
    private Integer watermark;
    private Integer viewCount;
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
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

    public String getResultSnapshot() {
        return resultSnapshot;
    }

    public void setResultSnapshot(String resultSnapshot) {
        this.resultSnapshot = resultSnapshot;
    }

    public String getChartSpec() {
        return chartSpec;
    }

    public void setChartSpec(String chartSpec) {
        this.chartSpec = chartSpec;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public Integer getWatermark() {
        return watermark;
    }

    public void setWatermark(Integer watermark) {
        this.watermark = watermark;
    }

    public Integer getViewCount() {
        return viewCount;
    }

    public void setViewCount(Integer viewCount) {
        this.viewCount = viewCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
