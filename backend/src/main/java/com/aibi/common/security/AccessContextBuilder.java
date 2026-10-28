package com.aibi.common.security;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.DataScope;
import com.aibi.common.tenant.PlanType;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.org.entity.Team;
import com.aibi.module.org.entity.TeamMember;
import com.aibi.module.org.mapper.TeamMapper;
import com.aibi.module.org.mapper.TeamMemberMapper;
import com.aibi.module.org.service.TeamTree;
import com.aibi.module.rbac.entity.Role;
import com.aibi.module.rbac.entity.UserRole;
import com.aibi.module.rbac.mapper.RoleMapper;
import com.aibi.module.rbac.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 访问上下文构建器：把"库里的身份/角色/团队事实"解析成一次请求可用的 {@link AccessContext}，
 * 见《角色权限体系与组织协同设计》2.4 / 3.6（合并算法）/ 12（落地映射）。
 *
 * <p>两条构建路径，成本与用途完全不同，<b>不要混用</b>：
 * <ul>
 *   <li>{@link #buildForLogin}：登录期全量解析，查角色授予、团队成员、团队树（3~4 次索引查询），
 *       并生成新的 {@code sid}。一次登录只跑一次，成本可忽略。</li>
 *   <li>{@link #buildForRequest}：请求期按 JWT 载荷重建，角色直接取 claim、数据范围由
 *       <b>内存角色字典快照</b>推导，<b>零数据库查询</b>。团队成员关系不重查——
 *       它的变更由 {@code tenant.perm_version} 递增 + 会话吊销来即时生效，而不是每请求回源。</li>
 * </ul>
 *
 * <p><b>角色字典快照</b>：{@code role} 是全局字典（约 13 行，只在版本升级时变），
 * 用 volatile 快照 + 定时失效（{@value #ROLE_SNAPSHOT_TTL_MILLIS} ms）承载，
 * 避免每个请求都查一次库；字典变更后最迟一个 TTL 生效，后台改角色时可显式调 {@link #refreshRoles()} 立即生效。
 */
@Component
public class AccessContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(AccessContextBuilder.class);

    /** 角色字典快照有效期（毫秒）：字典极少变更，5 分钟足以在"零查询"与"及时生效"间取得平衡。 */
    private static final long ROLE_SNAPSHOT_TTL_MILLIS = 5 * 60 * 1000L;

    private final UserRoleMapper userRoleMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final RoleMapper roleMapper;

    /** 角色字典快照（不可变），volatile 保证发布可见性；{@code at} 为加载时刻。 */
    private volatile RoleSnapshot roleSnapshot = new RoleSnapshot(Map.of(), 0L);

    public AccessContextBuilder(UserRoleMapper userRoleMapper,
                                TeamMapper teamMapper,
                                TeamMemberMapper teamMemberMapper,
                                RoleMapper roleMapper) {
        this.userRoleMapper = userRoleMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.roleMapper = roleMapper;
    }

    /** 角色字典快照载体。 */
    private record RoleSnapshot(Map<String, Role> byCode, long loadedAt) {

        boolean expired(long now) {
            return byCode.isEmpty() || now - loadedAt > ROLE_SNAPSHOT_TTL_MILLIS;
        }
    }

    // ==================== 登录期：全量解析 ====================

    /**
     * 登录期构建完整上下文（含团队成员关系与新 {@code sid}）。
     *
     * <p><b>{@code isolationMode} 一律由套餐推导，不读 {@code tenant.isolation_mode}</b>：
     * 请求期的 {@link #buildForRequest} 只能拿到 JWT 载荷（里面只有 {@code plan}，没有隔离模式），
     * 若登录期改读数据库列，就会出现"登录响应说 SCHEMA、后续请求说 DATABASE"的不一致。
     * 真要支持逐租户定制隔离模式（{@code TenantAdminService} 已可写入该列），
     * 必须同时把它加进 JWT claim 并升级 {@code TokenPrincipal}，不能只改这一侧。
     * 当前无任何代码消费该值，故按统一口径处理。
     *
     * @param user            已通过口令校验的 {@code app_user} 行
     * @param tenant          其所属组织；平台身份（{@code tenant_id=0}）传 {@code null}
     * @param requestedTeamId 前端指定的团队（多团队用户选择进入哪个），可空表示按默认规则挑
     * @return 可直接用于签发 JWT 与登记 {@code login_session} 的上下文
     */
    public AccessContext buildForLogin(User user, Tenant tenant, Long requestedTeamId) {
        Long uid = user.getId();
        Long tenantId = user.getTenantId();
        boolean platform = tenantId != null && tenantId == 0L;
        PlanType plan = resolvePlan(tenant, user, platform);

        Set<String> platformRoles = platform
                ? resolvePlatformRoles(uid, user.getRole())
                : Set.of();
        // 3.6①：平台角色与组织角色互斥——租户身份即使库里存在平台角色行也强制置空，防跨域提权
        Set<String> orgRoles = platform
                ? Set.of()
                : resolveOrgRoles(tenantId, uid, user.getRole());

        List<UserRole> grants = platform ? List.of() : safeGrants(tenantId, uid);
        TeamTree tree = platform ? TeamTree.empty() : loadTeamTree(tenantId);
        List<TeamMember> memberships = platform ? List.of() : safeMemberships(tenantId, uid);
        Set<Long> teamIds = membershipTeamIds(memberships);
        // 收集用户持有的自定义角色 id（来自 user_role.custom_role_id），请求期从 JWT claim 取，零 DB
        Set<Long> customRoleIds = platform ? Set.of() : collectCustomRoleIds(grants);

        Long currentTeamId = pickCurrentTeam(requestedTeamId, user.getCurrentTeamId(),
                tenant == null ? null : tenant.getDefaultTeamId(), teamIds, tree);
        Set<String> teamRoles = platform ? Set.of() : resolveTeamRoles(grants, memberships, tree, currentTeamId);
        Set<Long> childTeamIds = currentTeamId == null ? Set.of() : tree.descendantIds(currentTeamId);

        DataScope dataScope = resolveDataScope(platform, platformRoles, orgRoles, teamRoles);
        long permVersion = tenant == null ? 0L : tenant.permVersionOrZero();

        return AccessContext.builder()
                .uid(uid)
                .identityId(user.getIdentityId())
                .username(user.getUsername())
                .tenantId(tenantId)
                .plan(plan)
                .isolationMode(plan.defaultIsolationMode())
                .platformRoles(platformRoles)
                .orgRoles(orgRoles)
                .teamRoles(teamRoles)
                .currentTeamId(currentTeamId)
                .teamIds(teamIds)
                .childTeamIds(childTeamIds)
                .dataScope(dataScope)
                .permVersion(permVersion)
                .tokenVersion(user.tokenVersionOrZero())
                .sid(TokenDigest.newSessionId())
                .tokenScope(TokenScope.NORMAL)
                .customRoleIds(customRoleIds)
                .build();
    }

    // ==================== 请求期：按 JWT 载荷重建（零查询） ====================

    /**
     * 请求期重建上下文：角色取 claim，数据范围由角色字典快照推导，<b>不查数据库</b>。
     *
     * <p>{@code teamIds}/{@code childTeamIds} 留空：P0 阶段没有消费方（数据范围拦截器与团队接口在 P1/P2 落地），
     * 而每请求回源查团队成员关系在 4 核 4G 的部署规格下是实打实的吞吐损失。
     * 需要团队维度的接口应显式调 {@link #withTeams(AccessContext)} 按需展开（届时叠加缓存）。
     *
     * <p>{@code permVersion} 拆回两个分量：claim 里只有二者之和 {@code pv}，无法还原各自取值，
     * 故全部记在 {@code permVersion} 上、{@code tokenVersion} 置 0——{@link AccessContext#sessionVersion()}
     * 的结果与签发时一致，校验语义不变。
     *
     * <p><b>旧格式令牌的回退</b>：升级前签发的令牌没有 {@code prole}/{@code trole}，只能按兼容 claim
     * {@code role} 还原角色，使已登录的老用户在令牌自然过期前无需被强制重登（11.1 不停机原则）。
     * 判据必须是 {@link TokenPrincipal#isLegacyFormat()}（即 {@code tv < 2}）而<b>不是"角色集为空"</b>：
     * 新格式令牌下"真的没有任何角色"同样是空集，两者一旦混淆就会把无角色用户回退成 OWNER（静默提权）。
     */
    public AccessContext buildForRequest(TokenPrincipal principal) {
        boolean platform = principal.isPlatform();
        Set<String> platformRoles = principal.platformRoles();
        Set<String> orgRoles = principal.orgRoles();
        if (principal.isLegacyFormat()) {
            if (platform) {
                platformRoles = UserContext.ROLE_SUPER_ADMIN.equals(principal.legacyRole())
                        ? Set.of(PlatformRole.SUPER_ADMIN)
                        : Set.of();
            } else {
                // 与 resolveOrgRoles 的存量回退口径一致：普通租户用户即本组织 OWNER（当时组织只有注册者一人）
                orgRoles = Set.of(PlatformRole.isPlatform(principal.legacyRole()) ? OrgRole.VIEWER : OrgRole.OWNER);
            }
            log.debug("旧格式令牌按 role={} 回退解析角色: uid={}, tid={}",
                    principal.legacyRole(), principal.uid(), principal.tenantId());
        }
        DataScope dataScope = resolveDataScope(platform, platformRoles, orgRoles, Set.of());
        return AccessContext.builder()
                .uid(principal.uid())
                .identityId(principal.identityId())
                .username(principal.username())
                .tenantId(principal.tenantId())
                .plan(principal.plan())
                .isolationMode(principal.plan().defaultIsolationMode())
                .platformRoles(platformRoles)
                .orgRoles(orgRoles)
                .teamRoles(Set.of())
                .currentTeamId(principal.currentTeamId())
                .teamIds(Set.of())
                .childTeamIds(Set.of())
                .dataScope(dataScope)
                .permVersion(principal.permVersion())
                .tokenVersion(0L)
                .sid(principal.sid())
                .tokenScope(principal.tokenScope())
                .customRoleIds(principal.customRoleIds())
                .build();
    }

    /**
     * 按需展开团队维度（团队成员关系 + 子团队集合 + 团队角色），返回带团队信息的新上下文。
     *
     * <p>供团队级接口与数据范围拦截器使用；原上下文不变（record 语义），调用方需
     * {@code AccessContextHolder.set(返回值)} 才对本线程后续逻辑生效。
     */
    public AccessContext withTeams(AccessContext context) {
        if (context == null || context.tenantId() == null || context.tenantId() == 0L) {
            return context;
        }
        Long tenantId = context.tenantId();
        Long uid = context.uid();
        TeamTree tree = loadTeamTree(tenantId);
        List<TeamMember> memberships = safeMemberships(tenantId, uid);
        Set<Long> teamIds = membershipTeamIds(memberships);
        Long currentTeamId = pickCurrentTeam(context.currentTeamId(), null, null, teamIds, tree);
        Set<String> teamRoles = resolveTeamRoles(safeGrants(tenantId, uid), memberships, tree, currentTeamId);
        Set<Long> childTeamIds = currentTeamId == null ? Set.of() : tree.descendantIds(currentTeamId);
        Set<String> mergedTeamRoles = new LinkedHashSet<>(context.teamRoles());
        mergedTeamRoles.addAll(teamRoles);
        DataScope dataScope = DataScope.widest(context.dataScope(),
                widestDataScope(mergedTeamRoles));
        return context.toBuilder()
                .currentTeamId(currentTeamId)
                .teamIds(teamIds)
                .childTeamIds(childTeamIds)
                .teamRoles(mergedTeamRoles)
                .dataScope(dataScope)
                .build();
    }

    /**
     * 切换当前团队：重算团队角色、子团队集合与数据范围（不能只改 {@code currentTeamId}）。
     *
     * <p>目标团队必须是本人<b>在册</b>团队之一，否则抛 40306——直接信任前端传来的 teamId
     * 等于把"团队级数据范围"的开关交给客户端，只需改一个请求参数就能看到别的团队的数据。
     *
     * <p>团队角色取<b>重算后的集合而非并集</b>：团队角色是相对当前团队而言的，
     * 从 A 团队切到 B 团队后，A 团队的 {@code TEAM_OWNER} 必须失效，否则切团队就变成"只增不减"的提权。
     */
    public AccessContext switchTeam(AccessContext context, Long newTeamId) {
        Long tenantId = context.tenantId();
        if (tenantId == null || tenantId == 0L) {
            // 平台域身份无团队维度，切换无意义；不报错以免前端切换器在超管账号上弹错误
            return context;
        }
        Long uid = context.uid();
        TeamTree tree = loadTeamTree(tenantId);
        List<TeamMember> memberships = safeMemberships(tenantId, uid);
        Set<Long> teamIds = membershipTeamIds(memberships);
        if (newTeamId == null || !teamIds.contains(newTeamId) || !tree.contains(newTeamId)) {
            throw new BizException(ErrorCode.TEAM_MISSING, "不是该团队的在册成员，无法切换到该团队");
        }
        Set<String> teamRoles = resolveTeamRoles(safeGrants(tenantId, uid), memberships, tree, newTeamId);
        DataScope scope = DataScope.widest(widestDataScope(context.orgRoles()), widestDataScope(teamRoles));
        return context.toBuilder()
                .currentTeamId(newTeamId)
                .teamIds(teamIds)
                .childTeamIds(tree.descendantIds(newTeamId))
                .teamRoles(teamRoles)
                // 角色字典未加载完成时保持原有范围，避免因切团队反而把用户的数据可见面清空
                .dataScope(scope == null ? context.dataScope() : scope)
                .build();
    }

    // ==================== 团队树 ====================

    /** 载入组织的团队树快照（1 次查询；平台域与空租户返回空树）。 */
    public TeamTree loadTeamTree(Long tenantId) {
        if (tenantId == null || tenantId == 0L) {
            return TeamTree.empty();
        }
        List<Team> teams = teamMapper.selectAllByTenant(tenantId);
        return TeamTree.of(teams);
    }

    // ==================== 角色字典与数据范围 ====================

    /**
     * 多角色取最宽数据范围（3.6⑦）：兼任 ADMIN 与 TEAM_VIEWER 的人应按 ADMIN 看全域，
     * 否则会出现"给了更高角色反而看到更少数据"的反直觉结果。
     *
     * @return 角色码全部不在字典中时返回 {@code null}，由调用方按主体类型兜底
     */
    public DataScope widestDataScope(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return null;
        }
        Map<String, Role> snapshot = roles();
        DataScope widest = null;
        for (String code : roleCodes) {
            Role role = snapshot.get(code);
            if (role == null) {
                continue;
            }
            widest = DataScope.widest(widest, DataScope.parse(role.getDataScope()));
        }
        return widest;
    }

    /** 角色字典快照（{@code code → Role}），过期自动重载；永不为 {@code null}。 */
    public Map<String, Role> roles() {
        RoleSnapshot snapshot = roleSnapshot;
        long now = System.currentTimeMillis();
        if (!snapshot.expired(now)) {
            return snapshot.byCode();
        }
        synchronized (this) {
            // 双检：并发下只让一个线程回源，其余直接用刚发布的快照
            if (!roleSnapshot.expired(now)) {
                return roleSnapshot.byCode();
            }
            Map<String, Role> loaded = loadRoles();
            roleSnapshot = new RoleSnapshot(loaded, System.currentTimeMillis());
            if (loaded.isEmpty()) {
                // 字典未播种（首次启动 DataSeeder 尚未跑完，或播种失败）：此时所有数据范围都会走兜底值
                log.warn("角色字典为空：数据范围将退化为兜底值，请检查 role 表播种是否成功");
            }
            return loaded;
        }
    }

    /** 强制重载角色字典（后台修改角色定义后调用，免去等一个 TTL）。 */
    public void refreshRoles() {
        synchronized (this) {
            roleSnapshot = new RoleSnapshot(loadRoles(), System.currentTimeMillis());
        }
    }

    private Map<String, Role> loadRoles() {
        try {
            List<Role> all = roleMapper.selectList(null);
            Map<String, Role> byCode = new HashMap<>(all.size() * 2);
            for (Role role : all) {
                if (role != null && role.getCode() != null && role.isEnabled()) {
                    byCode.put(role.getCode(), role);
                }
            }
            return Map.copyOf(byCode);
        } catch (Exception e) {
            // 字典表缺失（迁移未跑）不应让整个应用起不来：返回空快照并告警，判定走兜底分支
            log.warn("载入角色字典失败，按空字典处理: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== 角色解析（含存量兼容回退） ====================

    /**
     * 平台角色解析：仅 {@code tenant_id=0} 的身份可有。
     *
     * <p>回退规则：库里没有 {@code user_role} 行时，按 {@code app_user.role} 还原
     * （{@code SUPER_ADMIN → SUPER_ADMIN}），保证升级后超管不会丢掉后台入口。
     */
    private Set<String> resolvePlatformRoles(Long uid, String legacyRole) {
        List<UserRole> grants = safeGrants(0L, uid);
        Set<String> codes = new LinkedHashSet<>();
        for (UserRole grant : grants) {
            if (!grant.isTeamScoped() && PlatformRole.isPlatform(grant.getRoleCode())) {
                codes.add(grant.getRoleCode());
            }
        }
        if (codes.isEmpty() && UserContext.ROLE_SUPER_ADMIN.equals(legacyRole)) {
            codes.add(PlatformRole.SUPER_ADMIN);
        }
        return codes.isEmpty() ? Set.of() : Set.copyOf(codes);
    }

    /**
     * 组织角色解析。
     *
     * <p><b>回退条件是"整个组织尚未回填"而非"该用户没有角色行"</b>，这一点很关键：
     * 若按用户判空回退，则升级后管理员新建的第二个成员（{@code app_user.role='USER'}，
     * 尚无 {@code user_role} 行）会被回退成 {@code OWNER}，形成静默提权。
     * 按组织判空则只在"存量库整体未回填"时生效，回填完成后永久走精确解析。
     */
    private Set<String> resolveOrgRoles(Long tenantId, Long uid, String legacyRole) {
        List<UserRole> grants = safeGrants(tenantId, uid);
        Set<String> codes = new LinkedHashSet<>();
        for (UserRole grant : grants) {
            if (!grant.isTeamScoped() && OrgRole.isOrg(grant.getRoleCode())) {
                codes.add(grant.getRoleCode());
            }
        }
        if (codes.isEmpty() && !hasAnyTenantGrant(tenantId)) {
            // 存量库整体未回填：按升级前的语义还原——普通租户用户即本组织的 OWNER（当时组织只有注册者一人）
            codes.add(PlatformRole.isPlatform(legacyRole) ? OrgRole.VIEWER : OrgRole.OWNER);
            log.warn("组织 {} 尚无 user_role 记录，按 app_user.role 回退解析组织角色（请尽快完成 V2026_09 回填）", tenantId);
        }
        return codes.isEmpty() ? Set.of() : Set.copyOf(codes);
    }

    /** 该组织是否已有任何角色授予记录（用于判定"是否整体未回填"）。 */
    private boolean hasAnyTenantGrant(Long tenantId) {
        try {
            Long count = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                    .eq(UserRole::getTenantId, tenantId));
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("查询组织 {} 的角色授予数失败，按已回填处理以免误提权: {}", tenantId, e.getMessage());
            return true;
        }
    }

    /**
     * 团队角色解析：当前团队的团队角色 ∪ 祖先团队中 {@code inherit_to_child=1} 的团队角色。
     *
     * <p>两个来源都要看：{@code team_member.team_role}（入组时的默认团队角色）与
     * {@code user_role(scope_type='TEAM')}（后续单独授予的团队角色），取并集后按字典推导数据范围。
     */
    private Set<String> resolveTeamRoles(List<UserRole> grants, List<TeamMember> memberships,
                                         TeamTree tree, Long currentTeamId) {
        if (currentTeamId == null) {
            return Set.of();
        }
        Set<Long> ancestors = new LinkedHashSet<>(tree.ancestorIds(currentTeamId));
        Map<String, Role> dict = roles();
        Set<String> codes = new LinkedHashSet<>();
        for (TeamMember m : memberships) {
            String code = TeamRole.normalize(m.getTeamRole());
            if (currentTeamId.equals(m.getTeamId())) {
                codes.add(code);
            } else if (ancestors.contains(m.getTeamId()) && inheritsToChild(dict, code)) {
                codes.add(code);
            }
        }
        for (UserRole grant : grants) {
            if (!grant.isTeamScoped() || !TeamRole.isTeam(grant.getRoleCode())) {
                continue;
            }
            String code = grant.getRoleCode();
            Long scopeId = grant.getScopeId();
            if (currentTeamId.equals(scopeId)) {
                codes.add(code);
            } else if (scopeId != null && ancestors.contains(scopeId) && inheritsToChild(dict, code)) {
                codes.add(code);
            }
        }
        return codes.isEmpty() ? Set.of() : Set.copyOf(codes);
    }

    /**
     * 团队角色是否向子团队继承（{@code role.inherit_to_child=1}）。
     *
     * <p>字典缺失（未播种）时返回 {@code false}：宁可让上级团队的负责人在子团队里少一份权限
     * （可通过补字典修复），也不能默认向下扩散——那会让"父团队只读成员"在全部子团队也变成成员，
     * 跨越了团队边界且很难被察觉。
     */
    private boolean inheritsToChild(Map<String, Role> dict, String roleCode) {
        Role role = dict.get(roleCode);
        return role != null && role.inheritsToChild();
    }

    /**
     * 数据范围推导：平台身份按平台角色字典取最宽（缺省 ALL），组织身份按组织+团队角色取最宽（缺省 SELF）。
     *
     * <p>缺省值刻意取<b>最窄</b>：字典未播种或角色码不在字典中时，宁可让用户少看到数据（可通过补字典修复），
     * 也不能默认放行组织全域（那是无法察觉的越权）。平台域例外——超管在字典缺失时若被降为 SELF，
     * 会直接锁死后台，故取 ALL。
     */
    private DataScope resolveDataScope(boolean platform, Set<String> platformRoles,
                                       Set<String> orgRoles, Set<String> teamRoles) {
        Set<String> merged = new LinkedHashSet<>();
        if (platform) {
            merged.addAll(platformRoles);
        } else {
            merged.addAll(orgRoles);
            merged.addAll(teamRoles);
        }
        DataScope fromDict = widestDataScope(merged);
        if (fromDict != null) {
            // 平台角色的 ALL 只在 tenant_id=0 时成立；租户身份即使字典误配了 ALL 也必须收敛到 TENANT
            return !platform && fromDict.isCrossTenant() ? DataScope.TENANT : fromDict;
        }
        return platform ? DataScope.ALL : DataScope.SELF;
    }

    // ==================== 当前团队选择 ====================

    /**
     * 决定本次会话的当前团队，优先级：显式指定 &gt; 用户上次选择 &gt; 组织默认团队 &gt; 在册团队首个。
     *
     * <p>任何一级都要用 {@code tree.contains} 或 {@code teamIds} 校验<b>本人确实在册</b>：
     * {@code app_user.current_team_id} 是持久化的历史值，用户可能已被移出该团队，
     * 直接信任会让人继续以上次团队的范围看数据。
     */
    private Long pickCurrentTeam(Long requested, Long remembered, Long orgDefault,
                                 Set<Long> teamIds, TeamTree tree) {
        if (requested != null && teamIds.contains(requested) && tree.contains(requested)) {
            return requested;
        }
        if (remembered != null && teamIds.contains(remembered) && tree.contains(remembered)) {
            return remembered;
        }
        if (orgDefault != null && teamIds.contains(orgDefault)) {
            return orgDefault;
        }
        if (!teamIds.isEmpty()) {
            // 取最小 id：teamIds 由 LinkedHashSet 保序（来自 ORDER BY team_id），结果稳定可预期
            return teamIds.iterator().next();
        }
        // 在册团队为空但组织有默认团队：存量回填可能只建了团队未建成员关系，此时按组织级身份处理
        return orgDefault != null && tree.contains(orgDefault) ? orgDefault : null;
    }

    private Set<Long> membershipTeamIds(List<TeamMember> memberships) {
        if (memberships.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (TeamMember m : memberships) {
            if (m.getTeamId() != null) {
                ids.add(m.getTeamId());
            }
        }
        return ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    // ==================== 查询兜底 ====================

    /**
     * 角色授予查询：异常时返回空列表而非抛出。
     *
     * <p>理由是<b>故障域隔离</b>：{@code user_role} 表缺失或迁移未跑时，登录仍应可用
     * （走 {@code app_user.role} 回退），而不是整站 500。安全侧由回退规则的保守性保证
     * （见 {@link #resolveOrgRoles} 的"按组织判空"）。
     */
    private List<UserRole> safeGrants(Long tenantId, Long uid) {
        try {
            List<UserRole> grants = userRoleMapper.selectEffective(tenantId, uid);
            return grants == null ? List.of() : grants;
        } catch (Exception e) {
            log.warn("查询角色授予失败（tenant={}, uid={}），按无授予处理: {}", tenantId, uid, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从有效授予里收集自定义角色 id（去重）。
     *
     * <p>仅取 {@code custom_role_id} 非空的授予行：这些行让用户在 base 角色权限之上叠加
     * {@code role_permission_override} 的 DENY/GRANT 收窄，必须在上下文里带上以便请求期零 DB 施加。
     */
    private Set<Long> collectCustomRoleIds(List<UserRole> grants) {
        if (grants == null || grants.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (UserRole g : grants) {
            if (g.getCustomRoleId() != null) {
                ids.add(g.getCustomRoleId());
            }
        }
        return ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    /** 团队成员查询：异常时返回空列表（同 {@link #safeGrants} 的故障域隔离理由）。 */
    private List<TeamMember> safeMemberships(Long tenantId, Long uid) {
        try {
            List<TeamMember> rows = teamMemberMapper.selectActiveByUid(tenantId, uid);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("查询团队成员关系失败（tenant={}, uid={}），按无团队处理: {}", tenantId, uid, e.getMessage());
            return List.of();
        }
    }

    /**
     * 套餐解析：优先取组织行的 {@code plan}，缺失时按身份类型兜底。
     *
     * <p>平台身份（{@code tenant_id=0}）没有对应的 {@code tenant} 行，其套餐无业务含义，
     * 但 {@code TenantContext.planType()} 不能为 null，故取 ENTERPRISE（隔离模式最宽，
     * 且平台域本就走 {@code @InterceptorIgnore} 显式查询，不受套餐影响）。
     */
    private PlanType resolvePlan(Tenant tenant, User user, boolean platform) {
        if (tenant != null && tenant.getPlan() != null && !tenant.getPlan().isBlank()) {
            return PlanType.parse(tenant.getPlan());
        }
        if (platform) {
            return PlanType.ENTERPRISE;
        }
        log.warn("组织行缺失或套餐为空（uid={}, tenant={}），按 FREE 处理", user.getId(), user.getTenantId());
        return PlanType.FREE;
    }
}
