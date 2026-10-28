package com.aibi.module.datasource;

import com.aibi.module.datasource.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 元数据缓存（§3.8）：缓存每个 (租户, 数据源) 的表结构扫描结果，避免每次问数都重新走
 * JDBC {@code DatabaseMetaData} 扫描（该操作对大库开销明显，是问数链路的主要延迟来源之一）。
 *
 * <p>归属数据源模块：缓存对象是数据源的 {@link TableMeta}，且需在数据源增删改时失效，
 * 放在此处可让 {@code DataSourceService} 直接调用而不产生 datasource→chat 的反向依赖。
 *
 * <p>骨架版为进程内 {@link ConcurrentHashMap} + TTL；生产版按 §3.8 平替为 Redis（key=schema:{dsId}，
 * TTL 30min，并用 lock:meta:{dsId} 防并发重复扫描）。缓存键含租户 id，天然不跨租户共享。
 *
 * <p><b>并发与过期修复（问题#4）</b>：{@link #getOrLoad} 对<b>同一键</b>加锁，保证至多一个线程执行
 * 扫描（其余线程复用其结果），消除“惊群”式重复 {@code DatabaseMetaData} 扫描；过期条目在重扫前先清理，
 * 避免脏数据长期驻留。
 */
@Component
public class SchemaCache {

    private static final Logger log = LoggerFactory.getLogger(SchemaCache.class);

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    /** 每把键一个锁对象，串行化同一数据源的加载，杜绝并发重复扫表。键集合随数据源数有界，可忽略。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SchemaCache(@Value("${aibi.schema.cache-ttl-seconds:1800}") long ttlSeconds) {
        this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
    }

    /**
     * 命中且未过期则返回缓存；否则调用 {@code loader} 扫描、写入缓存后返回。
     *
     * @param tenantId     租户 id（隔离键）
     * @param datasourceId 数据源 id
     * @param loader       未命中时的表结构加载器（通常是 {@code adapter::scanSchema}）
     */
    public List<TableMeta> getOrLoad(Long tenantId, Long datasourceId, Supplier<List<TableMeta>> loader) {
        String key = tenantId + ":" + datasourceId;
        long now = System.currentTimeMillis();
        Entry hit = cache.get(key);
        if (hit != null && (ttlMillis <= 0 || hit.expireAt() > now)) {
            return hit.tables();
        }
        // 同一 key 串行化：首个进锁的线程负责扫描并写入，其余线程复用其结果，杜绝重复扫表（惊群）；
        // 过期条目先清理再加载，避免脏数据长期驻留内存。
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            Entry entry = cache.get(key);
            if (entry != null && (ttlMillis <= 0 || entry.expireAt() > now)) {
                return entry.tables();
            }
            if (entry != null) {
                cache.remove(key);   // 过期先清
                log.debug("元数据缓存已过期，重新扫描: {}", key);
            }
            List<TableMeta> tables = loader.get();
            cache.put(key, new Entry(tables, now + ttlMillis));
            log.debug("元数据缓存刷新 [{}]，表数={}", key, tables.size());
            return tables;
        }
    }

    /** 主动失效某数据源的缓存（数据源结构变更、更新或删除时调用）。 */
    public void invalidate(Long tenantId, Long datasourceId) {
        cache.remove(tenantId + ":" + datasourceId);
    }

    /** 清空全部缓存（仅用于运维/测试）。 */
    public void clear() {
        cache.clear();
    }

    private record Entry(List<TableMeta> tables, long expireAt) {
    }
}
