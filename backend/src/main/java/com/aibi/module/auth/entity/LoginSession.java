package com.aibi.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 登录会话实体（映射表 {@code login_session}，租户隔离表）。
 *
 * <p>引入本表是为了实现<b>精确吊销</b>：JWT 无状态，签发后在自然过期前（当前配置
 * {@code aibi.jwt.expire-minutes=120}）始终有效，被移除的成员/被停用的账号可继续使用旧令牌。
 * 有了会话行，{@code JwtAuthFilter} 可在解析令牌后按 {@code sid} 查一次 {@code revoked_at}，
 * 命中即返回 40311（SESSION_REVOKED），实现 ≤1s 生效的强制下线。
 *
 * <p><b>硬约束</b>：本表落地后 {@code AuthController.logout()} 必须真吊销（置 {@code revoked_at}），
 * 不能再是 {@code return R.ok()} 的空实现，否则"退出登录"只是前端清了本地存储。
 *
 * <p>{@code token_hash} 存 SHA-256(access_token)，用于"按令牌定位会话"（旧令牌无 {@code sid} claim 时的兜底）；
 * 绝不存明文令牌。{@code scope=ASSIST} 表示平台代客协助签发的影子令牌。
 */
@TableName("login_session")
public class LoginSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属组织；平台身份为 0。 */
    private Long tenantId;
    /** {@code app_user.id}。 */
    private Long uid;
    /** 会话 id，写入 JWT claim {@code sid}，全局唯一。 */
    private String sid;
    /** SHA-256(access_token) 十六进制，绝不存明文。 */
    private String tokenHash;
    /** NORMAL/ASSIST，见 {@code TokenScope}。 */
    private String scope;
    private String ip;
    private String userAgent;
    /** 与 JWT {@code exp} 一致，供过期清理任务使用。 */
    private LocalDateTime expireAt;
    /** 非空=已吊销（登出、强制下线、移除成员、停用账号、重置口令）。 */
    private LocalDateTime revokedAt;
    /** 吊销操作人：自助登出为本人，管理员强制下线为管理员 uid。 */
    private Long revokedBy;
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

    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Long getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(Long revokedBy) {
        this.revokedBy = revokedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
