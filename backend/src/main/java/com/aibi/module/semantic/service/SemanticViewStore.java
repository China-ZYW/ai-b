package com.aibi.module.semantic.service;

import com.aibi.module.chat.nl2sql.SemanticView;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义视图基座协作类（T8.4 自 SemanticService 抽出）：语义行的读取、缓存视图构建与
 * 行级存在性/启用判定——{@link SemanticLabels}（展示名）、{@link SemanticPromptSupplier}
 * （Prompt 供给）、{@link SemanticWriter}（写路径）共同的地基。
 *
 * <p>视图按数据源缓存（TTL 同 schema 缓存），写操作即失效；无语义数据时返回空视图（消费点零影响）。
 */
@Component
class SemanticViewStore {

    private static final Logger log = LoggerFactory.getLogger(SemanticViewStore.class);

    static final String TYPE_TABLE = "TABLE";
    static final String TYPE_COLUMN = "COLUMN";
    static final String ROLE_AUTO = "AUTO";
    static final String ROLE_MEASURE = "MEASURE";

    private final SemanticMetaMapper mapper;
    private final long cacheTtlSeconds;

    private final Map<Long, CachedView> cache = new ConcurrentHashMap<>();

    SemanticViewStore(SemanticMetaMapper mapper,
                      @Value("${aibi.schema.cache-ttl-seconds:1800}") long cacheTtlSeconds) {
        this.mapper = mapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    /** 语义行 Mapper（写路径复用，包内可见避免二次封装）。 */
    SemanticMetaMapper mapper() {
        return mapper;
    }

    /** 某数据源的全部语义行（管理页）。 */
    List<SemanticMeta> list(Long datasourceId) {
        return mapper.selectList(new LambdaQueryWrapper<SemanticMeta>()
                .eq(SemanticMeta::getDatasourceId, datasourceId)
                .orderByAsc(SemanticMeta::getTableName)
                .orderByAsc(SemanticMeta::getObjectType)
                .orderByAsc(SemanticMeta::getColumnName));
    }

    /** 带缓存的语义视图；无语义数据时返回空视图（消费点零影响）。 */
    SemanticView view(Long datasourceId) {
        if (datasourceId == null) {
            return SemanticView.empty();
        }
        long now = System.currentTimeMillis();
        CachedView hit = cache.get(datasourceId);
        if (hit != null && hit.expireAt > now) {
            return hit.view;
        }
        SemanticView view = buildView(datasourceId);
        long ttl = cacheTtlSeconds <= 0 ? 1800 : cacheTtlSeconds;
        cache.put(datasourceId, new CachedView(view, now + ttl * 1000));
        return view;
    }

    /**
     * 列角色查询（图表推荐用）：按列名跨表查找人工确认的角色（非 AUTO）；
     * 结果列通常为别名/原列名，跨表同名同角色时直接采信。无匹配返回 null。
     */
    String columnRole(Long datasourceId, String columnName) {
        if (datasourceId == null || columnName == null) {
            return null;
        }
        for (Map<String, SemanticMeta> cols : view(datasourceId).columns().values()) {
            SemanticMeta m = cols.get(columnName);
            if (m != null && m.getColumnRole() != null && !ROLE_AUTO.equals(m.getColumnRole())
                    && enabled(m)) {
                return m.getColumnRole();
            }
        }
        return null;
    }

    /**
     * 度量单位查询（图表标注用）：按列名跨表匹配已启用语义行的 {@code unit}。
     *
     * <p>与 {@link #columnRole}、{@code aliasOf} 同策略：结果列通常是 SQL 别名或原列名，跨表同名时直接采信。
     * 未配置单位（含停用行）返回 null，由调用方回落"按列名关键词推断"或"无单位"。
     */
    String unitOf(Long datasourceId, String columnName) {
        if (datasourceId == null || columnName == null) {
            return null;
        }
        for (Map<String, SemanticMeta> cols : view(datasourceId).columns().values()) {
            SemanticMeta m = cols.get(columnName);
            if (m != null && enabled(m) && notBlank(m.getUnit())) {
                return m.getUnit().trim();
            }
        }
        return null;
    }

    /** 写操作后失效该数据源视图缓存。 */
    void invalidate(Long datasourceId) {
        if (datasourceId != null) {
            cache.remove(datasourceId);
        } else {
            cache.clear();
        }
    }

    SemanticView buildView(Long datasourceId) {
        List<SemanticMeta> rows;
        try {
            rows = mapper.selectList(new LambdaQueryWrapper<SemanticMeta>()
                    .eq(SemanticMeta::getDatasourceId, datasourceId)
                    .eq(SemanticMeta::getEnabled, 1));
        } catch (Exception e) {
            log.debug("语义视图读取跳过（表未就绪）：{}", e.getMessage());
            return SemanticView.empty();
        }
        Map<String, SemanticMeta> tables = new HashMap<>();
        Map<String, Map<String, SemanticMeta>> columns = new HashMap<>();
        for (SemanticMeta r : rows) {
            if (TYPE_TABLE.equals(r.getObjectType())) {
                tables.put(r.getTableName(), r);
            } else {
                columns.computeIfAbsent(r.getTableName(), k -> new HashMap<>())
                        .put(r.getColumnName(), r);
            }
        }
        return new SemanticView(tables, columns);
    }

    SemanticMeta insertRow(Long datasourceId, String objectType, String table,
                           String column, String alias, String role) {
        SemanticMeta row = new SemanticMeta();
        row.setDatasourceId(datasourceId);
        row.setObjectType(objectType);
        row.setTableName(table);
        row.setColumnName(column);
        row.setAliasCn(alias);
        row.setColumnRole(role == null ? ROLE_AUTO : role);
        row.setEnabled(1);
        mapper.insert(row);   // 租户拦截器自动补 tenant_id
        return row;
    }

    /** 表/列名归一化（大小写不敏感），与 MySQL utf8mb4 唯一索引排序规则对齐。 */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** 列级存在性键：表名 + 列名（均归一化，用 NUL 分隔避免拼接歧义）。 */
    static String colKey(String table, String column) {
        return norm(table) + '\u0000' + norm(column);
    }

    /** 插入骨架行；命中唯一索引（大小写/历史遗留/并发）时视为已存在并跳过，保证初始化幂等。 */
    boolean safeInsert(Long datasourceId, String objectType, String table, String column, String alias) {
        try {
            insertRow(datasourceId, objectType, table, column, alias, null);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("语义骨架行已存在，跳过: ds={} {} {}.{}", datasourceId, objectType, table, column);
            return false;
        }
    }

    static boolean enabled(SemanticMeta m) {
        return m.getEnabled() == null || m.getEnabled() == 1;
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private record CachedView(SemanticView view, long expireAt) {
    }
}
