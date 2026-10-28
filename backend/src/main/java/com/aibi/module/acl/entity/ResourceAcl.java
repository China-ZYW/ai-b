package com.aibi.module.acl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 资源级 ACL 授权实体（映射表 {@code resource_acl}，设计文档 6.2 / 4.3 数据范围）。
 *
 * <p>本表采用 <b>principal（被授权主体）模型</b>：每个受管资源可有多行授权记录，每行描述
 * "某主体（用户/团队/角色）对资源拥有何种级别的权限（READ/WRITE/MANAGE）"。{@code PermissionAspect}
 * 的 {@code @CheckResourceAccess} 在能力点判定之后，调 {@code ResourceAclService.canAccess} 按这些行做
 * "对这一个具体资源有没有权限" 的逐资源行级校验（如 {@code DataSourceController.delete}）。
 *
 * <p><b>与早期 owner/visibility 模型的差异</b>：早期实体误写成 {@code owner_uid/team_id/visibility}，
 * 与表结构（principal_* 列）完全不匹配，导致 {@code grant} 写入因 Unknown column 被静默吞掉、
 * {@code canAccess} 恒 fail-open；本次对齐到真实 principal 模型。{@link #VISIBILITY_PRIVATE} 等常量
 * 仅作为 {@code grant} 入参的语义助记，由 {@code ResourceAclService} 翻译为对应的 principal 行，不再映射到列。
 *
 * <p><b>默认可见性</b>：{@code grant} 对 {@code ORG}/{@code null} 写一行 {@code (ROLE, ORG, READ)}，
 * 表示组织内全员可读；{@code TEAM} 写一行 {@code (TEAM, teamId, READ)}；{@code PRIVATE} 仅写创建者
 * {@code (USER, ownerUid, MANAGE)} 行。历史资源若未登记任何行，{@code canAccess} 按租户隔离 fail-open 放行。
 */
@TableName("resource_acl")
public class ResourceAcl {

    /** 主体类型：具体用户（{@code principal_id}=app_user.id）。 */
    public static final String PRINCIPAL_USER = "USER";
    /** 主体类型：团队（{@code principal_id}=team.id，含子团队由 {@code AccessContext.scopeTeamIds()} 展开）。 */
    public static final String PRINCIPAL_TEAM = "TEAM";
    /** 主体类型：组织角色（{@code principal_code}=角色码）。 */
    public static final String PRINCIPAL_ROLE = "ROLE";
    /** 主体类型：自定义角色（{@code principal_code}=自定义角色码）。 */
    public static final String PRINCIPAL_CUSTOM_ROLE = "CUSTOM_ROLE";

    /** 组织全员可见的角色码（{@code PRINCIPAL_ROLE} 专用）：对应可见性 ORG。 */
    public static final String CODE_ORG = "ORG";

    /** 权限级别：只读。 */
    public static final String PERMISSION_READ = "READ";
    /** 权限级别：可读可改。 */
    public static final String PERMISSION_WRITE = "WRITE";
    /** 权限级别：可读改删，最高。 */
    public static final String PERMISSION_MANAGE = "MANAGE";

    /** grant 入参助记：仅创建者可见（翻译为仅 owner {@code MANAGE} 行）。 */
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    /** grant 入参助记：创建者 + 所属团队（翻译为 owner {@code MANAGE} + {@code TEAM} READ 行）。 */
    public static final String VISIBILITY_TEAM = "TEAM";
    /** grant 入参助记：组织内全员可见（默认，翻译为 owner {@code MANAGE} + {@code ROLE/ORG} READ 行）。 */
    public static final String VISIBILITY_ORG = "ORG";

    /** 授权行状态：生效中。 */
    public static final int STATUS_ACTIVE = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** 资源类型：DATASOURCE / METRIC / DASHBOARD / SHARE 等，与 {@code @CheckResourceAccess.type()} 对齐。 */
    private String resourceType;
    /** 资源主键。 */
    private Long resourceId;
    /** 主体类型：USER / TEAM / ROLE / CUSTOM_ROLE。 */
    private String principalType;
    /** 主体 id：USER=uid；TEAM=team_id；ROLE/CUSTOM_ROLE 时可为 null（角色码在 {@link #principalCode}）。 */
    private Long principalId;
    /** 角色码：{@code principal_type=ROLE} 时存 {@link #CODE_ORG} 或具体角色码。 */
    private String principalCode;
    /** 授权级别：READ / WRITE / MANAGE。 */
    private String permission;
    /** 授权人（{@code app_user.id}），便于审计。 */
    private Long grantedBy;
    /** 过期时间（可空=不过期）。 */
    private LocalDateTime expireAt;
    /** 状态：1=生效。 */
    private Integer status;
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

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(String principalType) {
        this.principalType = principalType;
    }

    public Long getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(Long principalId) {
        this.principalId = principalId;
    }

    public String getPrincipalCode() {
        return principalCode;
    }

    public void setPrincipalCode(String principalCode) {
        this.principalCode = principalCode;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(Long grantedBy) {
        this.grantedBy = grantedBy;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
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
}
