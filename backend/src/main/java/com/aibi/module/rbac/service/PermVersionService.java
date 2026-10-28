package com.aibi.module.rbac.service;

import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限版本号服务（见《角色权限体系与组织协同设计》6.3 四级缓存之"版本号"级）。
 *
 * <p>合并版本号 {@code pv = tenant.perm_version + app_user.token_version}，与 JWT claim {@code pv}
 * 同源。{@code JwtAuthFilter} 每请求比对令牌 {@code pv} 与本服务返回值：令牌值偏小即说明签发后
 * 组织授权或身份敏感信息发生过变更，立即回 401 + {@code 40311}（分级吊销的"版本"路径）。
 *
 * <p><b>30s 缓存是有意的权衡</b>：完全实时校验会让每请求多两次 DB 查询（4 核 4G 单机下 QPS 敏感）。
 * 对"移除成员/停用账号"这类必须秒级生效的场景，另走 {@code login_session.revoked_at} 按 {@code sid}
 * 精确吊销（{@code LoginSessionService}）；两者叠加即"普通变更 30s 内收敛、精确吊销即时生效"。
 *
 * <p><b>故障策略</b>：查询异常时返回令牌侧不可比对的 {@code -1}，由调用方按"跳过 pv 校验"处理，
 * 与 {@code JwtAuthFilter} 会话校验的 fail-open 口径一致——避免一次数据库抖动演变成全站 401。
 */
@Service
public class PermVersionService {

    private static final Logger log = LoggerFactory.getLogger(PermVersionService.class);

    /** 版本号缓存 TTL（毫秒）：短 TTL 容忍最终一致，见类注释的权衡说明。 */
    private static final long VERSION_TTL_MILLIS = 30 * 1000L;

    /** 查询失败时的哨兵值：调用方据此跳过 pv 比对（fail-open）。 */
    public static final long UNKNOWN = -1L;

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;

    private final ConcurrentHashMap<String, Snapshot> cache = new ConcurrentHashMap<>();

    public PermVersionService(TenantMapper tenantMapper, UserMapper userMapper) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
    }

    /** 带加载时刻的版本号快照。 */
    private record Snapshot(long value, long loadedAt) {

        boolean expired(long now) {
            return now - loadedAt > VERSION_TTL_MILLIS;
        }
    }

    /**
     * 当前合并版本号 {@code tenant.perm_version + app_user.token_version}。
     *
     * <p>平台身份（{@code tenantId==0}）无租户行，组织分量记 0；缺参返回 0（不可比对，调用方会跳过）。
     * 查询异常返回 {@link #UNKNOWN}。
     */
    public long current(Long tenantId, Long uid) {
        if (tenantId == null || uid == null) {
            return 0L;
        }
        String key = tenantId + ":" + uid;
        long now = System.currentTimeMillis();
        Snapshot cached = cache.get(key);
        if (cached != null && !cached.expired(now)) {
            return cached.value();
        }
        try {
            long tokenVersion = nullToZero(userMapper.selectTokenVersion(uid));
            long permVersion = tenantId == 0L ? 0L : nullToZero(tenantMapper.selectPermVersion(tenantId));
            long total = permVersion + tokenVersion;
            cache.put(key, new Snapshot(total, now));
            return total;
        } catch (Exception e) {
            log.warn("版本号查询失败，按不可比对处理（跳过 pv 校验）: tenant={}, uid={}, err={}",
                    tenantId, uid, e.getMessage());
            return UNKNOWN;
        }
    }

    /**
     * 失效某身份的版本缓存（敏感变更后调用，使下一次请求立即回源、旧令牌即刻失配）。
     *
     * <p>与 {@code login_session} 吊销配合：后者保证 ≤1s 精确下线，前者保证版本号不再被 30s 旧值遮蔽。
     */
    public void evict(Long tenantId, Long uid) {
        if (tenantId != null && uid != null) {
            cache.remove(tenantId + ":" + uid);
        }
    }

    /** 失效某组织全部成员的组织级版本缓存（组织授权/成员批量变更）。 */
    public void evictTenant(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        String prefix = tenantId + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
