package com.aibi.module.chat.service;

import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.chat.nl2sql.SchemaSlice;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.semantic.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文追问生成服务：依「本轮问题 + 最终 SQL + 结果快照」由 LLM 生成 3~4 条逐轮追问，
 * 供答案下方"你可能还想问"展示——与进入页面时的静态推荐（{@link SuggestionService}）互补：
 * 静态推荐是"进页面时的通用榜单"，追问是"接着这轮答案往下钻"。
 *
 * <p>三层保障与 {@link SuggestionService} 一致：① <b>缓存</b>——按 {@code historyId} 进程内缓存
 * （同一轮的追问是确定性的，TTL 内直接命中，避免重复打 LLM）；② <b>降级</b>——LLM 失败返回空列表，
 * 前端回落到静态推荐；③ <b>永不抛错</b>——追问是 UI 辅助，任何异常都不能影响主问数链路。
 *
 * <p><b>安全边界</b>：本服务不做归属校验，由调用方（Controller/ChatEmbedService）先完成
 * 「本人 owned / 嵌入数据源白名单 + uid 匹配」校验后再传入 {@link QueryHistory}。
 */
@Service
public class FollowupService {

    private static final Logger log = LoggerFactory.getLogger(FollowupService.class);

    private final DataSourceService dataSourceService;
    private final LlmRouter llmRouter;
    private final SemanticService semanticService;
    private final int count;
    private final long ttlMillis;

    /** 进程内缓存：key = {@code historyId}（同一轮问数的追问确定性不变）。 */
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public FollowupService(DataSourceService dataSourceService,
                           LlmRouter llmRouter,
                           SemanticService semanticService,
                           @Value("${aibi.followup.count:4}") int count,
                           @Value("${aibi.followup.cache-ttl-seconds:3600}") long ttlSeconds) {
        this.dataSourceService = dataSourceService;
        this.llmRouter = llmRouter;
        this.semanticService = semanticService;
        this.count = count <= 0 ? 4 : count;
        this.ttlMillis = ttlSeconds <= 0 ? 3600_000L : ttlSeconds * 1000;
    }

    /**
     * 生成本轮问数的上下文追问（缓存 → LLM → 空回落，永不抛错）。
     *
     * @param h 已通过归属校验的问数历史（须为 {@code status=OK} 的成功轮次）
     * @return 追问列表；无历史/失败/非成功轮次返回空列表（前端回落静态推荐）
     */
    public List<String> followups(QueryHistory h) {
        if (h == null || h.getId() == null || !"OK".equals(h.getStatus())) {
            return List.of();
        }
        Long key = h.getId();
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && now - hit.at() < ttlMillis) {
            return hit.questions();
        }

        List<String> questions;
        try {
            questions = SuggestionService.parseQuestions(llmRouter.chat(buildPrompt(h)), count);
        } catch (RuntimeException e) {
            log.warn("追问生成失败，回落空（前端用静态推荐兜底）: history={} - {}", key, e.getMessage());
            return List.of();   // 失败不缓存，下次重试
        }
        // 去掉与原问题相同的项；空列表不缓存，避免把瞬时失败钉死一个 TTL
        questions.removeIf(q -> q.equals(h.getQuestion()));
        if (questions.isEmpty()) {
            return List.of();
        }
        List<String> immutable = List.copyOf(questions);
        cache.put(key, new CacheEntry(immutable, now));
        return immutable;
    }

    // ---------- Prompt 组装 ----------

    private String buildPrompt(QueryHistory h) {
        String sql = truncate(h.getSqlText(), 1200);
        // 结果快照（columns + 前 1000 行 JSON）：截断即可，追问只需要"结果长什么样"，不需要全量数据
        String snapshot = truncate(h.getResultJson(), 2800);

        String schemaText = safeSchemaText(h);
        String semanticSection = safeSemanticSection(h.getDatasourceId());

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个数据分析助手。用户刚刚对数据库完成了一次提问，下面是本轮的问题、实际执行的 SQL 与查询结果快照。\n")
                .append("请生成 ").append(count)
                .append(" 个用户接下来最可能想追问的问题——要\"接着这轮答案往下钻\"（例如对比、环比/同比、")
                .append("占比、细分维度、Top 排名、异常点解释、时间范围变化），而不是重新问一遍通用榜单。\n\n")
                .append("要求：\n")
                .append("1. 用自然、口语化的中文，与原问题的问法明显不同；\n")
                .append("2. 每个问题都能通过对同一个数据库执行 SQL 回答，可涉及本轮结果中的维度/度量取值；\n")
                .append("3. 不要与原问题重复，也不要与其他追问互相重复；\n")
                .append("4. 每行只输出一个问题，不要编号、不要引号、不要任何解释或多余文字。\n\n")
                .append("用户的问题：").append(truncate(h.getQuestion(), 200)).append('\n');
        if (!sql.isBlank()) {
            prompt.append("\n实际执行的 SQL：\n").append(sql).append('\n');
        }
        if (!snapshot.isBlank()) {
            prompt.append("\n查询结果快照（JSON，可能被截断）：\n").append(snapshot).append('\n');
        }
        if (!schemaText.isBlank()) {
            prompt.append("\n相关表结构：\n").append(schemaText);
        }
        if (semanticSection != null && !semanticSection.isBlank()) {
            prompt.append("\n业务语义（别名/口径/枚举）：\n").append(semanticSection);
        }
        return prompt.toString();
    }

    /** 只注入本轮 SQL 涉及的表结构（best-effort）：匹配不到任何表时取前 4 张，总预算 3000 字符。 */
    private String safeSchemaText(QueryHistory h) {
        try {
            List<TableMeta> schema = dataSourceService.getSchema(h.getDatasourceId());
            if (schema == null || schema.isEmpty()) {
                return "";
            }
            String sqlLower = h.getSqlText() == null ? "" : h.getSqlText().toLowerCase(Locale.ROOT);
            List<TableMeta> matched = new ArrayList<>();
            List<TableMeta> rest = new ArrayList<>();
            for (TableMeta t : schema) {
                if (sqlLower.contains(t.name().toLowerCase(Locale.ROOT))) {
                    matched.add(t);
                } else {
                    rest.add(t);
                }
            }
            List<TableMeta> picked = new ArrayList<>(matched);
            for (TableMeta t : rest) {
                if (picked.size() >= 4) {
                    break;
                }
                picked.add(t);
            }
            StringBuilder sb = new StringBuilder();
            for (TableMeta t : picked) {
                sb.append(t.toSchemaLine()).append('\n');
            }
            return truncate(sb.toString(), 3000);
        } catch (RuntimeException e) {
            log.debug("追问表结构注入跳过: history={} - {}", h.getId(), e.getMessage());
            return "";
        }
    }

    /** 语义段落（best-effort）：复用 {@link SemanticService#buildPromptSection}，截断 2000 字符，失败返回 null。 */
    private String safeSemanticSection(Long datasourceId) {
        try {
            List<TableMeta> schema = dataSourceService.getSchema(datasourceId);
            if (schema == null || schema.isEmpty()) {
                return null;
            }
            List<TableMeta> limited = schema.size() > 8 ? schema.subList(0, 8) : schema;
            String section = semanticService.buildPromptSection(
                    datasourceId, new SchemaSlice(limited, limited.size(), false));
            return truncate(section, 2000);
        } catch (RuntimeException e) {
            log.debug("追问语义段落注入跳过: ds={} - {}", datasourceId, e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private record CacheEntry(List<String> questions, long at) {
    }
}
