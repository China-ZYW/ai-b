package com.aibi.module.config.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.TenantContext;
import com.aibi.common.util.JsonCompare;
import com.aibi.module.config.entity.SysConfig;
import com.aibi.module.config.mapper.SysConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一配置中心服务（配置化升级 V2）：{@code sys_config} 的解析、读写与缓存。
 *
 * <p><b>解析顺序</b>：TENANT(scope_id=当前租户) → GLOBAL(scope_id=NULL) → 调用方代码默认，
 * 使"平台默认 + 租户个性化覆盖"无需 if/else 散落业务代码。
 *
 * <p><b>平台管控（{@code managed}）</b>：GLOBAL 行可被超管置为"管控"（{@code /api/admin/config}）。
 * 管控键在<b>所有租户</b>被锁死为平台值——解析时 GLOBAL 优先于 TENANT，租户写入抛
 * {@link ErrorCode#FORBIDDEN}。这样同一个机制既能当"平台默认值"（{@code managed=0}），
 * 也能当"平台强制策略"（{@code managed=1}），无需为管控另建一套表。
 * 关闭管控后租户既有覆盖行原样复活（管控期间不删数据），故开关可安全来回切换。
 *
 * <p><b>缓存</b>：按 scope 维度缓存行集合，TTL 由 {@code aibi.config.cache-ttl-seconds} 控制，
 * <b>默认 0 = 不缓存</b>。配置数据量小、读多写少，但<b>跨实例可见性要求高</b>——同一个
 * {@code aibi_meta} 可能被多个 JVM（如本地联调实例与生产实例）或库外途径修改，而任何写操作
 * 只能失效<b>本实例</b>的缓存。若沿用长 TTL，其它写入方造成的改动最长要等 TTL 到期才被感知，
 * 表现为"平台侧已改、租户侧还是旧值 / 配了没生效"。故默认关闭缓存，保证 {@code sys_config}
 * 改动<b>立即生效</b>；确有性能需要时再显式调大该 TTL（代价是跨实例可能滞后）。
 *
 * <p>本表在租户拦截器 IGNORE 名单内（GLOBAL/TENANT 二级 scope 无法靠自动注入表达），
 * 所有查询在此显式携带 scope 条件。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_TENANT = "TENANT";

    private final SysConfigMapper mapper;
    private final ObjectMapper objectMapper;
    private final long configCacheTtlSeconds;

    /** scope 级行缓存：key = "G" 或 "T:{tenantId}"；仅在 TTL > 0 时启用（默认不缓存）。 */
    private final Map<String, CachedRows> cache = new ConcurrentHashMap<>();

    public ConfigService(SysConfigMapper mapper, ObjectMapper objectMapper,
                         @Value("${aibi.config.cache-ttl-seconds:0}") long configCacheTtlSeconds) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.configCacheTtlSeconds = configCacheTtlSeconds;
    }

    // ==================== 解析（读路径） ====================

    /** 解析当前租户上下文下的配置原始 JSON 字符串；未配置返回 null。 */
    public String resolveRaw(String category, String key) {
        Long tenantId = TenantContext.tenantId();
        return resolveRaw(tenantId, category, key);
    }

    /** 解析指定租户视图下的配置原始 JSON 字符串（供无租户上下文的后台任务使用）。 */
    public String resolveRaw(Long tenantId, String category, String key) {
        SysConfig global = findIn(globalRows(), category, key);
        // 平台管控键：GLOBAL 压过 TENANT——即便租户留有覆盖行也不再生效（管控期间保留数据，关闭后自动复活）
        if (global != null && isManaged(global)) {
            return global.getValueJson();
        }
        if (tenantId != null) {
            SysConfig row = findIn(rows(tenantId), category, key);
            if (row != null) {
                return row.getValueJson();
            }
        }
        return global == null ? null : global.getValueJson();
    }

    /**
     * 当前租户合并视图：category → key → 解析后的 JSON 值（供前端 /api/config/effective）。
     * <p>平台默认（GLOBAL）与租户覆盖（TENANT）必须同口径解析。任一侧回传原始 JSON 串，前端拿到的
     * 就是带引号的 "\"COMPACT\"" 或字符串 "8"，枚举比对与数值转换全部失配——平台级默认值会被静默忽略，
     * 而租户级覆盖却生效，表现为“配了没用”。
     */
    public Map<String, Map<String, Object>> effective(Long tenantId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<SysConfig> global = globalRows();
        collect(out, global);
        if (tenantId != null) {
            // 被平台管控的键：GLOBAL 值即最终值，租户行不参与合并（否则前端"生效值"与实际不符）
            collect(out, rows(tenantId), managedKeysOf(global));
        }
        return out;
    }

    /**
     * 合并视图的<b>说明字典</b>：category → key → description（供租户侧配置页「生效值」表展示中文说明）。
     *
     * <p><b>与 {@link #effective(Long)} 同口径合并</b>：GLOBAL 兜底在前，TENANT 非空说明覆盖在后
     * （租户行说明为空时沿用平台说明，不反向清空）。故意独立成字典而不改 {@code /effective} 的返回结构——
     * 那个扁平结构被 {@code stores/config.ts} 在应用启动时消费（ResultPanel / WidgetCard 等），
     * 贸然改形状会波及全部展示配置消费方；说明只是配置页的展示文案，单独一个只读端点最稳。
     */
    public Map<String, Map<String, String>> descriptions(Long tenantId) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        collectDesc(out, globalRows());
        if (tenantId != null) {
            collectDesc(out, rows(tenantId));
        }
        return out;
    }

    /** 说明收集：空说明不覆盖已有（租户留空说明不抹掉平台说明）。 */
    private void collectDesc(Map<String, Map<String, String>> out, List<SysConfig> list) {
        for (SysConfig r : list) {
            String d = r.getDescription();
            if (d == null || d.isBlank()) {
                continue;
            }
            out.computeIfAbsent(r.getCategory(), k -> new LinkedHashMap<>()).put(r.getConfigKey(), d);
        }
    }

    /**
     * 平台管控键清单（{@code "CATEGORY:configKey"}），供租户侧前端把对应配置项显示为"平台管控"、
     * 禁用编辑入口。仅用于<b>展示锁定态</b>：真正的拦截面在 {@link #resolveRaw} 与 {@link #upsert}，
     * 前端禁用只是提示，不是安全边界。
     */
    public List<String> managedKeys() {
        return new ArrayList<>(managedKeysOf(globalRows()));
    }

    // ==================== 类型化读取 ====================

    public int getInt(Long tenantId, String category, String key, int def) {
        JsonNode n = node(tenantId, category, key);
        return n != null && n.isNumber() ? n.asInt() : def;
    }

    public boolean getBool(Long tenantId, String category, String key, boolean def) {
        JsonNode n = node(tenantId, category, key);
        return n != null && n.isBoolean() ? n.asBoolean() : def;
    }

    public String getString(Long tenantId, String category, String key, String def) {
        JsonNode n = node(tenantId, category, key);
        if (n == null) {
            return def;
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    /** 读取字符串数组配置（如 NL2SQL Prompt 业务规则清单）；未配置返回默认列表。 */
    public List<String> getStringList(Long tenantId, String category, String key, List<String> def) {
        JsonNode n = node(tenantId, category, key);
        if (n == null || !n.isArray()) {
            return def;
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode item : n) {
            out.add(item.asText());
        }
        return out;
    }

    private JsonNode node(Long tenantId, String category, String key) {
        String raw = resolveRaw(tenantId, category, key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("配置值 JSON 解析失败 [{}.{}]: {}", category, key, e.getMessage());
            return null;
        }
    }

    // ==================== 管理（写路径） ====================

    /** 按 category 列出某 scope 的配置行（管理页）。 */
    public List<SysConfig> listRows(String scopeType, Long scopeId, String category) {
        LambdaQueryWrapper<SysConfig> q = new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getScopeType, scopeType)
                .eq(SysConfig::getCategory, category)
                .orderByAsc(SysConfig::getConfigKey);
        if (SCOPE_TENANT.equals(scopeType)) {
            q.eq(SysConfig::getScopeId, scopeId);
        } else {
            q.isNull(SysConfig::getScopeId);
        }
        return mapper.selectList(q);
    }

    /**
     * upsert 配置行；存在则值覆盖 + version+1，不存在则插入。写后失效缓存。
     *
     * @return 写入后的行；<b>内容无变化时返回 {@code null}（不写库、不动版本号）</b>
     */
    public SysConfig upsert(String scopeType, Long scopeId, String category, String configKey,
                            String valueJson, String description) {
        return upsert(scopeType, scopeId, category, configKey, valueJson, description, null);
    }

    /**
     * upsert 配置行（可同时设置平台管控标记）。
     *
     * <p>{@code managed} 仅对 GLOBAL scope 有意义：{@code true}=把该键锁死为平台值（所有租户不可覆盖），
     * {@code false}=解除管控，{@code null}=不改变现有标记（老调用方语义）。
     *
     * <p>租户写「已被平台管控」的键会被<b>直接拒绝</b>而不是静默忽略：静默忽略会让人以为
     * "改了没生效"，比明确报错更难排查。
     *
     * <p><b>幂等契约</b>：值（按 JSON 语义比较，忽略空白与键序）、说明、管控标记三者都没变、
     * 且行本身处于启用态时，<b>不写库、不递增 {@code version}，返回 {@code null}</b>。
     * 理由：{@code sys_config.version} 是这行配置"被改过几次"的事实记录，供审计与
     * "配置是否被人动过"的判断使用；管理页多点几次「保存」不该把它顶上去。调用方（控制器）需处理
     * {@code null} —— 表现为"提交成功但未发生变更"。
     */
    public SysConfig upsert(String scopeType, Long scopeId, String category, String configKey,
                            String valueJson, String description, Boolean managed) {
        if (SCOPE_TENANT.equals(scopeType)) {
            assertNotManaged(category, configKey);
        }
        LambdaQueryWrapper<SysConfig> q = new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getScopeType, scopeType)
                .eq(SysConfig::getCategory, category)
                .eq(SysConfig::getConfigKey, configKey);
        if (SCOPE_TENANT.equals(scopeType)) {
            q.eq(SysConfig::getScopeId, scopeId);
        } else {
            q.isNull(SysConfig::getScopeId);
        }
        SysConfig row = mapper.selectOne(q);
        if (row == null) {
            row = new SysConfig();
            row.setScopeType(scopeType);
            row.setScopeId(SCOPE_TENANT.equals(scopeType) ? scopeId : null);
            row.setCategory(category);
            row.setConfigKey(configKey);
            row.setValueJson(valueJson);
            row.setDescription(description);
            row.setVersion(1);
            row.setEnabled(1);
            row.setManaged(SCOPE_GLOBAL.equals(scopeType) && Boolean.TRUE.equals(managed) ? 1 : 0);
            mapper.insert(row);
        } else {
            boolean reEnable = row.getEnabled() == null || row.getEnabled() == 0;
            int currentManaged = row.getManaged() == null ? 0 : row.getManaged();
            boolean managedChanged = SCOPE_GLOBAL.equals(scopeType) && managed != null
                    && currentManaged != (managed ? 1 : 0);
            boolean descriptionChanged = description != null
                    && !Objects.equals(row.getDescription(), description);
            // 幂等护栏：值 / 说明 / 管控标记均无变化且行已启用 → 本次提交是空操作，直接返回 null。
            // 不这么做的话，管理页多点几次「保存」就会把 version 顶上去，污染"这行配置被改过几次"的事实。
            if (!reEnable && !managedChanged && !descriptionChanged
                    && sameJson(row.getValueJson(), valueJson)) {
                log.debug("配置无变化，跳过写入并保留版本号 [{}/{}/{}]", scopeType, category, configKey);
                return null;
            }
            row.setValueJson(valueJson);
            if (descriptionChanged) {
                row.setDescription(description);
            }
            if (SCOPE_GLOBAL.equals(scopeType) && managed != null) {
                row.setManaged(managed ? 1 : 0);
            }
            row.setVersion((row.getVersion() == null ? 1 : row.getVersion()) + 1);
            row.setEnabled(1);
            mapper.updateById(row);
        }
        invalidate();
        return row;
    }

    /** 删除配置行（恢复上一级默认）；返回是否删除成功。 */
    public boolean delete(Long id, String scopeType, Long scopeId) {
        SysConfig row = mapper.selectById(id);
        if (row == null) {
            return false;
        }
        if (!row.getScopeType().equals(scopeType)) {
            return false;
        }
        if (SCOPE_TENANT.equals(scopeType) && !scopeId.equals(row.getScopeId())) {
            return false;
        }
        if (SCOPE_GLOBAL.equals(scopeType) && row.getScopeId() != null) {
            return false;
        }
        mapper.deleteById(id);
        invalidate();
        return true;
    }

    /** 写操作后全量失效缓存；未启用缓存（默认）时为空操作，保留以便显式调大 TTL 后语义不变。 */
    public void invalidate() {
        cache.clear();
    }

    // ==================== 内部 ====================

    private void collect(Map<String, Map<String, Object>> out, List<SysConfig> rows) {
        collect(out, rows, Set.of());
    }

    /** 合并行到视图；{@code skipKeys} 内的键（=被平台管控）不参与覆盖。 */
    private void collect(Map<String, Map<String, Object>> out, List<SysConfig> rows, Set<String> skipKeys) {
        for (SysConfig r : rows) {
            if (r.getEnabled() != null && r.getEnabled() == 0) {
                continue;
            }
            if (skipKeys.contains(keyOf(r.getCategory(), r.getConfigKey()))) {
                continue;
            }
            Map<String, Object> bucket = out.computeIfAbsent(r.getCategory(), k -> new LinkedHashMap<>());
            bucket.put(r.getConfigKey(), parseValue(r.getValueJson()));
        }
    }

    /**
     * 配置值的语义比较：仅空白、缩进、键序不同不算变化——前端编辑框里的 JSON 常被重新格式化后再提交。
     *
     * <p>实现已收敛到 {@link JsonCompare#same}（全项目唯一口径），本方法只保留为语义化包装，
     * 便于阅读与后续替换。解析失败退化为"不等"的取舍见该处注释。
     */
    private boolean sameJson(String a, String b) {
        return JsonCompare.same(objectMapper, a, b);
    }

    /** 从 GLOBAL 行集合中挑出被平台管控的键（{@code "CATEGORY:configKey"}）。 */
    private Set<String> managedKeysOf(List<SysConfig> globalRows) {
        Set<String> out = new HashSet<>();
        for (SysConfig r : globalRows) {
            if (isManaged(r)) {
                out.add(keyOf(r.getCategory(), r.getConfigKey()));
            }
        }
        return out;
    }

    /** 管控键的作用域限定键：category 与 configKey 之间用冒号分隔，避免跨分类同名键互相干扰。 */
    private static String keyOf(String category, String configKey) {
        return category + ":" + configKey;
    }

    private static boolean isManaged(SysConfig row) {
        return row.getManaged() != null && row.getManaged() == 1;
    }

    /**
     * 断言该 (category, key) 未被平台管控，被管控则拒绝租户写入。
     *
     * <p>判定只看 GLOBAL 行的 {@code managed} 标记，与租户是否已有覆盖行无关；关闭管控后立即可写。
     */
    private void assertNotManaged(String category, String configKey) {
        SysConfig global = findIn(globalRows(), category, configKey);
        if (global != null && isManaged(global)) {
            throw new BizException(ErrorCode.FORBIDDEN, "配置项「" + category + "/" + configKey
                    + "」已由平台统一管控，租户不可覆盖；如需调整请联系平台管理员");
        }
    }

    private Object parseValue(String valueJson) {
        if (valueJson == null || valueJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(valueJson, Object.class);
        } catch (Exception e) {
            return valueJson;
        }
    }

    private SysConfig findIn(List<SysConfig> rows, String category, String key) {
        for (SysConfig r : rows) {
            if (r.getEnabled() != null && r.getEnabled() == 0) {
                continue;
            }
            if (r.getCategory().equals(category) && r.getConfigKey().equals(key)) {
                return r;
            }
        }
        return null;
    }

    private List<SysConfig> rows(Long tenantId) {
        return cached("T:" + tenantId, () -> mapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getScopeType, SCOPE_TENANT)
                .eq(SysConfig::getScopeId, tenantId)));
    }

    private List<SysConfig> globalRows() {
        return cached("G", () -> mapper.selectList(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getScopeType, SCOPE_GLOBAL)
                .isNull(SysConfig::getScopeId)));
    }

    private List<SysConfig> cached(String key, java.util.function.Supplier<List<SysConfig>> loader) {
        // 默认不缓存（configCacheTtlSeconds <= 0）：保证任意写入方（含其它 JVM / 直连库）的改动立即可见
        long ttl = configCacheTtlSeconds;
        if (ttl <= 0) {
            return load(loader);
        }
        long now = System.currentTimeMillis();
        CachedRows hit = cache.get(key);
        if (hit != null && hit.expireAt > now) {
            return hit.rows;
        }
        List<SysConfig> rows = load(loader);
        cache.put(key, new CachedRows(rows, now + ttl * 1000));
        return rows;
    }

    /** 从库装载某个 scope 的行集合；表未就绪等异常按空集合处理（保持既有降级语义）。 */
    private List<SysConfig> load(java.util.function.Supplier<List<SysConfig>> loader) {
        try {
            return loader.get();
        } catch (Exception e) {
            log.debug("配置读取跳过（表未就绪）：{}", e.getMessage());
            return List.of();
        }
    }

    private record CachedRows(List<SysConfig> rows, long expireAt) {
    }
}
