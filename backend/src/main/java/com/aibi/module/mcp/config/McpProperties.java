package com.aibi.module.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 对外服务配置（{@code aibi.mcp.*}）。
 *
 * <p>一期用 PAT 做鉴权闭环，OAuth 2.1 相关项（{@code authorization-servers} / {@code jwks-url} /
 * {@code issuer} / {@code require-audience}）为二期预留：留空即不启用对应校验，不会误拒 PAT 流量。
 */
@Component
public class McpProperties {

    @Value("${aibi.mcp.enabled:true}")
    private boolean enabled;

    /** 本服务规范 URI（RFC 8707 resource）：令牌 {@code aud} 需与之匹配。 */
    @Value("${aibi.mcp.resource-url:}")
    private String resourceUrl;

    @Value("${aibi.mcp.authorization-servers:}")
    private String authorizationServers;

    @Value("${aibi.mcp.jwks-url:}")
    private String jwksUrl;

    @Value("${aibi.mcp.issuer:}")
    private String issuer;

    @Value("${aibi.mcp.require-audience:true}")
    private boolean requireAudience;

    @Value("${aibi.mcp.allow-self-jwt:true}")
    private boolean allowSelfJwt;

    @Value("${aibi.mcp.rate-limit-per-user:30}")
    private int rateLimitPerUser;

    @Value("${aibi.mcp.rate-limit-per-tenant:120}")
    private int rateLimitPerTenant;

    public boolean isEnabled() {
        return enabled;
    }

    public String getResourceUrl() {
        return resourceUrl;
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public String getIssuer() {
        return issuer;
    }

    public boolean isRequireAudience() {
        return requireAudience;
    }

    public boolean isAllowSelfJwt() {
        return allowSelfJwt;
    }

    public int getRateLimitPerUser() {
        return rateLimitPerUser;
    }

    public int getRateLimitPerTenant() {
        return rateLimitPerTenant;
    }

    /** 授权服务器地址列表（逗号分隔，去空白；未配置返回空列表）。 */
    public List<String> authorizationServers() {
        if (authorizationServers == null || authorizationServers.isBlank()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String part : authorizationServers.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** 是否配置了外部授权服务器（决定 well-known 元数据里是否给出 authorization_servers）。 */
    public boolean hasAuthorizationServers() {
        return !authorizationServers().isEmpty();
    }
}
