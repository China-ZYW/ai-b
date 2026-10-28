package com.aibi.module.chat.llm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 大模型配置实体（映射表 {@code llm_config}）：支持“全局默认（tenant_id=NULL）+ 租户覆盖”。
 *
 * <p>{@code apiKey} 以 {@link com.aibi.module.datasource.security.CredentialCipher} AES-GCM 密文存于
 * {@code api_key_cipher}，绝不明文。{@code priority} 越小越优先（主力→备选），{@code enabled=0} 停用。
 * 该表在租户拦截器 IGNORE 名单，由 {@code LlmConfigService} 按 (租户 OR 全局) 显式解析。
 */
@TableName("llm_config")
public class LlmConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** NULL=全局默认；非空=租户专属覆盖。 */
    private Long tenantId;
    private String provider;
    private String name;
    private String baseUrl;
    private String apiKeyCipher;
    private String model;
    private Integer timeoutSeconds;
    private Integer priority;
    private Integer enabled;
    /** 额外请求体 JSON（逐字段并入请求体顶层），如 {"thinking":{"type":"disabled"}}；空=不加任何额外字段。 */
    private String extraBody;
    /** 输出 token 上限；空=用供应商默认。 */
    private Integer maxTokens;
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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(String apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
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

    public String getExtraBody() {
        return extraBody;
    }

    public void setExtraBody(String extraBody) {
        this.extraBody = extraBody;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }
}
