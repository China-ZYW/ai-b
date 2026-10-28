package com.aibi.module.embed.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对话问数嵌入实体（表 {@code chat_embed}）。
 *
 * <p>一条「嵌入」把一个数据源以<b>匿名令牌</b>的方式开放给第三方页面：第三方用 iframe 嵌入
 * {@code /embed/chat/{token}}，访客无需登录即可对该数据源提问，后端按 {@code token} 解析出租户与
 * 数据源并合成匿名访问上下文后复用问数主链路。
 *
 * <p>{@code allowedOrigins} 用于限制可被嵌入的站点来源（逗号分隔，留空表示不限制）；
 * {@code enabled} 为软开关（0=停用），{@code expireAt} 为空表示长期有效。
 */
@TableName("chat_embed")
public class ChatEmbed {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long uid;
    private String name;
    private String token;
    private Long datasourceId;
    private String datasourceIds;
    private Integer enabled;
    private LocalDateTime expireAt;
    private String allowedOrigins;
    private Integer viewCount;
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

    public String getDatasourceIds() {
        return datasourceIds;
    }

    public void setDatasourceIds(String datasourceIds) {
        this.datasourceIds = datasourceIds;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
