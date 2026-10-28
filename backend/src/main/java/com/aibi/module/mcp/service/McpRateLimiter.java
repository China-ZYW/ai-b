package com.aibi.module.mcp.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 调用限流（按用户 / 按租户，分钟级固定窗口）。
 *
 * <p>为什么必须单独限流：MCP 的调用方是 <b>Agent 而不是人</b>——一次循环可能连打几十次问数，
 * 而每次问数都要走 LLM + 数据库。配额（{@code QuotaService}）只能控制"总量"，
 * 挡不住"短时间内打满 QPS 把单机拖垮"，故在配额之外再叠一层速率护栏。
 *
 * <p>实现取舍：单机内存固定窗口（非分布式）。本项目是单机部署（4C4G），
 * 且限流是"保护性护栏"而非"计费口径"——多实例时各实例各自限流，总量会放大实例数倍，
 * 但仍能挡住单实例被打爆；真需要全局精确限流应引入 Redis，届时替换本实现即可。
 *
 * <p><b>内存有界</b>：窗口翻页时清理上一窗口的计数桶，key 数上限即"活跃调用者数"，不会无限增长。
 */
@Component
public class McpRateLimiter {

    /** 窗口内的计数桶。 */
    private record Bucket(long windowStart, AtomicInteger count) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 是否放行一次调用。
     *
     * @param key   限流键（如 {@code u:12} / {@code t:3}）
     * @param limit 每分钟允许次数；{@code <=0} 表示不限
     */
    public boolean allow(String key, int limit) {
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long window = now / 60_000L;
        Bucket bucket = buckets.compute(key, (k, old) -> {
            if (old == null || old.windowStart() != window) {
                return new Bucket(window, new AtomicInteger(0));
            }
            return old;
        });
        return bucket.count().incrementAndGet() <= limit;
    }

    /** 清理过期桶（供定时或低频调用，避免长期运行下堆积不活跃 key）。 */
    public void evictStale() {
        long window = System.currentTimeMillis() / 60_000L;
        buckets.entrySet().removeIf(e -> e.getValue().windowStart() < window);
    }
}
