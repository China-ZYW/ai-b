package com.aibi.module.rbac.service;

import com.aibi.common.security.AccessContext;
import com.aibi.common.tenant.DataScope;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.rbac.dto.PermissionsView;
import com.aibi.module.rbac.entity.Permission;
import com.aibi.module.rbac.entity.RolePermissionOverride;
import com.aibi.module.rbac.mapper.PermissionMapper;
import com.aibi.module.rbac.mapper.RolePermissionOverrideMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限解析服务（见《角色权限体系与组织协同设计》3.6 合并算法 / 6.3 四级缓存）。
 *
 * <p><b>不把权限点集合塞进 JWT</b>：令牌只带角色码 + 版本号，权限点在服务端按角色解析并缓存。
 * 解析结果由 {@code PermissionAspect}（L2 门禁）与 {@code /api/auth/permissions}（前端下发）共同消费，
 * 保证"后端判定"与"前端显隐"用的是同一份权限码，杜绝两套权限漂移。
 *
 * <p><b>四级缓存</b>（无 Caffeine 依赖，用 {@code ConcurrentHashMap + volatile 快照 + TTL} 实现，
 * 与 {@code AccessContextBuilder} 的角色字典快照同一套写法）：
 * <ol>
 *   <li>权限点字典 {@code perm:dict}：{@code Map<code,Permission>}，TTL 30min，供菜单码解析；</li>
 *   <li>角色权限 {@code role:{roleCode}}：{@code Set<code>}，TTL 10min（{@code role_permission} 为全局字典，
 *       故键不含 tenantId；租户自定义角色的 override 收敛在 P2 落地）；</li>
 *   <li>用户有效权限 {@code perm:user:{tenantId}:{uid}:{teamId}:{pv}}：TTL 5min。<b>把 {@code pv} 编进键</b>——
 *       组织权限版本一变，键即失配、自动回源重算，无需显式失效，天然满足"变更 ≤5min 收敛、版本跳变即时生效"；</li>
 *   <li>版本号缓存见 {@link PermVersionService}（TTL 30s）。</li>
 * </ol>
 *
 * <p><b>ASSIST 剥离</b>（9.3③ + 红线 2012）：代客协助影子令牌的有效权限集在此处即剔除全部写类动作
 * 与 {@code member:*}/{@code org:*}/{@code datasource:config:test}，使 {@code /api/auth/permissions}
 * 下发的能力集与切面硬拒绝清单一致（纵深防御，字典误配也无法越界）。
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    /** 权限点字典快照 TTL（毫秒）。 */
    private static final long DICT_TTL_MILLIS = 30 * 60 * 1000L;
    /** 角色→权限码快照 TTL（毫秒）。 */
    private static final long ROLE_TTL_MILLIS = 10 * 60 * 1000L;
    /** 用户有效权限快照 TTL（毫秒）。 */
    private static final long USER_TTL_MILLIS = 5 * 60 * 1000L;

    /**
     * ASSIST 影子令牌一律剥离的动作段（9.3③）。
     *
     * <p>只列"改变状态或产生业务副作用"的动作；{@code read} 等只读动作保留，
     * 以支撑支持人员的只读诊断诉求。
     */
    private static final Set<String> ASSIST_STRIP_ACTIONS =
            Set.of("write", "delete", "manage", "execute", "assign", "publish");

    private final PermissionMapper permissionMapper;
    private final TenantMapper tenantMapper;
    private final RolePermissionOverrideMapper overrideMapper;

    /** 权限点字典快照（不可变），volatile 保证发布可见性。 */
    private volatile Snapshot<Map<String, Permission>> dictSnapshot = Snapshot.empty();
    /** 角色码 → 权限码集合快照。 */
    private final ConcurrentHashMap<String, Snapshot<Set<String>>> roleCache = new ConcurrentHashMap<>();
    /** 用户维度键（含 pv）→ 有效权限码集合快照。 */
    private final ConcurrentHashMap<String, Snapshot<Set<String>>> userCache = new ConcurrentHashMap<>();
    /**
     * 自定义角色覆盖缓存（{@code customRoleId → 权限码集合}），TTL 同角色缓存 10min。
     * 与 {@code roleCache} 分离：自定义角色覆盖是租户隔离表，键只需 {@code customRoleId}（全局唯一）。
     */
    private final ConcurrentHashMap<Long, Snapshot<Set<String>>> customGrantCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Snapshot<Set<String>>> customDenyCache = new ConcurrentHashMap<>();

    public PermissionService(PermissionMapper permissionMapper, TenantMapper tenantMapper,
                             RolePermissionOverrideMapper overrideMapper) {
        this.permissionMapper = permissionMapper;
        this.tenantMapper = tenantMapper;
        this.overrideMapper = overrideMapper;
    }

    /** 带加载时刻的不可变快照载体；{@code expired} 按各自 TTL 判定。 */
    private record Snapshot<T>(T value, long loadedAt) {

        static <T> Snapshot<T> empty() {
            return new Snapshot<>(null, 0L);
        }

        boolean expired(long now, long ttl) {
            return value == null || now - loadedAt > ttl;
        }
    }

    // ==================== 权限解析 ====================

    /**
     * 解析上下文的有效权限码集合（3.6④ 并集 + ⑤ 收敛 + ASSIST 剥离）。
     *
     * <p>结果按 {@code tenantId:uid:teamId:pv} 缓存 5min；{@code pv} 编入键使版本跳变自动回源。
     * 匿名/无 uid 上下文返回空集（安全默认：无任何权限）。
     */
    public Set<String> effectivePermissions(AccessContext ctx) {
        if (ctx == null || ctx.uid() == null) {
            return Set.of();
        }
        String key = ctx.tenantId() + ":" + ctx.uid() + ":" + ctx.currentTeamId() + ":" + ctx.sessionVersion();
        long now = System.currentTimeMillis();
        Snapshot<Set<String>> cached = userCache.get(key);
        if (cached != null && !cached.expired(now, USER_TTL_MILLIS)) {
            return cached.value();
        }

        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(ctx.platformRoles());
        roles.addAll(ctx.orgRoles());
        roles.addAll(ctx.teamRoles());

        Set<String> perms = new LinkedHashSet<>();
        for (String role : roles) {
            perms.addAll(rolePermissions(role));
        }
        // 自定义角色覆盖：有效 = base 权限 ∪ GRANT(仅 base 内) − DENY。
        // 先加 GRANT 再移除 DENY，保证 DENY 优先且不可被 base 的授予反转（见设计文档 §2.1）。
        // 注意：user_role.roleCode 存的是 base 码，故上面的 rolePermissions 已把 base 全集加入，
        // 此处只负责按各自定义角色的覆盖把权限收窄/（受限地）放宽。
        for (Long crId : ctx.customRoleIds()) {
            perms.addAll(overrideCodes(crId, RolePermissionOverride.EFFECT_GRANT));
        }
        for (Long crId : ctx.customRoleIds()) {
            if (!perms.isEmpty()) {
                perms.removeAll(overrideCodes(crId, RolePermissionOverride.EFFECT_DENY));
            }
        }
        // 平台超管旁路：SUPER_ADMIN 角色定义为「全权」，但 role_permission 种子只覆盖组织/团队角色——
        // 平台角色链上没有（也不应有）租户侧权限点授权，纯靠字典会把超管锁死在任意租户接口之外
        // （典型表现：GET /api/quota 报「缺少权限点 quota:usage:read」，刷新页面/清缓存均无效，因为是结构性缺口）。
        // 此处取「全部已启用权限点」作为超管有效集：新权限点自动对超管生效，无需随种子同步维护；
        // 且 buildView 复用本方法，前端 can() 显隐与后端切面判定仍是同一份权限码，不会漂移。
        if (ctx.isSuperAdmin()) {
            perms.addAll(dictionary().keySet());
        }
        if (ctx.isAssist()) {
            perms.removeIf(PermissionService::assistDenied);
        }
        Set<String> result = Set.copyOf(perms);
        userCache.put(key, new Snapshot<>(result, now));
        return result;
    }

    /** 是否具备全部指定权限点（AND）。 */
    public boolean hasAll(AccessContext ctx, String... codes) {
        if (codes == null || codes.length == 0) {
            return true;
        }
        Set<String> owned = effectivePermissions(ctx);
        for (String code : codes) {
            if (!owned.contains(code)) {
                return false;
            }
        }
        return true;
    }

    /** 是否具备任一指定权限点（OR）。 */
    public boolean hasAny(AccessContext ctx, String... codes) {
        if (codes == null || codes.length == 0) {
            return true;
        }
        Set<String> owned = effectivePermissions(ctx);
        for (String code : codes) {
            if (owned.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某角色的权限码集合（{@code role_permission} 全局字典，键不含 tenantId）。
     *
     * <p>调用 {@link PermissionMapper#selectCodesByRoleCodes} 时集合必须非空——此处恒为单元素，安全。
     * 结果缓存 10min；字典变更（平台维护权限点）后调 {@link #evictRole(String)} 或 {@link #evictAll()} 立即生效。
     */
    private Set<String> rolePermissions(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        Snapshot<Set<String>> cached = roleCache.get(roleCode);
        if (cached != null && !cached.expired(now, ROLE_TTL_MILLIS)) {
            return cached.value();
        }
        List<String> codes = permissionMapper.selectCodesByRoleCodes(List.of(roleCode));
        Set<String> result = codes == null || codes.isEmpty() ? Set.of() : Set.copyOf(new LinkedHashSet<>(codes));
        roleCache.put(roleCode, new Snapshot<>(result, now));
        return result;
    }

    /**
     * MENU 型权限点对应的前端路由 name 集合（动态菜单数据源）。
     *
     * <p>取 {@code permission.menu_code}（前端路由 name）；字典缺 {@code menu_code} 时回落权限码本身，
     * 保证菜单不因单行配置疏漏而整体丢失。
     */
    public Set<String> menuCodes(AccessContext ctx) {
        Set<String> perms = effectivePermissions(ctx);
        if (perms.isEmpty()) {
            return Set.of();
        }
        Map<String, Permission> dict = dictionary();
        Set<String> menus = new LinkedHashSet<>();
        for (String code : perms) {
            Permission p = dict.get(code);
            if (p != null && Permission.TYPE_MENU.equals(p.getType())) {
                String menuCode = p.getMenuCode();
                menus.add(menuCode == null || menuCode.isBlank() ? code : menuCode);
            }
        }
        return Set.copyOf(menus);
    }

    /** 权限点字典快照（{@code code → Permission}），TTL 30min。 */
    private Map<String, Permission> dictionary() {
        long now = System.currentTimeMillis();
        Snapshot<Map<String, Permission>> snapshot = dictSnapshot;
        if (!snapshot.expired(now, DICT_TTL_MILLIS)) {
            return snapshot.value();
        }
        List<Permission> all = permissionMapper.selectEnabled();
        Map<String, Permission> map = new java.util.HashMap<>();
        if (all != null) {
            for (Permission p : all) {
                if (p.getCode() != null) {
                    map.put(p.getCode(), p);
                }
            }
        }
        Map<String, Permission> result = Map.copyOf(map);
        dictSnapshot = new Snapshot<>(result, now);
        return result;
    }

    // ==================== 前端下发视图 ====================

    /**
     * 组装 {@code GET /api/auth/permissions} 的响应视图。
     *
     * <p>席位余量按 {@code tenant} 行实时读取（不缓存）：席位是邀请接受时的原子占位对象，
     * 展示值允许最终一致，但此处一次登录只查一次，直接读库最简单且不会误导。平台身份无席位概念，相关字段为 {@code null}。
     */
    public PermissionsView buildView(AccessContext ctx) {
        Set<String> perms = effectivePermissions(ctx);
        Set<String> menus = menuCodes(ctx);
        DataScope scope = ctx.dataScope() == null ? DataScope.SELF : ctx.dataScope();

        Integer seatLimit = null;
        Integer seatUsed = null;
        Integer seatRemaining = null;
        if (ctx.isTenantScope()) {
            Tenant tenant = tenantMapper.selectById(ctx.tenantId());
            if (tenant != null) {
                seatLimit = tenant.getSeatLimit();
                seatUsed = tenant.getSeatUsed();
                if (seatLimit != null && seatLimit >= 0) {
                    int used = seatUsed == null ? 0 : seatUsed;
                    seatRemaining = Math.max(0, seatLimit - used);
                }
            }
        }

        return new PermissionsView(
                perms,
                menus,
                scope.name(),
                ctx.platformRoles(),
                ctx.orgRoles(),
                ctx.teamRoles(),
                ctx.currentTeamId(),
                ctx.teamIds(),
                ctx.isPlatform(),
                ctx.isOwner(),
                ctx.isAssist(),
                seatLimit,
                seatUsed,
                seatRemaining);
    }

    // ==================== 缓存失效（供后台改字典/角色时主动调用） ====================

    /** 失效单个角色的权限缓存（自定义角色/override 变更时用；P2 接入）。 */
    public void evictRole(String roleCode) {
        if (roleCode != null) {
            roleCache.remove(roleCode);
        }
    }

    /**
     * 失效某个自定义角色的覆盖缓存（自定义角色增删改 / 覆盖变更时调用）。
     *
     * <p>仅清本角色的 GRANT/DENY 快照；用户维度缓存键含 {@code pv}，组织级授权变更靠
     * {@code perm_version++} 自然失配，无需在此逐一清除。
     */
    public void evictCustomRole(Long customRoleId) {
        if (customRoleId != null) {
            customGrantCache.remove(customRoleId);
            customDenyCache.remove(customRoleId);
        }
    }

    /**
     * 某内置角色的权限码集合（公开版，供自定义角色有效权限预览 / 窄化校验复用，见《组织治理专项设计》§2.3）。
     *
     * <p>即 {@link #effectivePermissions} 对单角色求并集时调用的底层解析，缓存策略一致（TTL 10min）。
     */
    public Set<String> baseRolePermissions(String roleCode) {
        return rolePermissions(roleCode);
    }

    /**
     * 某自定义角色的覆盖权限码集合（按 effect 过滤，公开版，供预览 / 服务层窄化校验复用）。
     */
    public Set<String> customRoleOverrides(Long customRoleId, String effect) {
        return overrideCodes(customRoleId, effect);
    }

    /** 某自定义角色的覆盖权限码集合（按 effect 过滤），结果缓存 10min。 */
    private Set<String> overrideCodes(Long customRoleId, String effect) {
        if (customRoleId == null) {
            return Set.of();
        }
        ConcurrentHashMap<Long, Snapshot<Set<String>>> cache =
                RolePermissionOverride.EFFECT_GRANT.equals(effect) ? customGrantCache : customDenyCache;
        long now = System.currentTimeMillis();
        Snapshot<Set<String>> cached = cache.get(customRoleId);
        if (cached != null && !cached.expired(now, ROLE_TTL_MILLIS)) {
            return cached.value();
        }
        List<String> codes = overrideMapper.selectOverrideCodes(customRoleId, effect);
        Set<String> result = codes == null || codes.isEmpty()
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(codes));
        cache.put(customRoleId, new Snapshot<>(result, now));
        return result;
    }

    /**
     * 全量失效权限相关缓存（平台维护权限字典、批量调整角色授权后调用）。
     *
     * <p>用户维度缓存键含 {@code pv}，正常授权变更靠版本跳变自然失配；此处的全清是"字典本身被改"
     * （权限点增删、{@code role_permission} 重播）时的兜底，确保最迟下一次请求即生效。
     */
    public void evictAll() {
        dictSnapshot = Snapshot.empty();
        roleCache.clear();
        userCache.clear();
        customGrantCache.clear();
        customDenyCache.clear();
        log.info("权限缓存已全量失效（字典/角色授权变更）");
    }

    // ==================== ASSIST 硬拒绝判定 ====================

    /**
     * ASSIST 影子令牌是否应剥离该权限码（红线 2012 + 9.3③）。
     *
     * <p>硬编码而非查字典：即使权限字典被误配（把写权限授给了 SUPPORT 的影子令牌），
     * 这里也拦得住——这是"写入代码硬断言，不依赖流程约束"的落点。
     */
    public static boolean assistDenied(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        // 硬拒绝清单：成员域、组织域一律不可碰；数据源连通性测试不可碰（会触发凭证解密）
        if (code.startsWith("member:") || code.startsWith("org:")) {
            return true;
        }
        if ("datasource:config:test".equals(code)) {
            return true;
        }
        // 动作剥离：全部写类动作（含 datasource:*:write、admin:*:write）
        int idx = code.lastIndexOf(':');
        String action = idx >= 0 ? code.substring(idx + 1) : code;
        return ASSIST_STRIP_ACTIONS.contains(action);
    }
}
