package com.aibi.module.mcp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;

import java.time.LocalDateTime;

/**
 * 对外 MCP 的个人访问令牌（PAT，{@code mcp_pat}）。
 *
 * <p>《对外 MCP 能力方案》一期用平台签发的 PAT 做鉴权闭环：外部 AI 客户端（Claude / Cursor / 自建 Agent）
 * 在 {@code Authorization: Bearer} 里带上它即可调用 {@code POST /mcp}，无需先接 OAuth 授权服务器。
 *
 * <p><b>明文只出现一次</b>：库里只存 {@code token_hash}（SHA-256 十六进制）与展示用的 {@code token_prefix}，
 * 创建接口返回一次明文后立即无副本——这是 PAT 的通行做法（GitHub / GitLab 同口径），
 * 也让"库被读走"不等于"令牌被盗用"。
 *
 * <p><b>吊销语义</b>：{@code revoked_at} 非空即失效，且每次调用都会读这一行，故吊销立即生效（无需等 TTL）。
 * 与"改密码/停用账号"的联动靠 {@link com.aibi.module.mcp.service.McpTokenResolver} 每次重新解析用户状态保证，
 * 不做"令牌自证有效"的信任。
 */
@TableName("mcp_pat")
public class McpPat {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属租户（隔离键）；平台域账号签发的令牌为 0。 */
    private Long tenantId;

    /** 令牌归属用户（{@code app_user.id}）：MCP 调用以该用户的<b>真实角色</b>执行，不做"MCP 专用超级权限"。 */
    private Long uid;

    /** 令牌名称（便于用户在后台辨认与撤销某一条）。 */
    private String name;

    /** 明文前缀（如 {@code aibi_pat_9f3c}），仅用于列表展示与定位，不足以还原令牌。 */
    private String tokenPrefix;

    /** 完整明文的 SHA-256（十六进制小写），比对用。 */
    private String tokenHash;

    /** 授权范围（逗号分隔；一期仅 {@code mcp:read}，预留 {@code mcp:write}）。 */
    private String scopes;

    private LocalDateTime expiresAt;

    /** 非空即已吊销。 */
    private LocalDateTime revokedAt;

    private LocalDateTime lastUsedAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    public McpPat() {
    }

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

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** 是否仍处于可用状态（未吊销且未过期）。 */
    public boolean usable(LocalDateTime now) {
        return revokedAt == null && (expiresAt == null || !expiresAt.isBefore(now));
    }
}
