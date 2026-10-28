package com.aibi.module.chat.service;

import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.chat.nl2sql.FewShotStore;
import com.aibi.module.chat.nl2sql.SchemaSlice;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 对话页"推荐问题"生成服务（问题4）：依数据源表结构 + 语义层业务口径 + curated 优质问法，
 * 由 LLM 智能生成贴业务、用户最可能问的问题（V3 增强：注入中文别名/口径/枚举与样例风格）。
 *
 * <p>三层保障：① <b>缓存</b>——按 {@code (租户, 数据源)} 进程内缓存，TTL 内直接命中，避免每次进对话页都打 LLM；
 * ② <b>降级</b>——LLM 不可用/失败时，退化为基于 schema 的通用问题模板；③ <b>兜底</b>——连 schema 都拿不到时，
 * 返回一组与业务无关的默认问题，保证对话页永不空白。本接口定位为 UI 辅助，故对上层"永不抛错"。
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    /** 无 schema / LLM 均不可用时的兜底问题（保证对话页不空白）。 */
    private static final List<String> DEFAULT_SUGGESTIONS = List.of(
            "各分类的销售额占比是多少？",
            "最近30天的订单量趋势如何？",
            "销售额最高的前10个商品是哪些？",
            "每月的客户数量变化怎样？");

    private final DataSourceService dataSourceService;
    private final LlmRouter llmRouter;
    private final SemanticService semanticService;
    private final FewShotStore fewShotStore;
    private final int count;
    private final long ttlMillis;
    private final int maxTables;

    /** 进程内缓存：key = {@code tenantId:datasourceId}。单机部署足够；生产可平替 Redis。 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SuggestionService(DataSourceService dataSourceService,
                             LlmRouter llmRouter,
                             SemanticService semanticService,
                             FewShotStore fewShotStore,
                             @Value("${aibi.suggestion.count:6}") int count,
                             @Value("${aibi.suggestion.cache-ttl-seconds:1800}") long ttlSeconds,
                             @Value("${aibi.suggestion.max-tables:20}") int maxTables) {
        this.dataSourceService = dataSourceService;
        this.llmRouter = llmRouter;
        this.semanticService = semanticService;
        this.fewShotStore = fewShotStore;
        this.count = count <= 0 ? 6 : count;
        this.ttlMillis = ttlSeconds * 1000;
        this.maxTables = maxTables <= 0 ? 20 : maxTables;
    }

    /** 取某数据源的推荐问题（缓存 → LLM → schema 模板 → 默认兜底，逐级降级，永不抛错）。 */
    public List<String> suggest(Long datasourceId) {
        Long tenantId = TenantContext.requireTenantId();
        String key = tenantId + ":" + datasourceId;
        long now = System.currentTimeMillis();

        CacheEntry hit = cache.get(key);
        if (hit != null && ttlMillis > 0 && now - hit.at() < ttlMillis) {
            return hit.questions();
        }

        List<TableMeta> schema;
        try {
            schema = dataSourceService.getSchema(datasourceId);
        } catch (RuntimeException e) {
            log.warn("获取 schema 失败，返回默认推荐问题: ds={} - {}", datasourceId, e.getMessage());
            return DEFAULT_SUGGESTIONS;   // 瞬时错误不缓存，下次重试
        }
        if (schema == null || schema.isEmpty()) {
            return cacheAndReturn(key, DEFAULT_SUGGESTIONS, now);
        }

        List<String> questions = null;
        try {
            questions = parseQuestions(llmRouter.chat(buildPrompt(datasourceId, schema)), count);
        } catch (RuntimeException e) {
            log.warn("LLM 生成推荐问题失败，降级为基于 schema 的通用问题: ds={} - {}", datasourceId, e.getMessage());
        }
        if (questions == null || questions.isEmpty()) {
            questions = generateFromSchema(schema);
        }
        return cacheAndReturn(key, questions, now);
    }

    /** 主动失效某数据源的推荐问题缓存（数据源结构变更后可调用；当前由 TTL 自然过期）。 */
    public void invalidate(Long tenantId, Long datasourceId) {
        cache.remove(tenantId + ":" + datasourceId);
    }

    // ---------- LLM 生成 ----------

    private String buildPrompt(Long datasourceId, List<TableMeta> schema) {
        // 表排序：语义层列级配置越丰富的表越靠前（截断时优先保留"有人维护过业务口径"的表），同分数保持原顺序
        List<TableMeta> limited = schema.size() > maxTables ? schema.subList(0, maxTables) : schema;
        Map<String, Long> semanticWeight = safeSemanticWeight(datasourceId);
        if (!semanticWeight.isEmpty()) {
            limited = new ArrayList<>(limited);
            limited.sort(Comparator.comparingLong((TableMeta t) -> semanticWeight.getOrDefault(t.name(), 0L))
                    .reversed());
        }

        // 先给全量表清单（截断后 LLM 仍知道库里有哪些表，避免推荐全部集中在前几张表）
        StringBuilder catalog = new StringBuilder("全部表：");
        for (TableMeta t : schema) {
            catalog.append(t.name());
            if (t.comment() != null && !t.comment().isBlank()) {
                catalog.append('(').append(t.comment()).append(')');
            }
            catalog.append("、");
        }
        catalog.setLength(Math.max(catalog.length() - 1, 0));

        StringBuilder sb = new StringBuilder(catalog).append("\n\n");
        for (TableMeta t : limited) {
            sb.append(t.toSchemaLine()).append('\n');
        }
        String schemaText = sb.length() > 6000 ? sb.substring(0, 6000) : sb.toString();

        // 语义层（中文别名/业务口径/枚举）+ curated 优质问法：让问题贴业务、可被 SQL 回答（best-effort，永不阻断生成）
        String semanticSection = safeSemanticSection(datasourceId, limited);
        List<String> examples = safeCuratedExamples(datasourceId);

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个数据分析助手。下面是某个数据库的表结构（schema）");
        if (semanticSection != null) {
            prompt.append("与业务语义（中文别名/口径/枚举）");
        }
        prompt.append("。请结合业务语义，生成 ").append(count)
                .append(" 个用户最可能想问、且能通过 SQL 查询回答的业务问题。\n\n")
                .append("要求：\n")
                .append("1. 用自然、口语化的中文，优先使用业务别名/中文名称而非英文表名列名；\n")
                .append("2. 问题要具体，能明确对应到表和字段（可涉及聚合、分组、排序、时间范围、筛选等）；\n")
                .append("3. 紧扣业务口径与枚举取值，问真实业务关心的问题，避免空泛套话；\n")
                .append("4. 问题必须分散在多张不同的表/业务主题上（至少覆盖 3 张不同的表；表不足 3 张时尽量分散），")
                .append("不要全部集中在一个表或一个业务域；\n")
                .append("5. 每行只输出一个问题，不要编号、不要引号、不要任何解释或多余文字；\n")
                .append("6. 问题之间不要重复。\n\n")
                .append("表结构：\n").append(schemaText);
        if (semanticSection != null) {
            prompt.append("\n\n业务语义（别名/口径/枚举，据此让问题更贴业务）：\n").append(semanticSection);
        }
        if (!examples.isEmpty()) {
            prompt.append("\n\n可参考的优质问法（仅借鉴风格与业务视角，不要照抄，也不要围绕同一主题出全部问题）：\n");
            for (String ex : examples) {
                prompt.append("- ").append(ex).append('\n');
            }
        }
        return prompt.toString();
    }

    /** 列级语义行计数（best-effort）：表名 → 已配置别名/口径/枚举的列数，失败返回空 Map。 */
    private Map<String, Long> safeSemanticWeight(Long datasourceId) {
        try {
            return semanticService.list(datasourceId).stream()
                    .filter(m -> m.getColumnName() != null && !m.getColumnName().isBlank())
                    .collect(Collectors.groupingBy(SemanticMeta::getTableName, Collectors.counting()));
        } catch (RuntimeException e) {
            log.debug("推荐问法语义权重计算跳过: ds={} - {}", datasourceId, e.getMessage());
            return Map.of();
        }
    }

    /** 语义段落（best-effort）：复用 {@link SemanticService#buildPromptSection}，超长截断，失败返回 null。 */
    private String safeSemanticSection(Long datasourceId, List<TableMeta> limited) {
        try {
            String section = semanticService.buildPromptSection(
                    datasourceId, new SchemaSlice(limited, limited.size(), false));
            if (section != null && section.length() > 4000) {
                section = section.substring(0, 4000);
            }
            return section;
        } catch (RuntimeException e) {
            log.debug("推荐问法语义段落注入跳过: ds={} - {}", datasourceId, e.getMessage());
            return null;
        }
    }

    /** curated 优质问法（best-effort）：作为风格参考注入（限 2 条防止单主题锚定），失败返回空列表。 */
    private List<String> safeCuratedExamples(Long datasourceId) {
        try {
            return fewShotStore.curatedQuestions(datasourceId, 2);
        } catch (RuntimeException e) {
            log.debug("推荐问法 curated 样例注入跳过: ds={} - {}", datasourceId, e.getMessage());
            return List.of();
        }
    }

    /** 解析 LLM 文本为问题列表：按行拆分、剥离编号/项目符号/引号、去重、限长、截断到 limit。供追问服务复用。 */
    static List<String> parseQuestions(String raw, int limit) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            s = s.replaceFirst("^([-*•·]\\s*|\\d+[.)、]\\s*)", "").trim();
            s = stripQuotes(s);
            if (s.isEmpty() || s.length() > 80) {
                continue;   // 过长多半是解释性文字，丢弃
            }
            set.add(s);
            if (set.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(set);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '“' && b == '”') || (a == '\'' && b == '\'')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    // ---------- 降级：基于 schema 的通用问题模板 ----------

    private List<String> generateFromSchema(List<TableMeta> schema) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (TableMeta t : schema) {
            String table = t.name();
            List<String> cols = t.columns() == null ? List.of()
                    : t.columns().stream().map(ColumnMeta::name).toList();
            out.add(table + " 一共有多少条记录？");
            out.add("查看 " + table + " 的前 10 条数据");
            if (!cols.isEmpty()) {
                out.add("按 " + cols.get(0) + " 分组统计 " + table + " 的数量分布");
            }
            if (cols.size() > 1) {
                out.add(table + " 中 " + cols.get(1) + " 的最大值、最小值和平均值分别是多少？");
            }
            if (out.size() >= count) {
                break;
            }
        }
        if (out.isEmpty()) {
            return DEFAULT_SUGGESTIONS;
        }
        return out.stream().limit(count).toList();
    }

    private List<String> cacheAndReturn(String key, List<String> questions, long now) {
        List<String> immutable = List.copyOf(questions);
        cache.put(key, new CacheEntry(immutable, now));
        return immutable;
    }

    private record CacheEntry(List<String> questions, long at) {
    }
}
