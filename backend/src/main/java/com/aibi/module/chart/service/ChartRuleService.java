package com.aibi.module.chart.service;

import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chart.entity.ChartRule;
import com.aibi.module.chart.mapper.ChartRuleMapper;
import com.aibi.module.config.service.AibiConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图表规则服务（配置化升级 V2）：{@code chart_rule} 表的解析与缓存，供 {@code ChartRecommender} 消费。
 *
 * <p>解析顺序：KEYWORD 规则按"租户行覆盖全局行"取用（该租户存在任一启用 KEYWORD 行则忽略全局行），
 * priority 小者先匹配；pattern 支持逗号/顿号分隔的同义词组。THRESHOLD 规则覆盖
 * {@code max-pie-slices}/{@code max-measures}，未配置时回落 {@link AibiConfig}（sys_config → 代码默认）。
 *
 * <p>表为空/未就绪时安全回落内置种子规则，绝不影响图表推荐主链路；写操作即失效缓存。
 */
@Service
public class ChartRuleService {

    private static final Logger log = LoggerFactory.getLogger(ChartRuleService.class);

    /** 内置种子关键词规则（chart_rule 表为空时兜底，与 DataSeeder 播种内容一致）。 */
    private static final String[][] SEED_KEYWORD_RULES = {
            // V3 新增图型（图型名更具体，优先级靠前，先于通用描述词命中）
            {"热力图,热力,矩阵", "HEATMAP"},
            {"地图,地域,省份分布,区域分布,地理分布", "MAP"},
            {"雷达图,雷达", "RADAR"},
            {"漏斗图,漏斗,转化漏斗", "FUNNEL"},
            {"仪表盘,仪表,达成率,gauge", "GAUGE"},
            {"散点图,散点,气泡图,气泡,相关性,scatter", "SCATTER"},
            {"面积", "AREA"},
            {"条形,横向柱状,横向柱形,hbar", "HBAR"},
            {"折线,曲线,走势,趋势,line", "LINE"},
            {"柱状,柱形,柱图,bar", "BAR"},
            {"饼图,饼状,环形,圆环,pie,donut", "PIE"},
            {"明细表,表格,列表,table", "TABLE"},
    };

    private final ChartRuleMapper mapper;
    private final AibiConfig aibiConfig;
    private final long cacheTtlSeconds;

    private final Map<String, CachedRules> cache = new ConcurrentHashMap<>();

