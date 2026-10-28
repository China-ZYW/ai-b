package com.aibi.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 自然人身份实体（映射表 {@code user_identity}，全局表，列入租户拦截器 IGNORE 名单）。
 *
 * <p>方案 A（见《角色权限体系与组织协同设计》2.3）：自然人 = {@code user_identity}，
 * 组织内成员身份 = {@code app_user}（{@code app_user.identity_id} 指向本表）。
 * 口令、邮箱、手机、头像只在<b>本层</b>维护；昵称可在 {@code app_user} 层按组织覆盖。
 * 这样 {@code uid} 仍是"组织内身份 id"，{@code query_history.uid} / {@code metric.uid} 等
 * 既有语义与租户注入逻辑<b>一行不改</b>。
 *
 * <p>{@code password_hash} 仅存 BCrypt，绝不明文；{@code status=0} 表示封禁，跨全部组织生效。
 */
@TableName("user_identity")
public class UserIdentity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String phone;
    private String passwordHash;
    private String nickname;
    private String avatar;
    /** LOCAL/SSO/LDAP/WECOM，本期仅 LOCAL。 */
    private String authSource;
    /** 1=正常 0=封禁（全组织失效）。 */
    private Integer status;
    private Integer mfaEnabled;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getAuthSource() {
        return authSource;
    }

    public void setAuthSource(String authSource) {
        this.authSource = authSource;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(Integer mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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
