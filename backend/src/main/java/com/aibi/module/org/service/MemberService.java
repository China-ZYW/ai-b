package com.aibi.module.org.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.OrgRole;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.entity.UserIdentity;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserIdentityMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.auth.service.LoginSessionService;
import com.aibi.module.org.dto.MemberCustomRoleVO;
import com.aibi.module.org.dto.MemberPasswordForm;
import com.aibi.module.org.dto.MemberRoleForm;
import com.aibi.module.org.dto.MemberUpdateForm;
import com.aibi.module.org.dto.MemberVO;
import com.aibi.module.org.dto.OwnerTransferForm;
import com.aibi.module.rbac.entity.CustomRole;
import com.aibi.module.rbac.entity.UserRole;
import com.aibi.module.rbac.mapper.CustomRoleMapper;
import com.aibi.module.rbac.mapper.UserRoleMapper;
import com.aibi.module.rbac.service.PermVersionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织成员管理服务（§9.2 组织设置 · 成员，写侧治理）。
 *
 * <p>方案 A 下「成员」= 本组织内 {@code status=1} 的 {@code app_user} 行 + 其 {@code user_role}
 * （{@code scope_type=TENANT}）组织角色集合。租户范围一律由 {@link AccessContext#requireTenantId()}
 * 从当前登录上下文强制推导，<b>不接受任何 {@code tenantId} 入参</b>，杜绝越权操作他组织成员。
 *
 * <p>{@code app_user}/{@code user_role}/{@code user_identity} 均在租户拦截器 IGNORE 名单，故本服务
 * 每条读写都<b>显式携带 {@code tenant_id}</b>（{@code user_identity} 为全局表，按 {@code identity_id} 寻址），
 * 读出口以「先按主键取行、再校验 {@code tenantId} 归属」兜底，漏校验即横向越权。
 *
 * <p><b>分级吊销</b>（设计文档 6.3）：单成员敏感变更（降级/移除/重置口令）走
 * {@code token_version++} + {@code evict(tenantId,uid)}（只影响本人重登）；组织级变更（转让所有权）走
 * {@code perm_version++} + {@code evictTenant}（全组织旧令牌失效）。移除/重置口令叠加
 * {@link LoginSessionService#revokeAllForUser} 实现 ≤1s 精确下线。
 *
 * <p><b>护栏</b>：OWNER 属 {@link OrgRole#NON_GRANTABLE}，只能转让不可授予/回收；不可授予超出自身权限的
 * 角色（{@code ROLE_GRANT_ESCALATION}）；不可移除/回收自己；不可移除最后一个 OWNER；OWNER 退出前须先转让
 * （{@code OWNER_TRANSFER_REQUIRED}）；只可操作权限严格低于自己的成员。ASSIST 令牌碰 {@code member:*}
 * 已由 {@code PermissionService.assistDenied} 硬拒，不在此重复。
 */
@Service
public class MemberService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final CustomRoleMapper customRoleMapper;
    private final TenantMapper tenantMapper;
    private final UserIdentityMapper userIdentityMapper;
    private final PermVersionService permVersionService;
    private final LoginSessionService loginSessionService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public MemberService(UserMapper userMapper,
                         UserRoleMapper userRoleMapper,
                         CustomRoleMapper customRoleMapper,
                         TenantMapper tenantMapper,
                         UserIdentityMapper userIdentityMapper,
                         PermVersionService permVersionService,
                         LoginSessionService loginSessionService,
                         AuditService auditService,
                         PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.customRoleMapper = customRoleMapper;
        this.tenantMapper = tenantMapper;
        this.userIdentityMapper = userIdentityMapper;
        this.permVersionService = permVersionService;
        this.loginSessionService = loginSessionService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    // ==================== 读侧 ====================

    /**
     * 本组织在册成员列表（{@code app_user.status=1}），联其组织角色（{@code user_role} scope=TENANT）。
     *
     * <p>一次性取回本组织全部有效授予后在内存按 {@code uid} 分组（{@code selectEffectiveByTenant}），
     * 避免逐成员 N+1。角色按 {@link OrgRole#ALL} 权限宽窄降序排列，便于前端展示主角色。
     */
    public List<MemberVO> list() {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();

        List<User> members = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getTenantId, tenantId)
                .eq(User::getStatus, 1)
                .orderByDesc(User::getId));

        // 内置角色（custom_role_id 为空）驱动 orgRoles 展示与 OWNER 判定；自定义角色授予（custom_role_id 非空）
        // 单独收敛到 customRoles，供前端展示「自定义角色」标签并按 id 精确回收（否则二者 base 码相同无法区分）。
        Map<Long, List<String>> rolesByUid = new HashMap<>();
        Map<Long, List<Long>> customIdsByUid = new HashMap<>();
        Set<Long> allCustomIds = new LinkedHashSet<>();
        for (UserRole ur : userRoleMapper.selectEffectiveByTenant(tenantId)) {
            if (!UserRole.SCOPE_TENANT.equals(ur.getScopeType()) || ur.getUid() == null) {
                continue;
            }
            if (ur.getCustomRoleId() == null) {
                rolesByUid.computeIfAbsent(ur.getUid(), k -> new ArrayList<>()).add(ur.getRoleCode());
            } else {
                customIdsByUid.computeIfAbsent(ur.getUid(), k -> new ArrayList<>()).add(ur.getCustomRoleId());
                allCustomIds.add(ur.getCustomRoleId());
            }
        }
        Map<Long, MemberCustomRoleVO> customRoleMap = resolveCustomRoles(allCustomIds);

        List<MemberVO> list = new ArrayList<>(members.size());
        for (User u : members) {
            List<String> roles = sortRoles(rolesByUid.get(u.getId()));
            list.add(new MemberVO(u.getId(), u.getUsername(), u.getNickname(), u.getEmail(), u.getPosition(),
                    u.getStatus(), roles, roles.contains(OrgRole.OWNER), u.getId().equals(actor),
                    u.getCreatedAt(), u.getLastLoginAt(),
                    mapCustomRoles(customIdsByUid.get(u.getId()), customRoleMap)));
        }
        return list;
    }

    // ==================== 角色授予/回收 ====================

    /**
     * 授予组织角色（{@code member:role:assign}）。护栏：角色码合法且可授予（非 OWNER）、不越级、目标在册、未重复。
     * 成功后目标成员 {@code token_version++} + {@code evict}，旧令牌下次请求即失配重登。
     */
    @Transactional
    public void assignRole(MemberRoleForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();
        Long customRoleId = form.customRoleId();

        // customRoleId 非空 → 授予的是「租户自定义角色」：role_code 写其 base 码、custom_role_id 写引用，
        // 自定义角色引擎据此施加 GRANT/DENY 覆盖（见《组织治理专项设计》§2）。需校验该自定义角色属本组织。
        String roleCode;
        if (customRoleId != null) {
            CustomRole cr = requireCustomRole(tenantId, customRoleId);
            roleCode = cr.getBaseRoleCode();
        } else {
            roleCode = form.roleCode();
        }

        if (!OrgRole.isOrg(roleCode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不是有效的组织角色: " + roleCode);
        }
        if (isNonGrantable(roleCode)) {
            throw new BizException(ErrorCode.FORBIDDEN, "OWNER 只能经转让产生，不可直接授予");
        }
        assertNoEscalation(ctx, roleCode);
        User target = requireMember(tenantId, form.uid());

        var dupQ = Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getUid, target.getId())
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getScopeType, UserRole.SCOPE_TENANT)
                .eq(UserRole::getStatus, 1);
        if (customRoleId == null) {
            dupQ.isNull(UserRole::getCustomRoleId);
        } else {
            dupQ.eq(UserRole::getCustomRoleId, customRoleId);
        }
        Long dup = userRoleMapper.selectCount(dupQ);
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该成员已持有此角色");
        }

        UserRole ur = new UserRole();
        ur.setTenantId(tenantId);
        ur.setUid(target.getId());
        ur.setRoleCode(roleCode);
        ur.setCustomRoleId(customRoleId);
        ur.setScopeType(UserRole.SCOPE_TENANT);
        ur.setScopeId(0L);
        ur.setGrantedBy(actor);
        ur.setStatus(1);
        userRoleMapper.insert(ur);

        bumpAndEvict(tenantId, target.getId());
        auditService.record(AuditAction.ROLE_GRANT, AuditAction.RESULT_OK, tenantId, target.getId(),
                "USER_ROLE", roleCode, Map.of("roleCode", roleCode, "uid", target.getId(),
                        "customRoleId", customRoleId == null ? "" : customRoleId.toString()));
    }

    /**
     * 回收组织角色（{@code member:role:assign}）。护栏：不可回收 OWNER（走转让）、不可回收自己的角色、
     * 只可回收权限严格低于自己的成员的角色。回收后目标 {@code token_version++} + {@code evict}。
     */
    @Transactional
    public void revokeRole(MemberRoleForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();
        Long customRoleId = form.customRoleId();
        String roleCode = form.roleCode();

        if (isNonGrantable(roleCode)) {
            throw new BizException(ErrorCode.FORBIDDEN, "OWNER 角色不可回收，请先转让所有权");
        }
        if (form.uid().equals(actor)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能回收自己的角色");
        }
        User target = requireMember(tenantId, form.uid());
        assertCanOperate(ctx, target.getId());

        // customRoleId 非空 → 仅回收该「自定义角色授予」；否则按原语义回收全部同 roleCode 的组织授予。
        var delQ = Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getUid, target.getId())
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getScopeType, UserRole.SCOPE_TENANT);
        if (customRoleId != null) {
            delQ.eq(UserRole::getCustomRoleId, customRoleId);
        }
        int deleted = userRoleMapper.delete(delQ);
        if (deleted == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该成员未持有此角色");
        }

        bumpAndEvict(tenantId, target.getId());
        auditService.record(AuditAction.ROLE_REVOKE, AuditAction.RESULT_OK, tenantId, target.getId(),
                "USER_ROLE", roleCode, Map.of("roleCode", roleCode, "uid", target.getId(),
                        "customRoleId", customRoleId == null ? "" : customRoleId.toString()));
    }

    // ==================== 资料更新 ====================

    /** 更新成员组织内展示资料（昵称、职位）；{@code null} 字段不改。仅落 {@code app_user} 行。 */
    @Transactional
    public MemberVO update(Long uid, MemberUpdateForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        User target = requireMember(tenantId, uid);

        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, target.getId())
                .eq(User::getTenantId, tenantId)
                .set(form.nickname() != null, User::getNickname, form.nickname())
                .set(form.position() != null, User::getPosition, form.position()));

        User reloaded = userMapper.selectById(target.getId());
        List<String> roles = builtInOrgRoles(tenantId, target.getId());
        return new MemberVO(reloaded.getId(), reloaded.getUsername(), reloaded.getNickname(), reloaded.getEmail(),
                reloaded.getPosition(), reloaded.getStatus(), roles, roles.contains(OrgRole.OWNER),
                reloaded.getId().equals(ctx.requireUid()), reloaded.getCreatedAt(), reloaded.getLastLoginAt(),
                customRolesOf(tenantId, target.getId()));
    }

    // ==================== 移除成员 ====================

    /**
     * 移除成员（{@code member:member:delete}）：置 {@code app_user.status=0}（保留行留痕）+
     * {@code token_version++} + 吊销其全部会话（≤1s 下线）+ 释放一个席位 + {@code evict} 版本缓存。
     *
     * <p>护栏：不可移除自己（改用 {@link #selfLeave()}）、不可移除最后一个 OWNER、只可移除权限严格低于自己的成员。
     */
    @Transactional
    public void remove(Long uid) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();

        if (uid.equals(actor)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能移除自己，请改用「退出组织」");
        }
        User target = requireMember(tenantId, uid);
        assertCanOperate(ctx, target.getId());
        assertNotLastOwner(tenantId, target.getId());

        deactivateMember(tenantId, target.getId(), actor);
        auditService.record(AuditAction.MEMBER_REMOVE, AuditAction.RESULT_OK, tenantId, target.getId(),
                "APP_USER", String.valueOf(target.getId()),
                Map.of("uid", target.getId(), "username", nz(target.getUsername()), "self", false));
    }

    /**
     * 主动退出组织（{@code member:self:leave}，矩阵 NYYYYY → 全组织角色可用）。
     *
     * <p>护栏：OWNER 退出前须先转让所有权（{@code OWNER_TRANSFER_REQUIRED}），否则组织将失去所有者。
     * 退出即对自身执行与 {@link #remove} 相同的锁定链路。
     */
    @Transactional
    public void selfLeave() {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();
        User self = requireMember(tenantId, actor);

        if (currentOrgRoles(tenantId, actor).contains(OrgRole.OWNER)) {
            throw new BizException(ErrorCode.OWNER_TRANSFER_REQUIRED, "组织所有者须先转让所有权才能退出");
        }

        deactivateMember(tenantId, self.getId(), actor);
        auditService.record(AuditAction.MEMBER_REMOVE, AuditAction.RESULT_OK, tenantId, self.getId(),
                "APP_USER", String.valueOf(self.getId()),
                Map.of("uid", self.getId(), "username", nz(self.getUsername()), "self", true));
    }

    /** 停用成员行的统一锁定链路：status=0 + token_version++ + 吊销全部会话 + 释放席位 + evict。 */
    private void deactivateMember(Long tenantId, Long uid, Long actor) {
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, uid)
                .eq(User::getTenantId, tenantId)
                .set(User::getStatus, 0));
        userMapper.bumpTokenVersion(uid);
        loginSessionService.revokeAllForUser(tenantId, uid, actor);
        tenantMapper.releaseSeat(tenantId);
        permVersionService.evict(tenantId, uid);
    }

    // ==================== 重置口令 ====================

    /**
     * 重置成员口令（{@code member:password:reset}）：BCrypt 编码后写 {@code app_user.password_hash}
     * （登录实际校验源），若该成员已归并自然人身份则同步 {@code user_identity.password_hash}。
     * 随后 {@code token_version++} + 吊销全部会话 + {@code evict}，强制其重新登录。
     *
     * <p>审计 detail <b>绝不记口令明文</b>，仅记被操作人 uid。
     */
    @Transactional
    public void resetPassword(Long uid, MemberPasswordForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        User target = requireMember(tenantId, uid);

        String hash = passwordEncoder.encode(form.newPassword());
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, target.getId())
                .eq(User::getTenantId, tenantId)
                .set(User::getPasswordHash, hash));
        if (target.hasIdentity()) {
            UserIdentity identity = new UserIdentity();
            identity.setId(target.getIdentityId());
            identity.setPasswordHash(hash);
            userIdentityMapper.updateById(identity);
        }

        bumpAndEvict(tenantId, target.getId());
        loginSessionService.revokeAllForUser(tenantId, target.getId(), ctx.requireUid());
        auditService.record(AuditAction.PASSWORD_RESET, AuditAction.RESULT_OK, tenantId, target.getId(),
                "APP_USER", String.valueOf(target.getId()), Map.of("uid", target.getId()));
    }

    // ==================== 转让所有权 ====================

    /**
     * 转让组织所有权（{@code org:org:transfer}，矩阵 YNNNNN → 仅 OWNER）。
     *
     * <p>原 OWNER 降为 ADMIN（保留管理权、不再是所有者），目标升为 OWNER 并回写 {@code tenant.owner_uid}。
     * 属组织级授权变更：{@code perm_version++} + {@code evictTenant} 使全组织旧令牌失效重登，
     * 并对双方各 {@code token_version++}。这是「OWNER 不可移除/退出/回收」诸护栏的唯一解锁路径。
     */
    @Transactional
    public void transferOwnership(OwnerTransferForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();

        if (!ctx.isOwner()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅组织所有者可转让所有权");
        }
        if (form.targetUid().equals(actor)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不能转让给自己");
        }
        User target = requireMember(tenantId, form.targetUid());

        // 原 OWNER 降为 ADMIN：删其 OWNER 授予、补一条 ADMIN 授予（若尚无）。
        userRoleMapper.delete(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getUid, actor)
                .eq(UserRole::getRoleCode, OrgRole.OWNER)
                .eq(UserRole::getScopeType, UserRole.SCOPE_TENANT));
        ensureRole(tenantId, actor, OrgRole.ADMIN, actor);
        // 目标升为 OWNER。
        ensureRole(tenantId, target.getId(), OrgRole.OWNER, actor);

        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant != null) {
            tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                    .eq(Tenant::getId, tenantId)
                    .set(Tenant::getOwnerUid, target.getId()));
        }

        tenantMapper.bumpPermVersion(tenantId);
        userMapper.bumpTokenVersion(actor);
        userMapper.bumpTokenVersion(target.getId());
        permVersionService.evictTenant(tenantId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromUid", actor);
        detail.put("toUid", target.getId());
        detail.put("toUsername", nz(target.getUsername()));
        auditService.record(AuditAction.OWNER_TRANSFER, AuditAction.RESULT_OK, tenantId, target.getId(),
                "TENANT", String.valueOf(tenantId), detail);
    }

    // ==================== 内部工具 ====================

    /** 插入一条组织角色授予（若目标尚未持有），用于转让时的降级/升级。 */
    private void ensureRole(Long tenantId, Long uid, String roleCode, Long grantedBy) {
        Long dup = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getUid, uid)
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getScopeType, UserRole.SCOPE_TENANT)
                .eq(UserRole::getStatus, 1));
        if (dup != null && dup > 0) {
            return;
        }
        UserRole ur = new UserRole();
        ur.setTenantId(tenantId);
        ur.setUid(uid);
        ur.setRoleCode(roleCode);
        ur.setScopeType(UserRole.SCOPE_TENANT);
        ur.setScopeId(0L);
        ur.setGrantedBy(grantedBy);
        ur.setStatus(1);
        userRoleMapper.insert(ur);
    }

    /**
     * 接受邀请时授予组织角色（无护栏版本）：邀请在创建时已校验角色合法/可授予/不越级，
     * 故此处跳过越级与去重护栏，仅"未持有才插入"并失效版本缓存。供 {@code InvitationService.accept} 复用，
     * 避免接受链路重复实现角色授予逻辑。
     */
    public void grantTenantRole(Long tenantId, Long uid, String roleCode, Long grantedBy) {
        grantTenantRole(tenantId, uid, roleCode, null, grantedBy);
    }

    /**
     * 接受邀请/后台授予时写入组织角色（支持自定义角色）。{@code customRoleId} 非空时
     * {@code user_role.custom_role_id} 写引用、{@code roleCode} 写其 base 码；否则为普通内置角色授予。
     * 仅"未持有才插入"并失效版本缓存。供 {@code InvitationService.accept} 复用。
     */
    public void grantTenantRole(Long tenantId, Long uid, String roleCode, Long customRoleId, Long grantedBy) {
        Long dup = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getUid, uid)
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getScopeType, UserRole.SCOPE_TENANT)
                .eq(UserRole::getStatus, 1));
        if (dup != null && dup > 0) {
            return;
        }
        UserRole ur = new UserRole();
        ur.setTenantId(tenantId);
        ur.setUid(uid);
        ur.setRoleCode(roleCode);
        ur.setCustomRoleId(customRoleId);
        ur.setScopeType(UserRole.SCOPE_TENANT);
        ur.setScopeId(0L);
        ur.setGrantedBy(grantedBy);
        ur.setStatus(1);
        userRoleMapper.insert(ur);
        bumpAndEvict(tenantId, uid);
    }

    /**
     * 接受邀请时授予团队角色（{@code scopeType=TEAM}，{@code scopeId=teamId}）。
     * 仅当同一团队下尚未持有该角色时插入；随后失效版本缓存。
     */
    public void grantTeamRole(Long tenantId, Long uid, String roleCode, Long teamId, Long grantedBy) {
        Long dup = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getUid, uid)
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getScopeType, UserRole.SCOPE_TEAM)
                .eq(UserRole::getScopeId, teamId == null ? 0L : teamId)
                .eq(UserRole::getStatus, 1));
        if (dup != null && dup > 0) {
            return;
        }
        UserRole ur = new UserRole();
        ur.setTenantId(tenantId);
        ur.setUid(uid);
        ur.setRoleCode(roleCode);
        ur.setScopeType(UserRole.SCOPE_TEAM);
        ur.setScopeId(teamId == null ? 0L : teamId);
        ur.setGrantedBy(grantedBy);
        ur.setStatus(1);
        userRoleMapper.insert(ur);
        bumpAndEvict(tenantId, uid);
    }

    /** 单成员敏感变更后的版本失效：{@code token_version++} + {@code evict(tenantId,uid)}。 */
    private void bumpAndEvict(Long tenantId, Long uid) {
        userMapper.bumpTokenVersion(uid);
        permVersionService.evict(tenantId, uid);
    }

    /** 取某成员当前有效的组织角色码集合（scope=TENANT，含自定义角色授予行的 base 码；供越级护栏判定有效上限）。 */
    private List<String> currentOrgRoles(Long tenantId, Long uid) {
        List<String> roles = new ArrayList<>();
        for (UserRole ur : userRoleMapper.selectEffective(tenantId, uid)) {
            if (UserRole.SCOPE_TENANT.equals(ur.getScopeType())) {
                roles.add(ur.getRoleCode());
            }
        }
        return sortRoles(roles);
    }

    /**
     * 取某成员的内置组织角色码集合（scope=TENANT 且 {@code custom_role_id} 为空）——{@code MemberVO.orgRoles} 展示口径。
     * 与 {@link #currentOrgRoles} 刻意区分：后者含自定义角色授予行的 base 码，用于越级护栏（成员有效性上限），
     * 若合并显示会导致「同一个自定义角色授予既是内置角色标签、又是自定义角色标签」的重复呈现。
     */
    private List<String> builtInOrgRoles(Long tenantId, Long uid) {
        List<String> roles = new ArrayList<>();
        for (UserRole ur : userRoleMapper.selectEffective(tenantId, uid)) {
            if (UserRole.SCOPE_TENANT.equals(ur.getScopeType()) && ur.getCustomRoleId() == null) {
                roles.add(ur.getRoleCode());
            }
        }
        return sortRoles(roles);
    }

    /** 取某成员持有的自定义角色授予摘要（scope=TENANT 且 {@code custom_role_id} 非空）。 */
    private List<MemberCustomRoleVO> customRolesOf(Long tenantId, Long uid) {
        Set<Long> ids = new LinkedHashSet<>();
        for (UserRole ur : userRoleMapper.selectEffective(tenantId, uid)) {
            if (UserRole.SCOPE_TENANT.equals(ur.getScopeType()) && ur.getCustomRoleId() != null) {
                ids.add(ur.getCustomRoleId());
            }
        }
        return mapCustomRoles(new ArrayList<>(ids), resolveCustomRoles(ids));
    }

    /** 批量解析自定义角色 id → 摘要（供成员列表一次性装配，避免逐成员 N+1）。 */
    private Map<Long, MemberCustomRoleVO> resolveCustomRoles(Set<Long> ids) {
        Map<Long, MemberCustomRoleVO> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return map;
        }
        for (CustomRole cr : customRoleMapper.selectBatchIds(ids)) {
            map.put(cr.getId(), new MemberCustomRoleVO(cr.getId(), cr.getName(), cr.getBaseRoleCode()));
        }
        return map;
    }

    /** 按 id 列表装配自定义角色摘要（跳过已删除/不可解析者），按 id 降序（与角色列表一致）。 */
    private List<MemberCustomRoleVO> mapCustomRoles(List<Long> ids, Map<Long, MemberCustomRoleVO> map) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<MemberCustomRoleVO> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            MemberCustomRoleVO vo = map.get(id);
            if (vo != null) {
                out.add(vo);
            }
        }
        out.sort(Comparator.comparing(MemberCustomRoleVO::id).reversed());
        return out;
    }

    /** 按主键取行并校验租户归属 + 在册（status=1），任一不满足即视为「成员不存在」，避免越权与横向探测。 */
    private User requireMember(Long tenantId, Long uid) {
        if (uid == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "成员不能为空");
        }
        User user = userMapper.selectById(uid);
        if (user == null || user.getTenantId() == null || !user.getTenantId().equals(tenantId)
                || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "成员不存在或已移除: " + uid);
        }
        return user;
    }

    /** 越级护栏：不可授予比自身最高组织角色更宽的角色（{@link OrgRole#ALL} 索引越小权限越宽）。 */
    private void assertNoEscalation(AccessContext ctx, String roleCode) {
        int actorIdx = highestRoleIndex(ctx.orgRoles());
        int targetIdx = roleIndex(roleCode);
        if (actorIdx >= 0 && targetIdx >= 0 && targetIdx < actorIdx) {
            throw new BizException(ErrorCode.ROLE_GRANT_ESCALATION, "不可授予超出自身权限的角色");
        }
    }

    /** 操作护栏：只可对权限严格低于自己的成员执行移除/回收（不可动同级或更宽者）。 */
    private void assertCanOperate(AccessContext ctx, Long targetUid) {
        int actorIdx = highestRoleIndex(ctx.orgRoles());
        int targetIdx = highestRoleIndex(Set.copyOf(currentOrgRoles(ctx.requireTenantId(), targetUid)));
        if (actorIdx >= 0 && targetIdx >= 0 && targetIdx <= actorIdx) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能操作权限不低于自己的成员");
        }
    }

    /** 最后一个 OWNER 护栏：目标若是 OWNER 且组织内仅剩一个 OWNER，则拒绝移除。 */
    private void assertNotLastOwner(Long tenantId, Long targetUid) {
        List<String> roles = currentOrgRoles(tenantId, targetUid);
        if (roles.contains(OrgRole.OWNER) && userRoleMapper.countTenantRoleHolders(tenantId, OrgRole.OWNER) <= 1) {
            throw new BizException(ErrorCode.OWNER_TRANSFER_REQUIRED, "不能移除最后一个组织所有者，请先转让所有权");
        }
    }

    /** 取自定义角色并校验租户归属（同 CustomRoleService.requireOwned 口径，供授权时校验引用合法性）。 */
    private CustomRole requireCustomRole(Long tenantId, Long customRoleId) {
        CustomRole cr = customRoleMapper.selectById(customRoleId);
        if (cr == null || cr.getTenantId() == null || !cr.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "自定义角色不存在或不属于本组织: " + customRoleId);
        }
        return cr;
    }

    private boolean isNonGrantable(String roleCode) {
        for (String r : OrgRole.NON_GRANTABLE) {
            if (r.equals(roleCode)) {
                return true;
            }
        }
        return false;
    }

    /** 角色集合中权限最宽者（{@link OrgRole#ALL} 索引最小）的索引；无有效组织角色返回 -1。 */
    private int highestRoleIndex(Set<String> roles) {
        int best = -1;
        if (roles == null) {
            return best;
        }
        for (String r : roles) {
            int idx = roleIndex(r);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private int roleIndex(String roleCode) {
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

    /** 按 {@link OrgRole#ALL} 顺序（权限宽窄降序）排序并去重角色码，未知码排在末尾。 */
    private List<String> sortRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        List<String> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Set<String> distinct = new java.util.LinkedHashSet<>(roles);
        for (String code : OrgRole.ALL) {
            if (distinct.contains(code)) {
                known.add(code);
            }
        }
        for (String code : distinct) {
            if (roleIndex(code) < 0) {
                unknown.add(code);
            }
        }
        known.addAll(unknown);
        return known;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
