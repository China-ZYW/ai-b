package com.aibi.module.acl.service;

import com.aibi.common.security.AccessContext;
import com.aibi.module.acl.entity.ResourceAcl;
import com.aibi.module.acl.mapper.ResourceAclMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源级 ACL（行级归属）服务（设计文档 6.2 / 4.3 数据范围，已实现并接入 {@code PermissionAspect}）。
 *
 * <p>{@code resource_acl} 采用 principal 模型：每个受管资源可有多行授权记录，每行描述
 * "某主体（USER/TEAM/ROLE）对资源拥有 READ/WRITE/MANAGE 级权限"。资源创建时由各自 Service 调
 * {@link #grant} 写行（创建者恒持 {@code MANAGE}，可见性翻译为对应 principal 行），{@code PermissionAspect}
 * 的 {@code @CheckResourceAccess} 在能力点判定（L2）之后调 {@link #canAccess} 做逐资源行级比对。
 *
 * <p><b>fail-open 兼容</b>：历史资源若无 {@code resource_acl} 行（迁移前创建），{@link #canAccess}
 * 直接放行——租户隔离仍由 {@code TenantLineInnerInterceptor} 保障，不会因本服务未上线把老资源锁死。
 * 新建资源一律带行，行级 ACL 即对它们生效（默认 ORG 可见，仅创建者可改删）。
 *
 * <p>判定优先级（租户不匹配直接拒绝，确保跨组织不可越权）：
 * <ol>
 *   <li>无授权行 → 放行（fail-open，仅依赖租户隔离）</li>
 *   <li>租户不匹配 → 拒绝</li>
 *   <li>组织所有者（OWNER）→ 放行</li>
 *   <li>授权行中权限级别 ≥ 请求级别 且主体命中当前用户/团队/角色 → 放行</li>
 *   <li>其余 → 拒绝</li>
 * </ol>
 *
 * <p>权限级别权重：MANAGE(3) &gt; WRITE(2) &gt; READ(1)。高权限授权行可满足低级别请求（如 owner 的
 * {@code MANAGE} 行允许其 READ/WRITE/MANAGE；{@code ROLE/ORG} 行仅 READ，故组织全员可读但不可改删）。
 */
@Service
public class ResourceAclService {

    private static final Logger log = LoggerFactory.getLogger(ResourceAclService.class);

    private final ResourceAclMapper resourceAclMapper;

    public ResourceAclService(ResourceAclMapper resourceAclMapper) {
        this.resourceAclMapper = resourceAclMapper;
    }

    /** 权限级别权重，用于"高权限覆盖低权限"比较。 */
    private static int level(String perm) {
        if (ResourceAcl.PERMISSION_MANAGE.equals(perm)) {
            return 3;
        }
        if (ResourceAcl.PERMISSION_WRITE.equals(perm)) {
            return 2;
        }
        if (ResourceAcl.PERMISSION_READ.equals(perm)) {
            return 1;
        }
        return 0;
    }

    /**
     * 判定 ctx 对 (type, id) 是否具备 permission（READ/WRITE/MANAGE）级访问权。
     *
     * @return true=放行；false=拒绝（由 {@code PermissionAspect} 转 {@code ACL_DENIED}）
     */
    public boolean canAccess(AccessContext ctx, String type, Object resourceId, String permission) {
        if (resourceId == null) {
            return false;
        }
        Long id;
        try {
            id = Long.valueOf(resourceId.toString());
        } catch (NumberFormatException e) {
            return false;
        }
        List<ResourceAcl> rows;
        try {
            rows = resourceAclMapper.listByResource(type, id);
        } catch (Exception e) {
            // 表缺失/查询异常：fail-open，仅依赖租户隔离，避免阻断主流程（与 grant 的 best-effort 对称）
            log.warn("资源归属查询失败（fail-open，仅依赖租户隔离）: type={}, id={}, err={}",
                    type, id, e.getMessage());
            return true;
        }
        if (rows == null || rows.isEmpty()) {
            // 历史资源未登记归属：fail-open，仅依赖租户隔离
            return true;
        }
        // 所有授权行同租户，取首行 tenant 做边界校验（不一致直接拒绝，防跨组织越权）
        Long rowTenant = rows.get(0).getTenantId();
        if (rowTenant == null || ctx.tenantId() == null || !rowTenant.equals(ctx.tenantId())) {
            return false;
        }
        // 组织所有者可管理一切
        if (ctx.isOwner()) {
            return true;
        }
        int req = level(permission);
        for (ResourceAcl row : rows) {
            if (level(row.getPermission()) < req) {
                continue; // 该授权行权限不足，跳过
            }
            if (ResourceAcl.PRINCIPAL_USER.equals(row.getPrincipalType())
                    && row.getPrincipalId() != null && row.getPrincipalId().equals(ctx.uid())) {
                return true; // 创建者/被显式授权的用户
            }
            if (ResourceAcl.PRINCIPAL_TEAM.equals(row.getPrincipalType())
                    && row.getPrincipalId() != null && ctx.scopeTeamIds().contains(row.getPrincipalId())) {
                return true; // 当前用户所属团队（含子团队）被授权
            }
            if (ResourceAcl.PRINCIPAL_ROLE.equals(row.getPrincipalType())
                    && ResourceAcl.CODE_ORG.equals(row.getPrincipalCode())) {
                return true; // 组织全员可见（仅 READ 行生效，改删不开放给全员）
            }
            if ((ResourceAcl.PRINCIPAL_ROLE.equals(row.getPrincipalType())
                    || ResourceAcl.PRINCIPAL_CUSTOM_ROLE.equals(row.getPrincipalType()))
                    && row.getPrincipalCode() != null
                    && !ResourceAcl.CODE_ORG.equals(row.getPrincipalCode())
                    && ctx.orgRoles().contains(row.getPrincipalCode())) {
                return true; // 持有该角色码
            }
        }
        return false;
    }

    /**
     * 登记资源归属（创建资源时调用）。把可见性语义翻译为 principal 行：
     * <ul>
     *   <li>创建者恒持 {@code (USER, ownerUid, MANAGE)}</li>
     *   <li>TEAM → 追加 {@code (TEAM, teamId, READ)}</li>
     *   <li>ORG / 未指定 → 追加 {@code (ROLE, ORG, READ)}（组织全员可读）</li>
     *   <li>PRIVATE → 仅创建者行</li>
     * </ul>
     * best-effort：写入异常仅告警不抛出，避免影响主流程（缺行时 {@link #canAccess} 会 fail-open）。
     */
    public void grant(Long tenantId, String type, Long resourceId, Long ownerUid, Long teamId, String visibility) {
        try {
            if (ownerUid != null) {
                insertRow(tenantId, type, resourceId, ResourceAcl.PRINCIPAL_USER, ownerUid, null,
                        ResourceAcl.PERMISSION_MANAGE, ownerUid);
            }
            if (ResourceAcl.VISIBILITY_TEAM.equals(visibility) && teamId != null) {
                insertRow(tenantId, type, resourceId, ResourceAcl.PRINCIPAL_TEAM, teamId, null,
                        ResourceAcl.PERMISSION_READ, ownerUid);
            } else if (!ResourceAcl.VISIBILITY_PRIVATE.equals(visibility)) {
                // ORG / 未指定：组织全员可见（READ）
                insertRow(tenantId, type, resourceId, ResourceAcl.PRINCIPAL_ROLE, null,
                        ResourceAcl.CODE_ORG, ResourceAcl.PERMISSION_READ, ownerUid);
            }
            // PRIVATE：仅创建者行（见上）
        } catch (Exception e) {
            log.warn("资源归属登记失败（不影响主流程，该资源将按租户隔离 fail-open）: type={}, id={}, err={}",
                    type, resourceId, e.getMessage());
        }
    }

    private void insertRow(Long tenantId, String type, Long resourceId, String principalType,
                           Long principalId, String principalCode, String permission, Long grantedBy) {
        ResourceAcl row = new ResourceAcl();
        row.setTenantId(tenantId);
        row.setResourceType(type);
        row.setResourceId(resourceId);
        row.setPrincipalType(principalType);
        row.setPrincipalId(principalId);
        row.setPrincipalCode(principalCode);
        row.setPermission(permission);
        row.setGrantedBy(grantedBy);
        row.setStatus(ResourceAcl.STATUS_ACTIVE);
        row.setCreatedAt(LocalDateTime.now());
        resourceAclMapper.insert(row);
    }

    /** 删除资源时清理其全部授权行（best-effort）。 */
    public void deleteByResource(String type, Long resourceId) {
        try {
            resourceAclMapper.deleteByResource(type, resourceId);
        } catch (Exception e) {
            log.warn("清理资源归属行失败: type={}, id={}, err={}", type, resourceId, e.getMessage());
        }
    }
}
