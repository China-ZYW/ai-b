package com.aibi.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 权限与登录审计实体（映射表 {@code auth_audit_log}，租户隔离表）。
 *
 * <p>{@code tenant_id=0} 表示平台域事件（平台角色授予、租户停用等由运营发起、作用于平台/某租户的动作）。
 * 本表带 {@code tenant_id} 但<b>不</b>在租户拦截器 IGNORE 名单：组织内审计（{@code audit:log:read}）
 * 依赖自动注入按租户收敛；而平台后台的<b>跨租户</b>检索（{@code admin:audit:read}）走
 * {@code @InterceptorIgnore(tenantLine="true")} + 显式条件（见 {@code AuthAuditLogMapper}），
 * 与设计文档 DDL 注释「平台域查询走 @InterceptorIgnore + 显式条件」一致。
 *
 * <p>{@code detail} 为脱敏 JSON：禁存口令、{@code password_hash}、{@code api_key_cipher}、
 * {@code password_cipher} 与完整邀请 token（见《角色权限体系与组织协同设计》10.2）。
 */
@TableName("auth_audit_log")
public class AuthAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 事件所属组织；{@code 0}=平台域事件。 */
    private Long tenantId;
    /** 操作人 {@code app_user.id}；匿名事件为空。 */
    private Long uid;
    /** 操作人自然人身份 {@code user_identity.id}。 */
    private Long identityId;
    /** USER/OPERATOR/SUPPORT/SYSTEM。 */
    private String actorType;
    /** 事件动作码，见 {@code AuditAction}（VARCHAR(48)）。 */
    private String action;
    /** 资源类型（如 USER_ROLE/TENANT）。 */
    private String resourceType;
    /** 资源标识（如角色码、租户 id 字符串）。 */
    private String resourceId;
    /** 被操作人 {@code app_user.id}。 */
    private Long targetUid;
    /** OK/DENIED/FAILED。 */
    private String result;
    /** 脱敏 JSON：变更前后值、权限点、角色码等。 */
    private String detail;
    private String ip;
    private String userAgent;
    private String traceId;
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

    public Long getIdentityId() {
        return identityId;
    }

    public void setIdentityId(Long identityId) {
        this.identityId = identityId;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Long getTargetUid() {
        return targetUid;
    }

    public void setTargetUid(Long targetUid) {
        this.targetUid = targetUid;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
