package com.aibi.module.rbac.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.OrgRole;
import com.aibi.common.tenant.DataScope;
import com.aibi.common.tenant.PlanType;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.rbac.dto.CustomRoleEffectiveVO;
import com.aibi.module.rbac.dto.CustomRoleForm;
import com.aibi.module.rbac.dto.CustomRolePermissionForm;
import com.aibi.module.rbac.dto.CustomRoleVO;
import com.aibi.module.rbac.entity.CustomRole;
import com.aibi.module.rbac.entity.RolePermissionOverride;
import com.aibi.module.rbac.entity.UserRole;
import com.aibi.module.rbac.mapper.CustomRoleMapper;
import com.aibi.module.rbac.mapper.RolePermissionOverrideMapper;
import com.aibi.module.rbac.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租户自定义角色服务（《组织治理专项设计》§2）。
 *
 * <p>自定义角色派生自一个<b>可授予的内置组织角色</b>（base），其有效权限集必须<b>窄化不宽于</b> base：
 * 有效 = base 权限 ∪ GRANT(仅 base 内) − DENY（由 {@code role_permission_override} 承载）。本服务负责
 * 角色 CRUD、覆盖整体替换（窄化护栏）、套餐配额（{@code PlanType.customRoleLimit}）、在册护栏
 * （{@code CUSTOM_ROLE_IN_USE}）与权限预览。
 *
 * <p>所有角色定义变更（增/改/覆盖/删）都触发 {@code perm_version++} + {@code evictTenant}，使持有该角色成员的
 * 旧令牌在 {@code pv} 跳变后即时收敛；同时清 {@code PermissionService} 的自定义角色覆盖快照
 * （{@link PermissionService#evictCustomRole}），保证 ≤ 一个 TTL 内生效（见设计文档 §4）。
 *
 * <p>{@code custom_role}/{@code role_permission_override} 均带 {@code tenant_id} 且未列入租户拦截器
 * IGNORE 名单，读写由框架自动注入隔离条件；{@code user_role} 在 IGNORE 名单，故在册计数显式带
 * {@code tenant_id}（同 {@code MemberService} 口径）。
 */
@Service
public class CustomRoleService {

    private final CustomRoleMapper customRoleMapper;
    private final RolePermissionOverrideMapper overrideMapper;
    private final UserRoleMapper userRoleMapper;
    private final TenantMapper tenantMapper;
    private final PermissionService permissionService;
    private final PermVersionService permVersionService;
    private final AuditService auditService;

    public CustomRoleService(CustomRoleMapper customRoleMapper,
                             RolePermissionOverrideMapper overrideMapper,
                             UserRoleMapper userRoleMapper,
                             TenantMapper tenantMapper,
                             PermissionService permissionService,
                             PermVersionService permVersionService,
                             AuditService auditService) {
        this.customRoleMapper = customRoleMapper;
        this.overrideMapper = overrideMapper;
        this.userRoleMapper = userRoleMapper;
        this.tenantMapper = tenantMapper;
        this.permissionService = permissionService;
        this.permVersionService = permVersionService;
        this.auditService = auditService;
    }

    // ==================== 读侧 ====================

    /** 本组织自定义角色列表（含 override 数）。租户范围由拦截器自动注入。 */
    public List<CustomRoleVO> list() {
        AccessContextHolder.require().requireTenantId();
        List<CustomRole> roles = customRoleMapper.selectList(
                Wrappers.<CustomRole>lambdaQuery().orderByDesc(CustomRole::getId));
        List<CustomRoleVO> vos = new ArrayList<>(roles.size());
        for (CustomRole r : roles) {
            vos.add(toVO(r, countOverrides(r.getId())));
        }
        return vos;
    }

    /** 预览某自定义角色的有效权限（base − DENY + GRANT）。 */
    public CustomRoleEffectiveVO effective(Long id) {
        CustomRole role = requireOwned(id);
        Set<String> base = permissionService.baseRolePermissions(role.getBaseRoleCode());
        Set<String> granted = permissionService.customRoleOverrides(id, RolePermissionOverride.EFFECT_GRANT);
        Set<String> denied = permissionService.customRoleOverrides(id, RolePermissionOverride.EFFECT_DENY);
        Set<String> eff = new LinkedHashSet<>(base);
        eff.addAll(granted);
        eff.removeAll(denied);
        return new CustomRoleEffectiveVO(role.getId(), role.getBaseRoleCode(),
                Set.copyOf(base), Set.copyOf(granted), Set.copyOf(denied), Set.copyOf(eff));
    }

    // ==================== 写侧 ====================

    /**
     * 新建自定义角色（{@code org:customrole:write}）。护栏：base 必须可授予（非 OWNER）、actor 不越级、
     * 套餐配额（{@code CUSTOM_ROLE_LIMIT}）、名称租户唯一（uk 兜底）。初建无覆盖 → 有效集 = base 全集。
     */
    @Transactional
    public CustomRoleVO create(CustomRoleForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();

        if (form.name() == null || form.name().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色名称不能为空");
        }
        if (!isAllowedBase(form.baseRoleCode())) {
            throw new BizException(ErrorCode.CUSTOM_ROLE_BASE_INVALID,
                    "自定义角色不能基于该内置角色: " + form.baseRoleCode());
        }
        assertActorCanGrantBase(ctx, form.baseRoleCode());

        Tenant tenant = tenantMapper.selectById(tenantId);
        int limit = tenant == null ? 0 : PlanType.parse(tenant.getPlan()).customRoleLimit();
        Long cur = customRoleMapper.selectCount(Wrappers.<CustomRole>lambdaQuery());
        int current = cur == null ? 0 : cur.intValue();
        if (limit >= 0 && current >= limit) {
            throw new BizException(ErrorCode.CUSTOM_ROLE_LIMIT, "自定义角色数量已达套餐上限");
        }

        CustomRole role = new CustomRole();
        role.setTenantId(tenantId);
        role.setName(form.name().trim());
        role.setBaseRoleCode(form.baseRoleCode());
        role.setDataScope(resolveDataScope(form.dataScope()));
        role.setDescription(form.description());
        role.setEnabled(form.enabled() == null ? CustomRole.STATUS_ENABLED : form.enabled());
        role.setCreatedBy(actor);
        customRoleMapper.insert(role);

        permissionService.evictCustomRole(role.getId());
        permVersionService.evictTenant(tenantId);
        auditService.record(AuditAction.CUSTOM_ROLE_CREATE, AuditAction.RESULT_OK, tenantId, actor,
                "CUSTOM_ROLE", String.valueOf(role.getId()),
                Map.of("name", role.getName(), "base", role.getBaseRoleCode()));
        return toVO(role, 0);
    }

    /**
     * 更新自定义角色（改名/描述/启用停用/data_scope）。护栏：<b>不可改 base 角色</b>（改 base 需重建）；
     * data_scope 写入值须为合法且非跨租户范围（v1 实际收敛仍按 base 角色 data_scope，见设计文档 §2.6）。
     */
    @Transactional
    public CustomRoleVO update(Long id, CustomRoleForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        CustomRole role = requireOwned(id);

        if (form.baseRoleCode() != null && !form.baseRoleCode().equals(role.getBaseRoleCode())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能更改自定义角色的 base 角色，请重建");
        }
        if (form.name() != null && !form.name().isBlank()) {
            role.setName(form.name().trim());
        }
        if (form.description() != null) {
            role.setDescription(form.description());
        }
        if (form.enabled() != null) {
            role.setEnabled(form.enabled());
        }
        if (form.dataScope() != null) {
            role.setDataScope(resolveDataScope(form.dataScope()));
        }
        customRoleMapper.updateById(role);

        permissionService.evictCustomRole(id);
        permVersionService.evictTenant(tenantId);
        auditService.record(AuditAction.CUSTOM_ROLE_UPDATE, AuditAction.RESULT_OK, tenantId, ctx.requireUid(),
                "CUSTOM_ROLE", String.valueOf(id), Map.of("name", role.getName()));
        return toVO(role, countOverrides(id));
    }

    /**
     * 整体替换某自定义角色的权限覆盖（{@code org:customrole:write}）。护栏：每条 GRANT 必须落在 base
     * 权限集内，否则 {@code ROLE_GRANT_ESCALATION}（窄化不变式）。先删后插，保证「整体替换」语义；
     * {@code uk_role_perm} 保证同一权限点不会同时 GRANT 与 DENY。
     */
    @Transactional
    public CustomRoleVO setOverrides(Long id, CustomRolePermissionForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        CustomRole role = requireOwned(id);

        Set<String> basePerms = permissionService.baseRolePermissions(role.getBaseRoleCode());
        if (form.grant() != null) {
            for (String code : form.grant()) {
                if (!basePerms.contains(code)) {
                    throw new BizException(ErrorCode.ROLE_GRANT_ESCALATION,
                            "GRANT 超出 base 权限（违反窄化不变式）: " + code);
                }
            }
        }

        overrideMapper.delete(Wrappers.<RolePermissionOverride>lambdaQuery()
                .eq(RolePermissionOverride::getCustomRoleId, id));

        List<RolePermissionOverride> inserts = new ArrayList<>();
        if (form.grant() != null) {
            for (String code : form.grant()) {
                inserts.add(new RolePermissionOverride(tenantId, id, code, RolePermissionOverride.EFFECT_GRANT));
            }
        }
        if (form.deny() != null) {
            for (String code : form.deny()) {
                inserts.add(new RolePermissionOverride(tenantId, id, code, RolePermissionOverride.EFFECT_DENY));
            }
        }
        for (RolePermissionOverride o : inserts) {
            overrideMapper.insert(o);
        }

        permissionService.evictCustomRole(id);
        permVersionService.evictTenant(tenantId);
        auditService.record(AuditAction.CUSTOM_ROLE_PERM_UPDATE, AuditAction.RESULT_OK, tenantId, ctx.requireUid(),
                "CUSTOM_ROLE", String.valueOf(id),
                Map.of("granted", form.grant() == null ? 0 : form.grant().size(),
                        "denied", form.deny() == null ? 0 : form.deny().size()));
        return toVO(role, inserts.size());
    }

    /**
     * 删除自定义角色（{@code org:customrole:write}）。护栏：仍有成员持有（{@code user_role.status=1}）
     * 则拒 {@code CUSTOM_ROLE_IN_USE}；删除同时清其全部覆盖行。
     */
    @Transactional
    public void delete(Long id) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        CustomRole role = requireOwned(id);

        Long holders = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getCustomRoleId, id)
                .eq(UserRole::getStatus, 1));
        if (holders != null && holders > 0) {
            throw new BizException(ErrorCode.CUSTOM_ROLE_IN_USE, "自定义角色仍有成员持有，不可删除");
        }

        overrideMapper.delete(Wrappers.<RolePermissionOverride>lambdaQuery()
                .eq(RolePermissionOverride::getCustomRoleId, id));
        customRoleMapper.deleteById(id);

        permissionService.evictCustomRole(id);
        permVersionService.evictTenant(tenantId);
        auditService.record(AuditAction.CUSTOM_ROLE_DELETE, AuditAction.RESULT_OK, tenantId, ctx.requireUid(),
                "CUSTOM_ROLE", String.valueOf(id), Map.of("name", role.getName()));
    }

    // ==================== 内部工具 ====================

    /** 取自定义角色并校验租户归属（拦截器已按 tenant 注入，这里再防御性校验，防上下文缺失时被越权）。 */
    private CustomRole requireOwned(Long id) {
        CustomRole role = customRoleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "自定义角色不存在: " + id);
        }
        Long tenantId = AccessContextHolder.require().requireTenantId();
        if (role.getTenantId() == null || !role.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该自定义角色");
        }
        return role;
    }

    private int countOverrides(Long customRoleId) {
        Long c = overrideMapper.selectCount(Wrappers.<RolePermissionOverride>lambdaQuery()
                .eq(RolePermissionOverride::getCustomRoleId, customRoleId));
        return c == null ? 0 : Math.toIntExact(c);
    }

    private CustomRoleVO toVO(CustomRole r, int overrideCount) {
        return new CustomRoleVO(r.getId(), r.getName(), r.getBaseRoleCode(), r.getDataScope(),
                r.getDescription(), r.isEnabled(), overrideCount, r.getCreatedAt(), r.getUpdatedAt());
    }

    /** 解析/校验 data_scope：合法且非跨租户；空值回落 TEAM（与 DDL 默认值一致）。v1 仅登记，收敛见 §2.6。 */
    private String resolveDataScope(String value) {
        if (value == null || value.isBlank()) {
            return "TEAM";
        }
        DataScope ds = DataScope.parse(value);
        if (ds.isCrossTenant()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "自定义角色的数据范围不可为跨租户范围");
        }
        return ds.name();
    }

    /** base 是否可作为自定义角色派生源：必须是内置组织角色且非 OWNER（NON_GRANTABLE）。 */
    private boolean isAllowedBase(String code) {
        if (!OrgRole.isOrg(code)) {
            return false;
        }
        for (String r : OrgRole.NON_GRANTABLE) {
            if (r.equals(code)) {
                return false;
            }
        }
        return true;
    }

    /** 越级护栏：不可基于比自身最高组织角色更宽的 base（复用 MemberService 口径）。 */
    private void assertActorCanGrantBase(AccessContext ctx, String baseRoleCode) {
        int actorIdx = highestOrgRoleIndex(ctx.orgRoles());
        int targetIdx = orgRoleIndex(baseRoleCode);
        if (actorIdx >= 0 && targetIdx >= 0 && targetIdx < actorIdx) {
            throw new BizException(ErrorCode.ROLE_GRANT_ESCALATION, "不可基于超出自身权限的角色");
        }
    }

    private int highestOrgRoleIndex(Set<String> roles) {
        int best = -1;
        if (roles == null) {
            return best;
        }
        for (String r : roles) {
            int idx = orgRoleIndex(r);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private int orgRoleIndex(String roleCode) {
        if (roleCode == null) {
            return -1;
        }
        for (int i = 0; i < OrgRole.ALL.length; i++) {
            if (OrgRole.ALL[i].equals(roleCode)) {
                return i;
            }
        }
        return -1;
    }
}
