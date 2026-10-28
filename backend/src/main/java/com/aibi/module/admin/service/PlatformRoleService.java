package com.aibi.module.admin.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.PlatformRole;
import com.aibi.common.security.UserContext;
import com.aibi.module.admin.dto.PlatformRoleGrantForm;
import com.aibi.module.admin.dto.PlatformRoleHolderVO;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.rbac.entity.UserRole;
import com.aibi.module.rbac.mapper.UserRoleMapper;
import com.aibi.module.rbac.service.PermVersionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台角色授予/回收服务（§9.2 治理组，唯一写侧治理页）。
 *
 * <p>平台角色存放于 {@code user_role}：{@code tenant_id=0 / scope_type=PLATFORM / scope_id=0 / status=1}，
 * 与 {@code AccessContextBuilder.resolvePlatformRoles}（{@code selectEffective(0L, uid)} 后取平台码）同源。
 * 授予即在平台域插一行、回收即删该行，二者都 {@code evict} 版本缓存使变更立即对旧令牌生效。
 *
 * <p>{@code user_role} 带 {@code tenant_id} 却在租户拦截器 IGNORE 名单，故本服务每条查询都<b>显式</b>
 * 携带 {@code tenant_id=0}（见 {@link UserRoleMapper} 类注释：漏写即跨组织越权）。
 *
 * <p>护栏（防自锁）：不可回收自己的 {@code SUPER_ADMIN}；不可回收最后一个 {@code SUPER_ADMIN}。
 */
@Service
public class PlatformRoleService {

    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final PermVersionService permVersionService;
    private final AuditService auditService;

    public PlatformRoleService(UserRoleMapper userRoleMapper,
                               UserMapper userMapper,
                               PermVersionService permVersionService,
                               AuditService auditService) {
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.permVersionService = permVersionService;
        this.auditService = auditService;
    }

    /**
     * 全部平台角色持有者（联 {@code app_user} 取账号信息）。
     *
     * <p>先取 {@code user_role} 平台域行，再按 {@code uid} 批量查账号（一次 {@code selectBatchIds}，
     * 避免逐行 N+1）。仅保留 {@link PlatformRole#isPlatform} 的角色码——正常数据下平台域只会有平台角色，
     * 这里是防御性过滤（脏数据不至于渲染成"持有者"）。
     */
    public List<PlatformRoleHolderVO> listHolders() {
        List<UserRole> grants = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, 0L)
                .eq(UserRole::getScopeType, UserRole.SCOPE_PLATFORM)
                .eq(UserRole::getStatus, 1)
                .orderByAsc(UserRole::getId));

        Set<Long> uids = new LinkedHashSet<>();
        for (UserRole g : grants) {
            if (PlatformRole.isPlatform(g.getRoleCode()) && g.getUid() != null) {
                uids.add(g.getUid());
            }
        }
        Map<Long, User> users = new HashMap<>();
        if (!uids.isEmpty()) {
            for (User u : userMapper.selectBatchIds(uids)) {
                users.put(u.getId(), u);
            }
        }

        List<PlatformRoleHolderVO> list = new ArrayList<>();
        for (UserRole g : grants) {
            if (!PlatformRole.isPlatform(g.getRoleCode())) {
                continue;
            }
            User u = users.get(g.getUid());
            list.add(new PlatformRoleHolderVO(g.getId(), g.getUid(),
                    u == null ? null : u.getUsername(),
                    u == null ? null : u.getNickname(),
                    u == null ? null : u.getEmail(),
                    g.getRoleCode(), g.getCreatedAt(), g.getExpireAt()));
        }
        return list;
    }

    /**
     * 授予平台角色。校验：角色码合法、账号存在且为平台账号（{@code tenant_id=0}）、未重复持有。
     * 成功后 {@code evict} 该身份版本缓存，使新授权对其后续请求即时生效。
     */
    @Transactional
    public PlatformRoleHolderVO grant(PlatformRoleGrantForm form) {
        String roleCode = form.roleCode();
        if (!PlatformRole.isPlatform(roleCode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不是有效的平台角色: " + roleCode);
        }
        User user = userMapper.selectById(form.uid());
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "账号不存在: " + form.uid());
        }
        if (user.getTenantId() == null || user.getTenantId() != 0L) {
            throw new BizException(ErrorCode.PARAM_ERROR, "平台角色只能授予平台账号（tenant_id=0）");
        }
        Long dup = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, 0L)
                .eq(UserRole::getUid, form.uid())
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getScopeType, UserRole.SCOPE_PLATFORM)
                .eq(UserRole::getStatus, 1));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该账号已持有此平台角色");
        }

        UserRole ur = new UserRole();
        ur.setTenantId(0L);
        ur.setUid(form.uid());
        ur.setRoleCode(roleCode);
        ur.setScopeType(UserRole.SCOPE_PLATFORM);
        ur.setScopeId(0L);
        ur.setGrantedBy(UserContext.userId());
        ur.setStatus(1);
        userRoleMapper.insert(ur);
        permVersionService.evict(0L, form.uid());

        auditService.record(AuditAction.PLATFORM_ROLE_GRANT, AuditAction.RESULT_OK, 0L, form.uid(),
                "USER_ROLE", roleCode, Map.of("roleCode", roleCode));

        return new PlatformRoleHolderVO(ur.getId(), ur.getUid(), user.getUsername(), user.getNickname(),
                user.getEmail(), roleCode, ur.getCreatedAt(), ur.getExpireAt());
    }

    /**
     * 回收平台角色（按 {@code user_role} 行主键精确删除）。
     *
     * <p>护栏：不可回收自己的 {@code SUPER_ADMIN}（自锁）；不可回收最后一个 {@code SUPER_ADMIN}
     * （否则平台后台再无人可管理）。回收后 {@code evict} 版本缓存，旧令牌下一次请求即失配下线。
     */
    @Transactional
    public void revoke(Long id) {
        UserRole ur = userRoleMapper.selectById(id);
        if (ur == null || ur.getTenantId() == null || ur.getTenantId() != 0L
                || !UserRole.SCOPE_PLATFORM.equals(ur.getScopeType())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "平台角色授予不存在: " + id);
        }
        String roleCode = ur.getRoleCode();
        Long uid = ur.getUid();
        if (PlatformRole.SUPER_ADMIN.equals(roleCode)) {
            if (uid != null && uid.equals(UserContext.userId())) {
                throw new BizException(ErrorCode.FORBIDDEN, "不能回收自己的超级管理员角色");
            }
            if (countPlatformSuperAdmins() <= 1) {
                throw new BizException(ErrorCode.FORBIDDEN, "不能回收最后一个超级管理员");
            }
        }
        userRoleMapper.deleteById(id);
        permVersionService.evict(0L, uid);

        auditService.record(AuditAction.PLATFORM_ROLE_REVOKE, AuditAction.RESULT_OK, 0L, uid,
                "USER_ROLE", roleCode, Map.of("roleCode", roleCode));
    }

    /** 当前平台域有效的 {@code SUPER_ADMIN} 授予数（"不可回收最后一个"护栏用）。 */
    private long countPlatformSuperAdmins() {
        Long count = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, 0L)
                .eq(UserRole::getRoleCode, PlatformRole.SUPER_ADMIN)
                .eq(UserRole::getScopeType, UserRole.SCOPE_PLATFORM)
                .eq(UserRole::getStatus, 1));
        return count == null ? 0L : count;
    }
}
