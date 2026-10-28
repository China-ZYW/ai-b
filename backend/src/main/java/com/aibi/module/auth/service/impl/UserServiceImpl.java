package com.aibi.module.auth.service.impl;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextBuilder;
import com.aibi.common.security.JwtTokenProvider;
import com.aibi.common.security.OrgRole;
import com.aibi.common.security.UserContext;
import com.aibi.common.tenant.PlanType;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.dto.AuthResponse;
import com.aibi.module.auth.dto.LoginRequest;
import com.aibi.module.auth.dto.RegisterRequest;
import com.aibi.module.auth.dto.UpdateProfileRequest;
import com.aibi.module.auth.dto.UserVO;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.auth.service.LoginSessionService;
import com.aibi.module.auth.service.UserService;
import com.aibi.module.org.service.InvitationService;
import com.aibi.module.rbac.entity.UserRole;
import com.aibi.module.rbac.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户服务实现（MyBatis-Plus 四层架构之 ServiceImpl）。
 *
 * <p>{@code app_user} 在租户拦截器 IGNORE 名单，故用 {@code save/getById/lambdaQuery/updateById}
 * 操作不会被追加 tenant_id；注册时手动 {@code setTenantId} 建立"新租户 + 首个用户"的归属关系。
 * 租户本身经 {@link TenantMapper} 落库（{@code tenant} 亦在 IGNORE 名单）。
 *
 * <p><b>令牌签发已升级</b>：不再直接拼 {@code uid/tid/plan/role} 四个 claim，而是先由
 * {@link AccessContextBuilder#buildForLogin} 解析出完整访问上下文（平台/组织/团队角色、当前团队、
 * 数据范围、新 {@code sid}），再交给 {@link JwtTokenProvider#issue(AccessContext)} 签发，
 * 并登记 {@code login_session} 使其可被精确吊销（6.3）。
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final TenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AccessContextBuilder contextBuilder;
    private final LoginSessionService sessionService;
    private final UserRoleMapper userRoleMapper;
    private final AuditService auditService;
    private final InvitationService invitationService;

    public UserServiceImpl(TenantMapper tenantMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider,
                           AccessContextBuilder contextBuilder,
                           LoginSessionService sessionService,
                           UserRoleMapper userRoleMapper,
                           AuditService auditService,
                           InvitationService invitationService) {
        this.tenantMapper = tenantMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.contextBuilder = contextBuilder;
        this.sessionService = sessionService;
        this.userRoleMapper = userRoleMapper;
        this.auditService = auditService;
        this.invitationService = invitationService;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // 携带有效邀请令牌 → 在邀请所属组织内建账号并自动接受，避免凭空开新组织
        if (request.inviteToken() != null && !request.inviteToken().isBlank()) {
            return registerViaInvite(request);
        }

        long exists = lambdaQuery().eq(User::getUsername, request.username()).count();
        if (exists > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户名已被注册");
        }

        // 邮箱全局唯一：体验版可无限注册的根因是 email 仅做格式校验、不查重，
        // 故可用 NULL 邮箱 + 不同用户名批量刷号绕过每日 20 次配额。改为强校验。
        long emailExists = lambdaQuery().eq(User::getEmail, request.email()).count();
        if (emailExists > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该邮箱已被注册");
        }

        // 每 IP 仅可自助开通一个免费工作区（邀请注册不走此限制，见 registerViaInvite）。
        // 根因：自助注册每次新建独立租户，无来源约束，可无限刷号占用资源。
        String regIp = clientIp();
        if (regIp != null) {
            long ipUsed = tenantMapper.selectCount(Wrappers.<Tenant>lambdaQuery()
                    .eq(Tenant::getRegisterIp, regIp).eq(Tenant::getStatus, 1));
            if (ipUsed > 0) {
                throw new BizException(ErrorCode.PARAM_ERROR, "该 IP 已注册过，每个 IP 仅可开通一个免费工作区");
            }
        }

        Tenant tenant = new Tenant();
        tenant.setName(request.username() + " 的工作区");
        tenant.setPlan(PlanType.FREE.name());
        tenant.setIsolationMode(PlanType.FREE.defaultIsolationMode().name());
        tenant.setStatus(1);
        tenant.setDailyLimit(20);   // FREE 默认每日 20 次
        tenant.setTotalLimit(-1L);  // 不限累计总量
        tenant.setTotalUsed(0L);
        // 席位按套餐默认值而非 -1：-1 是存量行的兼容默认（11.1.2 "默认值等价于升级前行为"），
        // 新开通的组织应从一开始就受套餐约束，否则 FREE 组织能无限制拉人
        tenant.setSeatLimit(PlanType.FREE.defaultSeatLimit());
        tenant.setSeatUsed(0);
        tenant.setPermVersion(0L);
        tenant.setRegisterIp(regIp);
        tenantMapper.insert(tenant);

        User user = new User();
        user.setTenantId(tenant.getId());
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(isBlank(request.nickname()) ? request.username() : request.nickname());
        user.setEmail(request.email());
        user.setRole("USER");
        user.setStatus(1);
        user.setTokenVersion(0L);
        save(user);   // IdType 回填主键

        // owner_uid 与 seat_used 依赖 user.id，只能在用户落库后回写一次
        tenant.setOwnerUid(user.getId());
        tenant.setSeatUsed(1);
        tenantMapper.updateById(tenant);
        grantOwner(tenant.getId(), user.getId());

        log.info("新用户注册: uid={}, tenant={}", user.getId(), tenant.getId());
        return buildAuthResponse(user, tenant);
    }

    /**
     * 「注册即加入组织」：携带有效邀请令牌时，在邀请所属租户内建账号并自动接受，
     * 不再新建租户/授予 OWNER。注册人成为该组织的受邀角色成员。
     *
     * <p>与自助注册的区别：自助注册（无令牌）会新建租户并设为 OWNER；本路径复用邀请约定的租户与角色，
     * 由 {@link InvitationService#acceptAs} 统一完成席位占用与角色授予，避免产生孤立工作区。
     */
    private AuthResponse registerViaInvite(RegisterRequest request) {
        // 携带令牌时仍需全局唯一用户名（app_user 在租户拦截器 IGNORE 名单）
        long exists = lambdaQuery().eq(User::getUsername, request.username()).count();
        if (exists > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "用户名已被注册");
        }
        // 邮箱唯一规则与自助注册一致（app_user 在租户拦截器 IGNORE 名单，查重为全局）
        long emailExists = lambdaQuery().eq(User::getEmail, request.email()).count();
        if (emailExists > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该邮箱已被注册");
        }
        // 校验邀请可用性与注册邮箱限制（无效/过期/撤销/席位满/邮箱不符均抛 BizException）
        com.aibi.module.org.entity.Invitation inv =
                invitationService.resolveForRegister(request.inviteToken(), request.email());
        Long targetTenant = inv.getTenantId();

        User user = new User();
        user.setTenantId(targetTenant);
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(isBlank(request.nickname()) ? defaultNickname(request) : request.nickname());
        user.setEmail(request.email());
        user.setRole("USER");
        user.setStatus(1);
        user.setTokenVersion(0L);
        user.setCurrentTeamId(inv.getTeamId());
        save(user);   // IdType 回填主键

        // 以新账号身份完成邀请接受（授予角色、占用席位、置 ACCEPTED/PENDING）
        invitationService.acceptAs(user, request.inviteToken());

        Tenant tenant = tenantMapper.selectById(targetTenant);
        log.info("邀请注册并加入组织: uid={}, tenant={}, invitation={}", user.getId(), targetTenant, inv.getId());
        return buildAuthResponse(user, tenant);
    }

    /** 注册昵称缺省值：优先取邮箱本地名，否则用用户名。 */
    private String defaultNickname(RegisterRequest request) {
        if (!isBlank(request.email()) && request.email().contains("@")) {
            return request.email().substring(0, request.email().indexOf('@'));
        }
        return request.username();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        User user = getOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, request.username()), false);
        // 多组织身份：若已归并自然人且本次登录指定了目标组织，则在该身份的各组织中定位目标组织成员行
        if (user != null && user.hasIdentity() && request.tenantId() != null) {
            // 目标组织必须是该身份的成员组织，否则视为无此账号（落入下方登录失败分支）
            user = baseMapper.selectByIdentityAndTenant(user.getIdentityId(), request.tenantId());
        }
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 登录失败入审计（10.1）：记尝试的用户名与原因，绝不记口令；用户不存在时 actor 记 SYSTEM
            auditService.recordAuth(AuditAction.LOGIN_FAILED, AuditAction.RESULT_FAILED,
                    user == null ? null : user.getId(),
                    user == null ? null : user.getTenantId(),
                    user == null ? null : user.getIdentityId(),
                    "AUTH", request.username(),
                    Map.of("username", request.username(), "reason", user == null ? "用户不存在" : "密码错误"));
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            auditService.recordAuth(AuditAction.LOGIN_FAILED, AuditAction.RESULT_DENIED,
                    user.getId(), user.getTenantId(), user.getIdentityId(),
                    "AUTH", request.username(),
                    Map.of("username", request.username(), "reason", "账号已禁用"));
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        boolean superAdmin = UserContext.ROLE_SUPER_ADMIN.equals(user.getRole());
        Tenant tenant = tenantMapper.selectById(user.getTenantId());
        // 非超管需校验所属租户的启用状态与使用期限（超管为平台级、tenant_id=0，跳过门禁）
        if (!superAdmin && tenant != null) {
            if (tenant.getStatus() != null && tenant.getStatus() == 0) {
                auditService.recordAuth(AuditAction.LOGIN_FAILED, AuditAction.RESULT_DENIED,
                        user.getId(), user.getTenantId(), user.getIdentityId(),
                        "AUTH", request.username(),
                        Map.of("username", request.username(), "reason", "租户已停用"));
                throw new BizException(ErrorCode.TENANT_DISABLED);
            }
            if (tenant.getExpireAt() != null && tenant.getExpireAt().isBefore(LocalDateTime.now())) {
                auditService.recordAuth(AuditAction.LOGIN_FAILED, AuditAction.RESULT_DENIED,
                        user.getId(), user.getTenantId(), user.getIdentityId(),
                        "AUTH", request.username(),
                        Map.of("username", request.username(), "reason", "租户已过期"));
                throw new BizException(ErrorCode.TENANT_EXPIRED);
            }
        }
        AuthResponse response = buildAuthResponse(user, tenant);
        // 登录成功入审计（10.1）：actor 即登录用户，此时访问上下文尚未建立，用 recordAuth 显式传入
        auditService.recordAuth(AuditAction.LOGIN, AuditAction.RESULT_OK,
                user.getId(), user.getTenantId(), user.getIdentityId(),
                "AUTH", user.getUsername(), null);
        return response;
    }

    /**
     * 登出：吊销当前令牌对应的会话行。
     *
     * <p>不再是空实现。仅靠前端丢弃令牌时，已泄露或被缓存的令牌在
     * {@code aibi.jwt.expire-minutes}（当前 120 分钟）内仍然可用。
     */
    @Override
    public void logout() {
        boolean revoked = sessionService.revokeCurrent();
        Long uid = UserContext.userId();
        log.info("用户登出: uid={}, 会话吊销={}", uid, revoked);
        // 登出时请求上下文仍在（JwtAuthFilter 已还原），故用 record() 自动捕获操作人/ip/ua
        auditService.record(AuditAction.LOGOUT, AuditAction.RESULT_OK, null, null,
                "AUTH", null, Map.of("revoked", revoked));
    }

    @Override
    public UserVO currentUser() {
        User user = requireCurrentUser();
        return toVO(user, planOf(user.getTenantId()));
    }

    @Override
    @Transactional
    public UserVO updateProfile(UpdateProfileRequest request) {
        User user = requireCurrentUser();
        if (request.nickname() != null) {
            user.setNickname(request.nickname());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.uiTheme() != null) {
            user.setUiTheme(request.uiTheme());
        }
        updateById(user);
        return toVO(user, planOf(user.getTenantId()));
    }

    private User requireCurrentUser() {
        Long uid = UserContext.userId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        User user = getById(uid);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在或已注销");
        }
        return user;
    }

    private String planOf(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        return tenant != null ? tenant.getPlan() : PlanType.FREE.name();
    }

    private AuthResponse buildAuthResponse(User user, Tenant tenant) {
        AccessContext context = contextBuilder.buildForLogin(user, tenant, null);
        JwtTokenProvider.IssuedToken issued = tokenProvider.issue(context);
        // 必须在签发之后、返回之前登记：一旦响应发出，令牌就在客户端手里了，
        // 此时若登记失败，该令牌将不受吊销保护（服务内部已对此告警）
        sessionService.register(context, issued.accessToken(), issued.expiresAt());
        markLogin(user);
        long expiresInSeconds = (issued.expiresAt().getTime() - issued.issuedAt().getTime()) / 1000;
        return new AuthResponse(issued.accessToken(), "Bearer", expiresInSeconds,
                toVO(user, context.plan().name()));
    }

    /**
     * 为组织创建者写入 {@code OWNER} 授予行。
     *
     * <p>不能只依赖 {@link AccessContextBuilder} 的"整组织未回填"回退分支：那是为存量库准备的兜底，
     * 若新注册的正常路径也走它，每个新组织的首次登录都会打一条"尚未回填"的 warn，
     * 真正需要关注的存量告警会被淹没。
     */
    private void grantOwner(Long tenantId, Long uid) {
        try {
            UserRole grant = new UserRole();
            grant.setTenantId(tenantId);
            grant.setUid(uid);
            grant.setRoleCode(OrgRole.OWNER);
            grant.setScopeType(UserRole.SCOPE_TENANT);
            grant.setScopeId(0L);
            grant.setGrantedBy(uid);
            grant.setStatus(1);
            userRoleMapper.insert(grant);
        } catch (Exception e) {
            // 写入失败不阻断注册：回退分支仍会解析出 OWNER，业务可用，但需根据 warn 补行
            log.warn("注册时写入 OWNER 角色授予失败: tenant={}, uid={}, err={}", tenantId, uid, e.getMessage());
        }
    }

    /** 回写最后登录时间（审计与"长期未活跃账号"治理用）；失败不影响登录。 */
    private void markLogin(User user) {
        try {
            User patch = new User();
            patch.setId(user.getId());
            patch.setLastLoginAt(LocalDateTime.now());
            updateById(patch);
        } catch (Exception e) {
            log.debug("回写最后登录时间失败: uid={}, err={}", user.getId(), e.getMessage());
        }
    }

    private UserVO toVO(User user, String plan) {
        return new UserVO(user.getId(), user.getTenantId(), user.getUsername(),
                user.getNickname(), user.getEmail(), plan,
                user.getRole() == null ? "USER" : user.getRole(),
                user.uiThemeOrDefault());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 解析真实客户端 IP（用于注册防刷门禁，须抗伪造）：
     * <ul>
     *   <li>优先 {@code X-Real-IP}：nginx 以 {@code proxy_set_header X-Real-IP $remote_addr} <b>覆写</b>（非追加），
     *       值是 nginx 直连的对端，客户端无法伪造，最可信；</li>
     *   <li>否则取 {@code X-Forwarded-For} 的<b>末段</b>：nginx 用 {@code $proxy_add_x_forwarded_for} 把真实对端
     *       追加到末尾，而<b>首段可被客户端伪造</b>，故不能取首段；</li>
     *   <li>兜底 {@code getRemoteAddr()}（直连或取不到头时）。</li>
     * </ul>
     * 取不到（无请求上下文等边界）时返回 {@code null}，由调用方决定是否放行——绝不因取不到 IP 而误拦截正常注册。
     */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest req = attrs.getRequest();
            String realIp = req.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // 取末段：真实对端由 nginx 追加在最后，首段可被客户端伪造
                String[] parts = xff.split(",");
                return parts[parts.length - 1].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