    public ChartRuleService(ChartRuleMapper mapper,
                            AibiConfig aibiConfig,
                            @Value("${aibi.schema.cache-ttl-seconds:1800}") long cacheTtlSeconds) {
        this.mapper = mapper;
        this.aibiConfig = aibiConfig;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    /** 当前租户生效的 KEYWORD 规则（已按 priority 排序；表空时为内置种子）。 */
    public List<ChartRule> keywordRules() {
        return rules().keyword;
    }

    /** 饼图最大切片数（THRESHOLD 规则 → sys_config → 默认 8）。 */
    public int maxPieSlices() {
        Integer v = rules().thresholds.get("max-pie-slices");
        return v != null ? v : aibiConfig.maxPieSlices();
    }

    /** 单图最多度量数（THRESHOLD 规则 → sys_config → 默认 5）。 */
    public int maxMeasures() {
        Integer v = rules().thresholds.get("max-measures");
        return v != null ? v : aibiConfig.maxMeasures();
    }

    // ==================== 管理（CRUD，租户管理员） ====================

    /** 列出生效范围内的规则（管理页）：本租户行 + 全局行。 */
    public List<ChartRule> list() {
        Long tenantId = TenantContext.tenantId();
        return mapper.selectList(new LambdaQueryWrapper<ChartRule>()
                .and(w -> w.isNull(ChartRule::getTenantId)
                        .or(tenantId != null, o -> o.eq(ChartRule::getTenantId, tenantId)))
                .orderByAsc(ChartRule::getRuleType)
                .orderByAsc(ChartRule::getPriority));
    }

    /** 新建/更新规则（写入本租户行；id 为空则新建）。 */
    public ChartRule save(ChartRule form) {
        Long tenantId = TenantContext.requireTenantId();
        ChartRule row;
        if (form.getId() != null) {
            row = mapper.selectById(form.getId());
            if (row == null || (row.getTenantId() != null && !row.getTenantId().equals(tenantId))) {
                return null; // 不存在或无权改（全局行只读，超管经 admin 通道）
            }
        } else {
            row = new ChartRule();
            row.setTenantId(tenantId);
        }
        if (form.getRuleType() != null) {
            row.setRuleType(form.getRuleType());
        }
        if (form.getPattern() != null) {
            row.setPattern(form.getPattern());
        }
        if (form.getChartType() != null) {
            row.setChartType(form.getChartType());
        }
        if (form.getPriority() != null) {
            row.setPriority(form.getPriority());
        }
        if (form.getEnabled() != null) {
            row.setEnabled(form.getEnabled());
        }
        if (form.getDescription() != null) {
            row.setDescription(form.getDescription());
        }
        if (row.getId() == null) {
            if (row.getRuleType() == null || row.getPattern() == null) {
                return null;
            }
            if (row.getPriority() == null) {
                row.setPriority(100);
            }
            if (row.getEnabled() == null) {
                row.setEnabled(1);
            }
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        invalidate();
        return row;
    }

    /** 删除规则（仅本租户行）。 */
    public boolean delete(Long id) {
        Long tenantId = TenantContext.requireTenantId();
        ChartRule row = mapper.selectById(id);
        if (row == null || row.getTenantId() == null || !row.getTenantId().equals(tenantId)) {
            return false;
        }
        mapper.deleteById(id);
        invalidate();
        return true;
    }

    /** 写操作后失效全部规则缓存。 */
    public void invalidate() {
        cache.clear();
    }

    // ==================== 内部 ====================

    private record Resolved(List<ChartRule> keyword, Map<String, Integer> thresholds) {
    }

    private Resolved rules() {
        Long tenantId = TenantContext.tenantId();
        String key = String.valueOf(tenantId);
        long now = System.currentTimeMillis();
        CachedRules hit = cache.get(key);
        if (hit != null && hit.expireAt > now) {
            return hit.resolved;
        }
        Resolved resolved = resolve(tenantId);
        long ttl = cacheTtlSeconds <= 0 ? 1800 : cacheTtlSeconds;
        cache.put(key, new CachedRules(resolved, now + ttl * 1000));
        return resolved;
    }

    private Resolved resolve(Long tenantId) {
        List<ChartRule> keyword = new ArrayList<>();
        Map<String, Integer> thresholds = new ConcurrentHashMap<>();
        try {
            List<ChartRule> rows = mapper.selectList(new LambdaQueryWrapper<ChartRule>()
                    .eq(ChartRule::getEnabled, 1));
            List<ChartRule> tenantKw = new ArrayList<>();
            List<ChartRule> globalKw = new ArrayList<>();
            Map<String, Integer> tenantTh = new ConcurrentHashMap<>();
            Map<String, Integer> globalTh = new ConcurrentHashMap<>();
            for (ChartRule r : rows) {
                boolean tenantRow = r.getTenantId() != null && r.getTenantId().equals(tenantId);
                boolean globalRow = r.getTenantId() == null;
                if (!tenantRow && !globalRow) {
                    continue; // 其它租户的行不参与
                }
                if (ChartRule.TYPE_THRESHOLD.equals(r.getRuleType())) {
                    if (r.getPattern() != null && r.getPriority() != null) {
                        (tenantRow ? tenantTh : globalTh).put(r.getPattern(), r.getPriority());
                    }
                } else if (ChartRule.TYPE_KEYWORD.equals(r.getRuleType())
                        && r.getPattern() != null && r.getChartType() != null) {
                    (tenantRow ? tenantKw : globalKw).add(r);
                }
            }
            // ===== 覆盖语义（刻意的整体替换，非合并）=====
            // KEYWORD 规则按「租户行整体覆盖全局行」取用：只要本租户存在任一启用 KEYWORD 行，
            // 就<b>完全忽略</b>全局 KEYWORD 行（globalKw 整组丢弃），只用本租户那一组。
            // 这与 sys_config 的 scope 语义（租户覆盖平台）一致，但副作用是：
            // 租户只要新增一条关键词规则，所有内置种子关键词（热力图/地图/雷达/漏斗…）对该租户即全部失效，
            // 除非租户把想要的内置规则也一并显式补回。这是「租户完全掌控图表推荐」的有意设计，
            // 但运维必须知晓——避免误以为"加一条规则只是追加"。THRESHOLD 则是合并（tenant 覆盖同 key 的 global）。
            keyword.addAll(tenantKw.isEmpty() ? globalKw : tenantKw);
            keyword.sort(Comparator.comparingInt(r -> r.getPriority() == null ? 100 : r.getPriority()));
            thresholds.putAll(globalTh);
            thresholds.putAll(tenantTh);
        } catch (Exception e) {
            log.debug("chart_rule 读取跳过（表未就绪），使用内置种子规则：{}", e.getMessage());
        }
        if (keyword.isEmpty()) {
            keyword = seedRules();
        }
        return new Resolved(keyword, thresholds);
    }

    private List<ChartRule> seedRules() {
        List<ChartRule> seeds = new ArrayList<>();
        int priority = 10;
        for (String[] seed : SEED_KEYWORD_RULES) {
            ChartRule r = new ChartRule();
            r.setRuleType(ChartRule.TYPE_KEYWORD);
            r.setPattern(seed[0]);
            r.setChartType(seed[1]);
            r.setPriority(priority++);
            r.setEnabled(1);
            seeds.add(r);
        }
        return seeds;
    }

    private record CachedRules(Resolved resolved, long expireAt) {
    }
}
