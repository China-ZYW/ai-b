package com.aibi.module.org.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.OrgRole;
import com.aibi.common.security.TokenDigest;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.entity.UserIdentity;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserIdentityMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.org.dto.InvitationAcceptResult;
import com.aibi.module.org.dto.InvitationCreateForm;
import com.aibi.module.org.dto.InvitationCreateResult;
import com.aibi.module.org.dto.InvitationPreviewVO;
import com.aibi.module.org.dto.InvitationVO;
import com.aibi.module.org.entity.Invitation;
import com.aibi.module.org.mapper.InvitationMapper;
import com.aibi.module.org.service.MemberService;
import com.aibi.module.rbac.entity.UserRole;
import com.aibi.module.rbac.mapper.UserRoleMapper;
import com.aibi.module.rbac.service.PermVersionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 组织邀请管理服务（§9.2 组织设置 · 邀请，管理侧 create/list/revoke）。
 *
 * <p>令牌只存 {@code SHA-256(明文)}（{@link TokenDigest}），明文仅创建结果里返回一次；任何列表/详情
 * 都<b>不含</b> {@code tokenHash}。{@code invitation} 在租户拦截器 IGNORE 名单（为支持匿名接受），故本服务
 * 管理侧每条读写都<b>显式携带 {@code tenant_id}</b>，读出口以 {@code selectByIdAndTenant} 兜底防横向越权。
 *
 * <p><b>接受链路（accept）已落地</b>：被邀请者凭明文令牌在登录后调用 {@link #accept}，服务端校验令牌/状态/
 * 过期/席位与定向邮箱·邮箱域限制后，按方案 A 在其目标组织（邀请所属租户）解析或新建 {@code app_user} 身份，
 * 原子占用邀请席位与租户席位，并经 {@link MemberService} 授予邀请约定的组织/团队角色，最终置
 * {@code ACCEPTED}/{@code PENDING}。原注释担心的"多组织身份登录"由登录侧按 {@code user_identity} 跨组织归并解决，
 * 此处只需保证目标组织成员身份被正确建立即可。{@code InvitationMapper}/{@code TenantMapper} 的
 * {@code consumeSeat} 原子占位在此被真实消费。
 *
 * <p>护栏：{@code OWNER} 属 {@link OrgRole#NON_GRANTABLE}，不可经邀请授予（创建时已拦）；不可邀请超出自身权限的角色
 * （{@code ROLE_GRANT_ESCALATION}，创建时已拦）；{@code seats} 受 {@code max-seats-per-link} 上限约束。
 */
@Service
public class InvitationService {

    private final InvitationMapper invitationMapper;
    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final UserIdentityMapper userIdentityMapper;
    private final UserRoleMapper userRoleMapper;
    private final MemberService memberService;
    private final PermVersionService permVersionService;
    private final AuditService auditService;

    /** 邀请令牌有效期（天）。 */
    @Value("${aibi.rbac.invite.token-ttl-days:7}")
    private int tokenTtlDays;

    /** 单条邀请链接可接受的席位数上限（{@code seats=-1} 不限时仍以此值封顶，防一次性放开过多席位）。 */
    @Value("${aibi.rbac.invite.max-seats-per-link:50}")
    private int maxSeatsPerLink;

    /** 是否允许匿名接受（无需登录）；生产建议 false，据此决定 {@code need_login} 默认值。 */
    @Value("${aibi.rbac.invite.anonymous-accept:false}")
    private boolean anonymousAccept;

    public InvitationService(InvitationMapper invitationMapper,
                             TenantMapper tenantMapper,
                             UserMapper userMapper,
                             UserIdentityMapper userIdentityMapper,
                             UserRoleMapper userRoleMapper,
                             MemberService memberService,
                             PermVersionService permVersionService,
                             AuditService auditService) {
        this.invitationMapper = invitationMapper;
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.userIdentityMapper = userIdentityMapper;
        this.userRoleMapper = userRoleMapper;
        this.memberService = memberService;
        this.permVersionService = permVersionService;
        this.auditService = auditService;
    }

    // ==================== 创建 ====================

    /**
     * 发起邀请（{@code member:invite:create}）。生成随机令牌、只落哈希，返回一次性明文与可分发链接。
     *
     * <p>缺省规则见 {@link InvitationCreateForm}：channel→LINK、teamId→组织默认团队、seats→1（受上限封顶）、
     * expireDays→{@code token-ttl-days}、requireApproval→0、needLogin→由 {@code anonymous-accept} 决定。
     */
    @Transactional
    public InvitationCreateResult create(InvitationCreateForm form) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        Long actor = ctx.requireUid();

        String roleCode = form.roleCode();
        if (!OrgRole.isOrg(roleCode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不是有效的组织角色: " + roleCode);
        }
        if (isNonGrantable(roleCode)) {
            throw new BizException(ErrorCode.FORBIDDEN, "OWNER 只能经转让产生，不可通过邀请授予");
        }
        assertNoEscalation(ctx, roleCode);

        Tenant tenant = tenantMapper.selectById(tenantId);
        Long teamId = form.teamId() != null ? form.teamId()
                : (tenant == null ? null : tenant.getDefaultTeamId());

        String channel = isBlank(form.channel()) ? Invitation.CHANNEL_LINK : form.channel().trim();
        int seats = resolveSeats(form.seats());
        int ttlDays = form.expireDays() != null && form.expireDays() > 0 ? form.expireDays() : tokenTtlDays;
        LocalDateTime expireAt = LocalDateTime.now().plusDays(ttlDays);
        int requireApproval = form.requireApproval() != null ? form.requireApproval() : 0;
        int needLogin = anonymousAccept ? 0 : 1;

        String token = TokenDigest.randomToken();
        Invitation inv = new Invitation();
        inv.setTenantId(tenantId);
        inv.setTeamId(teamId);
        inv.setChannel(channel);
        inv.setTokenHash(TokenDigest.sha256Hex(token));
        inv.setTargetEmail(emptyToNull(form.targetEmail()));
        inv.setAllowDomain(emptyToNull(form.allowDomain()));
        inv.setRoleCode(roleCode);
        inv.setSeats(seats);
        inv.setUsedCount(0);
        inv.setRequireApproval(requireApproval);
        inv.setNeedLogin(needLogin);
        inv.setExpireAt(expireAt);
        inv.setStatus(Invitation.STATUS_SENT);
        inv.setInvitedBy(actor);
        inv.setMessage(emptyToNull(form.message()));
        invitationMapper.insert(inv);

        auditService.record(AuditAction.INVITE_CREATE, AuditAction.RESULT_OK, tenantId, null,
                "INVITATION", String.valueOf(inv.getId()),
                Map.of("invitationId", inv.getId(), "roleCode", roleCode, "channel", channel, "seats", seats));

        InvitationVO vo = toVO(inv);
        return new InvitationCreateResult(vo, token, "/org/join?token=" + token);
    }

    // ==================== 列表 ====================

    /** 本组织全部邀请（按创建时间倒序）；<b>不含</b>令牌哈希。 */
    public List<InvitationVO> list() {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();
        List<Invitation> rows = invitationMapper.selectByTenant(tenantId);
        List<InvitationVO> list = new ArrayList<>(rows.size());
        for (Invitation inv : rows) {
            list.add(toVO(inv));
        }
        return list;
    }

    // ==================== 撤销 ====================

    /**
     * 撤销邀请（{@code member:invite:revoke}）：置 {@code status=REVOKED}，令牌随即失效不可再接受。
     *
     * <p>以 {@code selectByIdAndTenant} 校验归属，防止撤销他组织邀请（横向越权）。已撤销/已接受的邀请
     * 幂等处理：仅 {@code SENT}/{@code PENDING} 状态可撤销，其余视为无需操作。
     */
    @Transactional
    public void revoke(Long id) {
        AccessContext ctx = AccessContextHolder.require();
        Long tenantId = ctx.requireTenantId();

        Invitation inv = invitationMapper.selectByIdAndTenant(id, tenantId);
        if (inv == null) {
            throw new BizException(ErrorCode.INVITE_INVALID, "邀请不存在或无权操作: " + id);
        }
        if (!Invitation.STATUS_SENT.equals(inv.getStatus()) && !Invitation.STATUS_PENDING.equals(inv.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅已发出或待审批的邀请可撤销，当前状态: " + inv.getStatus());
        }

        Invitation update = new Invitation();
        update.setId(inv.getId());
        update.setStatus(Invitation.STATUS_REVOKED);
        invitationMapper.updateById(update);

        auditService.record(AuditAction.INVITE_REVOKE, AuditAction.RESULT_OK, tenantId, null,
                "INVITATION", String.valueOf(inv.getId()),
                Map.of("invitationId", inv.getId(), "roleCode", nz(inv.getRoleCode())));
    }

    // ==================== 预览（匿名落地页） ====================

    /**
     * 匿名预览一个邀请（「加入组织」落地页消费）：只读、不写库、不审计、不抛异常。
     *
     * <p>凭明文令牌算 {@code SHA-256} 后全局寻址（{@link InvitationMapper#selectByTokenHash}——
     * {@code invitation} 在 IGNORE 名单，此查询<b>有意不带</b> {@code tenant_id}，早于租户上下文建立）。
     * 令牌无效时一律返回 {@code INVALID} 空预览，绝不泄露组织是否存在（防枚举探测）。
     *
     * <p>可用性状态判定优先级：撤销 &gt; 过期 &gt; 席位耗尽 &gt; 可接受态（SENT/PENDING）；其余终态
     * （如 ACCEPTED）归为 {@code INVALID}。本接口只读投影概要供落地页展示，接受动作由已登录用户经
     * {@link #accept} 完成。
     */
    public InvitationPreviewVO preview(String token) {
        if (isBlank(token)) {
            return InvitationPreviewVO.invalid();
        }
        Invitation inv = invitationMapper.selectByTokenHash(TokenDigest.sha256Hex(token.trim()));
        if (inv == null) {
            return InvitationPreviewVO.invalid();
        }
        String status;
        if (Invitation.STATUS_REVOKED.equals(inv.getStatus())) {
            status = InvitationPreviewVO.STATUS_REVOKED;
        } else if (inv.isExpired()) {
            status = InvitationPreviewVO.STATUS_EXPIRED;
        } else if (!inv.hasCapacity()) {
            status = InvitationPreviewVO.STATUS_EXHAUSTED;
        } else if (Invitation.STATUS_SENT.equals(inv.getStatus()) || Invitation.STATUS_PENDING.equals(inv.getStatus())) {
            status = InvitationPreviewVO.STATUS_OK;
        } else {
            // ACCEPTED / EXPIRED / 其它终态：不可再用，但不泄露细节
            return InvitationPreviewVO.invalid();
        }
        Tenant tenant = tenantMapper.selectById(inv.getTenantId());
        String tenantName = tenant == null ? null : tenant.getName();
        return new InvitationPreviewVO(status, tenantName, inv.getRoleCode(), inv.getChannel(),
                inv.getMessage(), inv.getRequireApproval(), inv.getNeedLogin(), inv.getExpireAt());
    }

    // ==================== 接受（被邀请者加入组织） ====================

    /**
     * 接受邀请（被邀请者登录后调用）：凭明文令牌消费邀请，加入目标组织。
     *
     * <p>流程：校验令牌/撤销/过期/席位 → 校验定向邮箱与邮箱域限制 → 按方案 A 在目标组织（邀请所属租户）
     * 解析或新建 {@code app_user} 身份（同一自然人跨组织 = 不同 {@code app_user} 行，经 {@code identityId} 归并）
     * → 若已持有约定角色则幂等成功、不重复消费席位 → 否则原子占用邀请席位与租户席位（锁序先 invitation 后 tenant，
     * 租户席位不足时回滚邀请席位）→ 经 {@link MemberService} 授予组织/团队角色 → 置
     * {@code ACCEPTED}/{@code PENDING}（{@code require_approval=1} 时）→ 审计 {@code INVITE_ACCEPT}。
     */
    /**
     * 接受邀请（已登录用户）：凭明文令牌消费邀请，加入目标组织。
     *
     * <p>本方法仅负责从请求线程的登录上下文解析操作人（actor），真正校验令牌/席位与建成员身份的逻辑
     * 统一在 {@link #acceptAs}，以便「注册即加入组织」链路在匿名注册事务内复用。
     */
    @Transactional
    public InvitationAcceptResult accept(String token) {
        AccessContext ctx = AccessContextHolder.require();
        Long actorUid = ctx.requireUid();
        User actor = userMapper.selectById(actorUid);
        if (actor == null || actor.getStatus() == null || actor.getStatus() != 1 || actor.getTenantId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请先以有效账号登录后再接受邀请");
        }
        if (isBlank(token)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "邀请令牌不能为空");
        }
        return acceptAs(actor, token);
    }

    /**
     * 接受邀请的核心逻辑（已登录用户与「注册即加入」共用）：校验令牌/撤销/过期/席位 → 定向邮箱限制 →
     * 在目标组织解析或新建成员身份 → 占用席位 → 授予组织/团队角色 → 置 ACCEPTED/PENDING → 审计。
     *
     * <p>与 {@link #accept} 的区别：actor 由调用方显式传入，不依赖请求线程的登录上下文，
     * 故可在匿名注册事务内复用（注册链路先建账号、再以其身份调用本方法完成加入）。
     */
    @Transactional
    public InvitationAcceptResult acceptAs(User actor, String token) {
        Invitation inv = invitationMapper.selectByTokenHash(TokenDigest.sha256Hex(token.trim()));
        if (inv == null) {
            throw new BizException(ErrorCode.INVITE_INVALID, "邀请不存在或已被撤销");
        }
        if (Invitation.STATUS_REVOKED.equals(inv.getStatus())) {
            throw new BizException(ErrorCode.INVITE_INVALID, "邀请已被撤销");
        }
        if (inv.isExpired()) {
            throw new BizException(ErrorCode.INVITE_EXPIRED, "邀请已过期");
        }
        if (!inv.hasCapacity()) {
            throw new BizException(ErrorCode.INVITE_EXHAUSTED, "邀请席位已用尽");
        }

        // 定向邮箱 / 邮箱域限制（identity 可能为空：注册即加入的新用户尚未回填 user_identity，
        // 此时回退到 actor 自身邮箱，避免把"已通过 resolveForRegister 校验的邮箱"误判为不符）
        UserIdentity identity = resolveAcceptIdentity(actor);
        String actorEmail = identity != null ? identity.getEmail() : actor.getEmail();
        if (!isBlank(inv.getTargetEmail())) {
            if (isBlank(actorEmail) || !actorEmail.equalsIgnoreCase(inv.getTargetEmail())) {
                throw new BizException(ErrorCode.INVITE_INVALID, "该邀请仅限指定邮箱接受");
            }
        }
        if (!isBlank(inv.getAllowDomain())) {
            if (isBlank(actorEmail) || !actorEmail.toLowerCase().endsWith("@" + inv.getAllowDomain().toLowerCase())) {
                throw new BizException(ErrorCode.INVITE_INVALID, "该邀请仅限指定邮箱域接受");
            }
        }

        Long targetTenant = inv.getTenantId();
        User member = resolveOrCreateMember(actor, identity, targetTenant, inv.getTeamId());

        // 已持有约定组织角色 → 幂等成功，不重复消费席位
        long held = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getTenantId, targetTenant)
                .eq(UserRole::getUid, member.getId())
                .eq(UserRole::getRoleCode, inv.getRoleCode())
                .eq(UserRole::getScopeType, UserRole.SCOPE_TENANT)
                .eq(UserRole::getStatus, 1));
        if (held > 0) {
            return new InvitationAcceptResult(targetTenant, tenantName(targetTenant), inv.getRoleCode(),
                    inv.getStatus(), "你已经是该组织的成员");
        }

        // 原子占用席位（锁序：先 invitation 后 tenant）
        int ic = invitationMapper.consumeSeat(inv.getId(), targetTenant);
        if (ic == 0) {
            throw new BizException(ErrorCode.INVITE_EXHAUSTED, "邀请席位已用尽");
        }
        int tc = tenantMapper.consumeSeat(targetTenant);
        if (tc == 0) {
            invitationMapper.releaseSeat(inv.getId(), targetTenant); // 租户席位不足，回滚邀请席位
            throw new BizException(ErrorCode.SEAT_LIMIT_EXCEEDED, "组织席位已满");
        }

        // 授予组织/团队角色
        memberService.grantTenantRole(targetTenant, member.getId(), inv.getRoleCode(), inv.getCustomRoleId(), actor.getId());
        if (!isBlank(inv.getTeamRole())) {
            memberService.grantTeamRole(targetTenant, member.getId(), inv.getTeamRole(), inv.getTeamId(), actor.getId());
        }
        // 新成员加入属组织级变更：失效组织版本缓存
        tenantMapper.bumpPermVersion(targetTenant);
        permVersionService.evictTenant(targetTenant);

        // 邀请状态：需审批则进 PENDING，否则直接 ACCEPTED
        String finalStatus = (inv.getRequireApproval() != null && inv.getRequireApproval() == 1)
                ? Invitation.STATUS_PENDING : Invitation.STATUS_ACCEPTED;
        Invitation upd = new Invitation();
        upd.setId(inv.getId());
        upd.setStatus(finalStatus);
        invitationMapper.updateById(upd);

        auditService.record(AuditAction.INVITE_ACCEPT, AuditAction.RESULT_OK, targetTenant, member.getId(),
                "INVITATION", String.valueOf(inv.getId()),
                Map.of("invitationId", inv.getId(), "roleCode", nz(inv.getRoleCode()),
                        "uid", member.getId(), "byUid", actor.getId()));
        return new InvitationAcceptResult(targetTenant, tenantName(targetTenant), inv.getRoleCode(),
                finalStatus, "已成功加入组织");
    }

    /**
     * 校验邀请是否可用于「注册即加入」：令牌有效、未撤销/过期/席位充足，且注册邮箱满足定向限制。
     * 返回邀请实体（含目标租户），供注册链路在目标组织内建账号。失败时抛 {@link BizException}。
     */
    public Invitation resolveForRegister(String token, String email) {
        if (isBlank(token)) {
            throw new BizException(ErrorCode.INVITE_INVALID, "邀请令牌不能为空");
        }
        Invitation inv = invitationMapper.selectByTokenHash(TokenDigest.sha256Hex(token.trim()));
        if (inv == null) {
            throw new BizException(ErrorCode.INVITE_INVALID, "邀请不存在或已被撤销");
        }
        if (Invitation.STATUS_REVOKED.equals(inv.getStatus())) {
            throw new BizException(ErrorCode.INVITE_INVALID, "邀请已被撤销");
        }
        if (inv.isExpired()) {
            throw new BizException(ErrorCode.INVITE_EXPIRED, "邀请已过期");
        }
        if (!inv.hasCapacity()) {
            throw new BizException(ErrorCode.INVITE_EXHAUSTED, "邀请席位已用尽");
        }
        if (!isBlank(inv.getTargetEmail())) {
            if (isBlank(email) || !email.equalsIgnoreCase(inv.getTargetEmail())) {
                throw new BizException(ErrorCode.INVITE_INVALID, "该邀请仅限指定邮箱注册接受");
            }
        }
        if (!isBlank(inv.getAllowDomain())) {
            if (isBlank(email) || !email.toLowerCase().endsWith("@" + inv.getAllowDomain().toLowerCase())) {
                throw new BizException(ErrorCode.INVITE_INVALID, "该邀请仅限指定邮箱域注册接受");
            }
        }
        return inv;
    }

    /** 解析接受者的自然人身份：优先 {@code app_user.identity_id}，否则按邮箱反查。 */
    private UserIdentity resolveAcceptIdentity(User actor) {
        if (actor.getIdentityId() != null) {
            return userIdentityMapper.selectById(actor.getIdentityId());
        }
        if (!isBlank(actor.getEmail())) {
            return userIdentityMapper.selectByEmail(actor.getEmail());
        }
        return null;
    }

    /**
     * 解析或新建目标组织的成员身份（方案 A：同一自然人跨组织 = 不同 {@code app_user} 行）。
     *
     * <p>已存在则复用（{@code status!=1} 时重新激活）；不存在则以自然人身份（优先邮箱作用户名）新建一行。
     */
    private User resolveOrCreateMember(User actor, UserIdentity identity, Long targetTenant, Long teamId) {
        User existing = null;
        if (actor.getIdentityId() != null) {
            existing = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                    .eq(User::getTenantId, targetTenant)
                    .eq(User::getIdentityId, actor.getIdentityId()));
        } else if (!isBlank(actor.getUsername())) {
            existing = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                    .eq(User::getTenantId, targetTenant)
                    .eq(User::getUsername, actor.getUsername()));
        }
        if (existing != null) {
            if (existing.getStatus() == null || existing.getStatus() != 1) {
                User re = new User();
                re.setId(existing.getId());
                re.setStatus(1);
                userMapper.updateById(re);
            }
            return existing;
        }
        User created = new User();
        created.setTenantId(targetTenant);
        created.setIdentityId(actor.getIdentityId());
        created.setUsername(resolveAcceptUsername(actor, identity, targetTenant));
        created.setNickname(identity != null && !isBlank(identity.getNickname()) ? identity.getNickname()
                : actor.getNickname());
        created.setEmail(identity != null ? identity.getEmail() : actor.getEmail());
        // 同一自然人在各组织的成员行共享登录口令：复用 actor 的 BCrypt 哈希，
        // 否则 app_user.password_hash（NOT NULL）缺失会插入失败，且新组织身份将无法用现有口令登录。
        created.setPasswordHash(actor.getPasswordHash());
        created.setRole("USER");
        created.setStatus(1);
        created.setTokenVersion(0L);
        created.setCurrentTeamId(teamId);
        userMapper.insert(created);
        return created;
    }

    /** 接受者在新组织的用户名：优先自然人邮箱（天然唯一倾向），否则派生唯一串。 */
    private String resolveAcceptUsername(User actor, UserIdentity identity, Long targetTenant) {
        if (identity != null && !isBlank(identity.getEmail())) {
            return identity.getEmail();
        }
        if (!isBlank(actor.getUsername())) {
            return actor.getUsername() + "_" + targetTenant;
        }
        return "user_" + targetTenant + "_" + (actor.getIdentityId() != null ? actor.getIdentityId() : actor.getId());
    }

    private String tenantName(Long tenantId) {
        Tenant t = tenantMapper.selectById(tenantId);
        return t == null ? null : t.getName();
    }

    // ==================== 内部工具 ====================

    private InvitationVO toVO(Invitation inv) {
        return new InvitationVO(inv.getId(), inv.getChannel(), inv.getRoleCode(), inv.getTeamId(),
                inv.getTargetEmail(), inv.getAllowDomain(), inv.getSeats(), inv.getUsedCount(),
                inv.getRequireApproval(), inv.getNeedLogin(), inv.getStatus(), inv.getExpireAt(),
                inv.getInvitedBy(), inv.getMessage(), inv.getCreatedAt());
    }

    /** 席位数解析：空→1；{@code -1}（不限）→封顶为 {@code maxSeatsPerLink}；正数→不超过上限。 */
    private int resolveSeats(Integer seats) {
        if (seats == null) {
            return 1;
        }
        if (seats < 0) {
            return maxSeatsPerLink;
        }
        return Math.min(seats, maxSeatsPerLink);
    }

    /** 越级护栏：不可邀请比自身最高组织角色更宽的角色（{@link OrgRole#ALL} 索引越小权限越宽）。 */
    private void assertNoEscalation(AccessContext ctx, String roleCode) {
        int actorIdx = highestRoleIndex(ctx.orgRoles());
        int targetIdx = roleIndex(roleCode);
        if (actorIdx >= 0 && targetIdx >= 0 && targetIdx < actorIdx) {
            throw new BizException(ErrorCode.ROLE_GRANT_ESCALATION, "不可邀请超出自身权限的角色");
        }
    }

    private int highestRoleIndex(java.util.Set<String> roles) {
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

    private boolean isNonGrantable(String roleCode) {
        for (String r : OrgRole.NON_GRANTABLE) {
            if (r.equals(roleCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String emptyToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
