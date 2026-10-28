package com.aibi.module.chat.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 技能检索索引缓存：给"每次问数都要全量扫一遍候选行"的确定性技能兜底的短 TTL 本地缓存。
 *
 * <p><b>为什么需要它</b>：指标直达 / 样例复用两个技能在 {@link AskSkill#plan} 里必须得到一个候选集
 * （该数据源下所有带 SQL 的指标、所有启用样例），否则无法判定命中。虽然 V4.1 的 Plan 模式已经把
 * "一次问数两遍"压成一遍，但问数本身是高频操作——并发十个人问十次就是十次全表扫描，而这层数据
 * （指标定义、样例库）变更频率极低，属典型的"读多写少"。
 *
 * <p><b>为什么 TTL 定得这么短（默认 30 秒）</b>：这是<b>正确性优先于极致性能</b>的取舍。
 * 管理员在后台新建/修改指标后，最迟 30 秒内一定生效；若缓存一小时，用户会遇到"明明刚存了指标，
 * 问它却还是走大模型"的困惑。相比每次省下的那一毫秒 SQL，能用确定性口径回答才是本技能的价值所在。
 * 缓存不做主动失效（未与指标模块的写操作耦合），靠短 TTL 收敛即可，保持两个模块解耦。
 *
 * <p><b>为什么不放 Redis</b>：本机进程内缓存零网络开销且不引入新依赖；多实例部署时各实例各自持有、
 * 最坏情况只是多打几次 SQL，无一致性风险。后续若指标量极大可平替 Redis（与 {@code SchemaCache} 同理）。
 */
@Component
public class SkillIndexCache {

    private static final Logger log = LoggerFactory.getLogger(SkillIndexCache.class);

    /** 缓存条目：值 + 过期时间戳（毫秒）。 */
    private record Entry(List<?> value, long expireAt) {
        boolean expired(long now) {
            return now > expireAt;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SkillIndexCache(@Value("${aibi.ask.skill-index-cache-seconds:30}") long ttlSeconds) {
        this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
    }

    /**
     * 取缓存值；未命中或已过期则加载并写回。加载抛异常时不污染缓存，直接把异常交给调用方处理。
     *
     * @param key    缓存键（建议含租户与数据源维度，避免跨租户/跨源串数据）
     * @param loader 数据加载器
     * @return 非空列表（null 会被规整为空列表，让调用方的遍历不必判空）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> list(String key, Supplier<List<T>> loader) {
        long now = System.currentTimeMillis();
        Entry hit = cache.get(key);
        if (hit != null && !hit.expired(now)) {
            return (List<T>) hit.value();
        }
        List<T> loaded = loader.get();
        List<T> value = loaded == null ? List.of() : List.copyOf(loaded);
        cache.put(key, new Entry(value, now + ttlMillis));
        return value;
    }

    /** 清除全部缓存（管理后台批量改指标/样例后可调用；无调用方时由 TTL 兜底）。 */
    public void clear() {
        cache.clear();
        log.debug("技能检索索引缓存已清空");
    }

    /**
     * 清除某个前缀下的条目（如仅失效某数据源的索引）。
     *
     * @return 被清除的条目数（便于日志与运维观测）
     */
    public int evictPrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        for (String k : cache.keySet()) {
            if (k.startsWith(prefix)) {
                keys.add(k);
            }
        }
        keys.forEach(cache::remove);
        if (!keys.isEmpty()) {
            log.debug("技能检索索引已失效 {} 条（prefix={}）", keys.size(), prefix);
        }
        return keys.size();
    }
}
