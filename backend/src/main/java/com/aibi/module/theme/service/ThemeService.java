package com.aibi.module.theme.service;

import com.aibi.common.tenant.TenantContext;
import com.aibi.module.theme.entity.ThemeConfig;
import com.aibi.module.theme.mapper.ThemeConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主题服务（配置化升级 V2，样式配置化）：{@code theme_config} 的读取、缓存与管理，
 * 使 DB 成为大屏/问数图表/公开页主题令牌的唯一事实源。
 *
 * <p>解析规则：全局内置预设（tenant_id=NULL）+ 租户自定义（tenant_id=当前租户）合并，同 code 时租户覆盖全局；
 * 按 code 取单个主题时同样"租户优先"。列表/单主题均带进程内缓存（TTL 同 schema 缓存），写操作即失效。
 *
 * <p>{@code theme_config} 在租户拦截器 IGNORE 名单中（tenant_id 可空），本服务显式按租户解析。
 */
@Service
public class ThemeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeService.class);

    private final ThemeConfigMapper mapper;
    private final long cacheTtlSeconds;

    private final Map<String, CachedList> cache = new ConcurrentHashMap<>();

    public ThemeService(ThemeConfigMapper mapper,
                        @Value("${aibi.schema.cache-ttl-seconds:1800}") long cacheTtlSeconds) {
        this.mapper = mapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    /** 当前租户可见的主题列表（全局预设 + 租户自定义；同 code 租户覆盖全局）。 */
    public List<ThemeConfig> list() {
        Long tenantId = TenantContext.tenantId();
        return listForTenant(tenantId);
    }

    /** 公开列表（免认证分享页用）：仅全局内置预设，不含租户私有主题。 */
    public List<ThemeConfig> listPublic() {
        return listForTenant(null);
    }

    /** 按 code 取单个主题（租户优先，回落全局预设）；无匹配返回 null。 */
    public ThemeConfig get(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Long tenantId = TenantContext.tenantId();
        for (ThemeConfig t : listForTenant(tenantId)) {
            if (code.equals(t.getCode())) {
                return t;
            }
        }
        return null;
    }

    /** 公开按 code 取主题（免认证分享页用）：仅全局预设。 */
    public ThemeConfig getPublic(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ThemeConfig t : listForTenant(null)) {
            if (code.equals(t.getCode())) {
                return t;
            }
        }
        return null;
    }

    /** 新建/更新租户自定义主题（code 唯一；写入本租户行）。 */
    public ThemeConfig save(ThemeConfig form) {
        Long tenantId = TenantContext.requireTenantId();
        if (form.getCode() == null || form.getCode().isBlank() || form.getTokens() == null) {
            return null;
        }
        ThemeConfig row;
        if (form.getId() != null) {
            row = mapper.selectById(form.getId());
            if (row == null || row.getTenantId() == null || !row.getTenantId().equals(tenantId)) {
                return null; // 不存在或无权改（全局内置只读）
            }
        } else {
            // 同租户下 code 唯一：已存在则转更新
            row = mapper.selectOne(new LambdaQueryWrapper<ThemeConfig>()
                    .eq(ThemeConfig::getCode, form.getCode())
                    .eq(ThemeConfig::getTenantId, tenantId)
                    .last("LIMIT 1"));
            if (row == null) {
                row = new ThemeConfig();
                row.setTenantId(tenantId);
                row.setCode(form.getCode());
                row.setBuiltin(0);
            }
        }
        if (form.getName() != null) {
            row.setName(form.getName());
        }
        if (form.getDark() != null) {
            row.setDark(form.getDark());
        }
        row.setTokens(form.getTokens());
        if (row.getId() == null) {
            if (row.getName() == null) {
                row.setName(row.getCode());
            }
            if (row.getDark() == null) {
                row.setDark(0);
            }
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        invalidate();
        return row;
    }

    /** 删除租户自定义主题（内置预设不可删）。 */
    public boolean delete(Long id) {
        Long tenantId = TenantContext.requireTenantId();
        ThemeConfig row = mapper.selectById(id);
        if (row == null || row.getTenantId() == null || !row.getTenantId().equals(tenantId)
                || (row.getBuiltin() != null && row.getBuiltin() == 1)) {
            return false;
        }
        mapper.deleteById(id);
        invalidate();
        return true;
    }

    /** 写操作后失效全部列表缓存。 */
    public void invalidate() {
        cache.clear();
    }

    // ==================== 内部 ====================

    private List<ThemeConfig> listForTenant(Long tenantId) {
        String key = String.valueOf(tenantId);
        long now = System.currentTimeMillis();
        CachedList hit = cache.get(key);
        if (hit != null && hit.expireAt > now) {
            return hit.themes;
        }
        List<ThemeConfig> themes = resolve(tenantId);
        long ttl = cacheTtlSeconds <= 0 ? 1800 : cacheTtlSeconds;
        cache.put(key, new CachedList(themes, now + ttl * 1000));
        return themes;
    }

    private List<ThemeConfig> resolve(Long tenantId) {
        try {
            List<ThemeConfig> rows = mapper.selectList(new LambdaQueryWrapper<ThemeConfig>()
                    .orderByAsc(ThemeConfig::getId));
            Map<String, ThemeConfig> byCode = new java.util.LinkedHashMap<>();
            // 先放全局预设，再用租户自定义覆盖同 code
            for (ThemeConfig r : rows) {
                if (r.getTenantId() == null) {
                    byCode.put(r.getCode(), r);
                }
            }
            if (tenantId != null) {
                for (ThemeConfig r : rows) {
                    if (tenantId.equals(r.getTenantId())) {
                        byCode.put(r.getCode(), r);
                    }
                }
            }
            return new ArrayList<>(byCode.values());
        } catch (Exception e) {
            log.debug("theme_config 读取跳过（表未就绪）：{}", e.getMessage());
            return List.of();
        }
    }

    private record CachedList(List<ThemeConfig> themes, long expireAt) {
    }
}
