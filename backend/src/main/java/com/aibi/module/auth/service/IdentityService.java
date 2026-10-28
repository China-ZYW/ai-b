package com.aibi.module.auth.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextBuilder;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.JwtTokenProvider;
import com.aibi.module.auth.dto.AuthResponse;
import com.aibi.module.auth.dto.MyTenantVO;
import com.aibi.module.auth.dto.UserVO;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserIdentityMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多组织身份服务（C 缺口）：支撑「一个自然人跨多个组织统一身份 + 组织切换」。
 *
 * <p>模型层已就绪：{@code user_identity.id} 为统一自然人，多个 {@code app_user} 行经各自的
 * {@code identity_id} 指向同一自然人（见《角色权限体系与组织协同设计》2.3 方案 A）。本服务把这部分
 * 能力暴露为两个闭环操作：
 * <ul>
 *   <li>{@link #listMyTenants()}：「我的组织」列表，供前端组织切换器渲染；</li>
 *   <li>{@link #switchTenant(Long)}：在已认证会话内，切换到该身份的另一个成员组织，重新签发令牌
 *       （换 token 而非请求期改租户）。</li>
 * </ul>
 *
 * <p>安全约束：切换目标必须是<b>当前身份</b>的成员组织（经 {@code app_user.identity_id + tenant_id} 校验），
 * 不可越权进入他组织；旧组织会话不强制吊销（多端多组织并存），但新令牌的 {@code pv}/{@code sid}
 * 按新 {@code (tenant,uid)} 独立计算，旧令牌仍受各自的会话吊销与版本号约束。
 */
@Service
public class IdentityService {

    private final UserIdentityMapper identityMapper;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final AccessContextBuilder contextBuilder;
    private final JwtTokenProvider tokenProvider;
    private final LoginSessionService sessionService;
    private final AuditService auditService;

    public IdentityService(UserIdentityMapper identityMapper,
                           UserMapper userMapper,
                           TenantMapper tenantMapper,
                           AccessContextBuilder contextBuilder,
                           JwtTokenProvider tokenProvider,
                           LoginSessionService sessionService,
                           AuditService auditService) {
        this.identityMapper = identityMapper;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.contextBuilder = contextBuilder;
        this.tokenProvider = tokenProvider;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    /**
     * 当前自然人身份可切换的组织列表（含组织名、该组织角色、是否当前组织）。
     *
     * <p>存量未归并到 {@code user_identity} 的账号没有多组织概念，返回空列表（前端不显示切换器）。
     */
    public List<MyTenantVO> listMyTenants() {
        AccessContext ctx = AccessContextHolder.require();
        Long identityId = ctx.identityId();
        if (identityId == null) {
            return List.of();
        }
        List<Long> uids = identityMapper.selectMemberUids(identityId);
        if (uids == null || uids.isEmpty()) {
            return List.of();
        }
        List<User> members = userMapper.selectBatchIds(uids);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        Long currentTid = ctx.tenantId();
        List<MyTenantVO> result = new ArrayList<>(members.size());
        for (User u : members) {
            Tenant t = tenantMapper.selectById(u.getTenantId());
            String name = t == null ? null : t.getName();
            result.add(new MyTenantVO(u.getTenantId(), name, orgRolesOf(u, t), u.getTenantId().equals(currentTid)));
        }
        return result;
    }

    /**
     * 切换到当前身份的另一个成员组织，重新签发令牌（换 token，而非请求期改租户）。
     *
     * <p>要求已登录；目标组织必须经 {@code app_user.identity_id + tenant_id} 校验确属当前身份，
     * 否则视为非成员。切换后新令牌按新 {@code (tenant,uid)} 计算 {@code pv}/{@code sid}。
     */
    public AuthResponse switchTenant(Long targetTenantId) {
        if (targetTenantId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少目标组织");
        }
        AccessContext ctx = AccessContextHolder.require();
        Long identityId = ctx.identityId();
        if (identityId == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前账号不支持组织切换");
        }
        User target = userMapper.selectByIdentityAndTenant(identityId, targetTenantId);
        if (target == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "你不是该组织的成员");
        }
        Tenant tenant = tenantMapper.selectById(targetTenantId);
        if (tenant == null || (tenant.getStatus() != null && tenant.getStatus() == 0)) {
            throw new BizException(ErrorCode.TENANT_DISABLED, "目标组织不可用");
        }
        // 复用登录期全量解析 + 令牌签发 + 会话登记路径，确保角色/团队/数据范围与登录完全一致
        AccessContext newCtx = contextBuilder.buildForLogin(target, tenant, null);
        JwtTokenProvider.IssuedToken issued = tokenProvider.issue(newCtx);
        sessionService.register(newCtx, issued.accessToken(), issued.expiresAt());
        long expiresIn = (issued.expiresAt().getTime() - issued.issuedAt().getTime()) / 1000;
        try {
            auditService.record(AuditAction.ORG_SWITCH, AuditAction.RESULT_OK, targetTenantId, target.getId(),
                    "ORG", String.valueOf(identityId),
                    Map.of("from", String.valueOf(ctx.tenantId()), "to", String.valueOf(targetTenantId)));
        } catch (Exception ignored) {
            // 审计失败不反噬业务
        }
        return new AuthResponse(issued.accessToken(), "Bearer", expiresIn, toVO(target, tenant));
    }

    /** 取某成员在其组织内的组织角色码（逗号分隔）；解析异常时返回空串。 */
    private String orgRolesOf(User u, Tenant t) {
        try {
            AccessContext c = contextBuilder.buildForLogin(u, t, null);
            return c.orgRoles().isEmpty() ? "" : String.join(",", c.orgRoles());
        } catch (Exception e) {
            return "";
        }
    }

    /** 构造对外安全资料视图（与 {@code UserServiceImpl.toVO} 口径一致）。 */
    private UserVO toVO(User user, Tenant tenant) {
        String plan = tenant != null && tenant.getPlan() != null ? tenant.getPlan() : "FREE";
        return new UserVO(user.getId(), user.getTenantId(), user.getUsername(),
                user.getNickname(), user.getEmail(), plan, user.getRole() == null ? "USER" : user.getRole(),
                user.uiThemeOrDefault());
    }
}
