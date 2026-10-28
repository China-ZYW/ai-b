package com.aibi.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户实体（MyBatis-Plus 映射表 {@code app_user}）。
 *
 * <p>表名用 {@code app_user} 规避 MySQL 保留字 {@code user}。密码仅存 BCrypt 哈希，绝不明文。
 * 该表在租户拦截器 IGNORE 名单中：登录发生在租户上下文建立之前，按全局唯一 {@code username} 寻址。
 *
 * <p><b>两层身份</b>（见《角色权限体系与组织协同设计》2.3 方案 A）：本表一行 = 自然人在<b>某个组织内</b>
 * 的成员身份，{@code id}（即全项目的 {@code uid}）语义与升级前完全一致；自然人本身（口令、邮箱、
 * 手机、头像）由 {@code identity_id} 指向的 {@code user_identity} 维护。本表保留的
 * {@code passwordHash}/{@code email} 是<b>兼容读取源</b>：存量行尚未回填 identity 时登录仍靠它们，
 * 新写入一律先写 identity 再回填本表，至少两个版本后才考虑下线。
 */
@TableName("app_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    /** 兼容字段：{@code SUPER_ADMIN}/{@code USER} 二元值，作为平台角色回退与存量读取源。 */
    private String role;
    private Integer status;
    /** 自然人身份 {@code user_identity.id}；回填前为 null。 */
    private Long identityId;
    /** 令牌版本：停用/降级/重置口令时 +1，与 JWT claim {@code pv} 不一致即强制重登。 */
    private Long tokenVersion;
    /** 上次选定的团队，作为下次登录的默认团队。 */
    private Long currentTeamId;
    /** 组织内职位（展示用，不参与权限判定）。 */
    private String position;
    /**
     * 账号级界面主题偏好：{@code light} / {@code dark} / {@code system}（默认，跟随操作系统）。
     *
     * <p>落在 {@code app_user} 而非 {@code sys_config}：配置中心只有 GLOBAL/TENANT 两级 scope，
     * 没有 USER 级；而主题是"人"的属性——换组织不该丢主题，同一自然人在不同组织的两个
     * {@code app_user} 行也各自独立，符合直觉。
     */
    private String uiTheme;
    private LocalDateTime lastLoginAt;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getIdentityId() {
        return identityId;
    }

    public void setIdentityId(Long identityId) {
        this.identityId = identityId;
    }

    public Long getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(Long tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    /** 令牌版本容错取值：存量行该列为 {@code NOT NULL DEFAULT 0}，但回填前的实体可能为 null。 */
    public long tokenVersionOrZero() {
        return tokenVersion == null ? 0L : tokenVersion;
    }

    public Long getCurrentTeamId() {
        return currentTeamId;
    }

    public void setCurrentTeamId(Long currentTeamId) {
        this.currentTeamId = currentTeamId;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getUiTheme() {
        return uiTheme;
    }

    public void setUiTheme(String uiTheme) {
        this.uiTheme = uiTheme;
    }

    /** 主题偏好容错取值：列缺失/回填前的实体可能为 null，按 'system'（跟随系统）处理。 */
    public String uiThemeOrDefault() {
        return (uiTheme == null || uiTheme.isBlank()) ? "system" : uiTheme;
    }

    /** 是否已归并到自然人身份（未回填时登录需走 {@code passwordHash} 老路径）。 */
    public boolean hasIdentity() {
        return identityId != null;
    }
}
