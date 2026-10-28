package com.aibi.module.audit.nlquery;

import com.aibi.common.exception.BizException;
import com.aibi.common.response.PageResult;
import com.aibi.module.audit.dto.AuditLogVO;
import com.aibi.module.audit.dto.AuditQuery;
import com.aibi.module.audit.dto.OperationLogVO;
import com.aibi.module.audit.dto.OperationQuery;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.audit.service.OperationLogService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审计 NL 查询三源融合服务（T11-3 K2）。
 *
 * <p><b>确定性组装，零 SQL 生成</b>：输入是清洗过的 {@link AuditQuerySlots}，输出是把
 * auth_audit_log / audit_log(操作日志) / query_history 三源各走<b>既有参数化检索</b>
 * （或新增的显式列投影 Mapper）后归并的统一投影行——本类不拼任何 SQL 文本。
 *
 * <p><b>越权口径</b>：{@code tenantId} 由调用方钉死（组织域=当前租户；平台域=null 跨租户），
 * query_history 跨用户检索只取显式投影列，{@code result_json} 快照只在服务端折叠成
 * {@link #resultSummary} 摘要（行数/列名/首行前 3 值示例），原始内容不出服务端。
 *
 * <p><b>点名解析不到人时该源返空而非忽略条件</b>：用户说了"张三"，检索却返回全员的行，
 * 比返回空行更具误导性——宁缺勿错（三源各自独立判定，OPS 源自有 username LIKE 不受此限）。
 */
@Service
public class AuditNlQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditNlQueryService.class);

    /** 融合行硬上限（与审计检索 MAX_PAGE_SIZE 同口径）。 */
    public static final int ROW_CAP = 200;
    /** 点名人名最多解析到的 uid 数（防常见姓氏爆炸）。 */
    static final int PERSON_UID_LIMIT = 5;

    private final AuditService auditService;
    private final OperationLogService operationLogService;
    private final QueryHistoryMapper queryHistoryMapper;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final ObjectMapper objectMapper;
    private final LlmRouter llm;
    private final AibiConfig aibiConfig;

    public AuditNlQueryService(AuditService auditService,
                               OperationLogService operationLogService,
                               QueryHistoryMapper queryHistoryMapper,
                               UserMapper userMapper,
                               TenantMapper tenantMapper,
                               ObjectMapper objectMapper,
                               LlmRouter llm,
                               AibiConfig aibiConfig) {
        this.auditService = auditService;
        this.operationLogService = operationLogService;
        this.queryHistoryMapper = queryHistoryMapper;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.objectMapper = objectMapper;
        this.llm = llm;
        this.aibiConfig = aibiConfig;
    }

    // ==================== 编排（T11-3 K3）：门控 → LLM 抽槽 → 规则时间盖写 → 清洗 → 融合 ====================

    /**
     * 审计智能查询主入口：一句话 → 槽位 → 三源融合行。
     *
     * <p><b>降级链</b>：LLM 未配置/异常/指定模型不在链上且默认链也失败/输出不可解析 →
     * {@code degraded} 响应（HTTP 仍 200），绝不 500、绝不阻断审计页的人工筛选表单；
     * 时间短语规则解析在 LLM 之前且命中即盖写 LLM 的时间槽（确定性优先，见设计稿 §4.1）。
     *
     * @param tenantId 组织域钉死当前租户；平台域传 null 跨租户
     */
    public AuditNlResult nlQuery(String question, Long tenantId) {
        if (!aibiConfig.auditNlQueryEnabled()) {
            throw new BizException(com.aibi.common.exception.ErrorCode.FORBIDDEN,
                    "审计智能查询未启用（AUDIT / nl-query），请管理员在配置管理中开启后使用");
        }
        LocalDateTime now = LocalDateTime.now();
        AuditQuerySlots parsed = parseSlots(question, now);
        if (parsed == null) {
            return AuditNlResult.degradedResult();
        }
        AuditTimePhrases.Window rule = AuditTimePhrases.parse(question, now);
        if (rule != null) {
            parsed = withRuleTime(parsed, rule);
        }
        AuditQuerySlots slots = AuditSlotsSanitizer.sanitize(parsed);
        List<AuditNlRow> rows = fuse(slots, tenantId);
        return new AuditNlResult(slots, false, rows.size(), rows);
    }

    /** LLM 抽槽：任何环节拿不到可用 JSON 一律 null（调用方降级），全程不抛出。 */
    private AuditQuerySlots parseSlots(String question, LocalDateTime now) {
        try {
            String prompt = AuditNlPrompts.build(question, now);
            String model = aibiConfig.auditNlQueryModel();
            String out = null;
            if (model != null && !model.isBlank()) {
                // 指定模型行（nl-query-model）；未命中时 chatWithModel 返回 null，回落默认链
                out = llm.chatWithModel(prompt, model);
            }
            if (out == null) {
                out = llm.chat(prompt);
            }
            String json = extractJsonObject(out);
            if (json == null) {
                log.warn("审计 NL 解析输出不含 JSON 对象，降级（len={}）", out == null ? -1 : out.length());
                return null;
            }
            return objectMapper.readValue(json, AuditQuerySlots.class);
        } catch (Exception e) {
            log.warn("审计 NL 解析失败，静默降级：{}", e.getMessage());
            return null;
        }
    }

    /** 剥代码围栏后取首个 '{' 到末个 '}' 的对象子串；不含对象返回 null。 */
    static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.replace("```json", "").replace("```", "").trim();
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        return (s >= 0 && e > s) ? t.substring(s, e + 1) : null;
    }

    /** 规则时间盖写 LLM 时间槽（确定性优先；record 无 withX，重建一份）。 */
    private static AuditQuerySlots withRuleTime(AuditQuerySlots s, AuditTimePhrases.Window w) {
        return new AuditQuerySlots(w.fromText(), w.toText(), s.username(), s.action(), s.result(),
                s.module(), s.keyword(), s.sources(), s.note());
    }

    /**
     * 按槽位融合三源（时间倒序、{@link #ROW_CAP} 截断）。
     *
     * @param tenantId 组织域钉死当前租户；平台域传 null 跨租户检索
     */
    public List<AuditNlRow> fuse(AuditQuerySlots slots, Long tenantId) {
        LocalDateTime from = parseTs(slots.timeFrom());
        LocalDateTime to = parseTs(slots.timeTo());
        Set<String> sources = normalizeSources(slots.sources());

        // 点名解析（username/nickname LIKE → uid 集合）：AUDIT/QUERY 源按 uid 过滤共用
        List<Long> uids = null;
        if (slots.username() != null && !slots.username().isBlank()) {
            uids = resolveUids(slots.username(), tenantId);
            if (uids.isEmpty()) {
                log.debug("审计 NL 查询点名「{}」未解析到本域用户，相关源返回空", slots.username());
            }
        }
        boolean personUnresolved = uids != null && uids.isEmpty();

        List<AuditNlRow> rows = new ArrayList<>();
        if (sources.contains(AuditNlDictionary.SOURCE_AUDIT)) {
            rows.addAll(auditRows(slots, tenantId, from, to, uids, personUnresolved));
        }
        if (sources.contains(AuditNlDictionary.SOURCE_OPS)) {
            rows.addAll(opsRows(slots, tenantId, from, to));
        }
        if (sources.contains(AuditNlDictionary.SOURCE_QUERY)) {
            rows.addAll(queryRows(tenantId, from, to, slots, uids, personUnresolved));
        }
        rows.sort((a, b) -> {
            if (a.occurredAt() == null) return b.occurredAt() == null ? 0 : 1;
            if (b.occurredAt() == null) return -1;
            return b.occurredAt().compareTo(a.occurredAt());
        });
        return rows.size() > ROW_CAP ? new ArrayList<>(rows.subList(0, ROW_CAP)) : rows;
    }

    // ==================== AUDIT：权限审计事件（复用 AuditService.search） ====================

    private List<AuditNlRow> auditRows(AuditQuerySlots slots, Long tenantId, LocalDateTime from,
                                       LocalDateTime to, List<Long> uids, boolean personUnresolved) {
        if (personUnresolved) {
            return List.of();
        }
        List<AuditNlRow> rows = new ArrayList<>();
        // uid 精确过滤是多值的，而既有 AuditQuery 只支持单 uid：逐个查（上限 PERSON_UID_LIMIT）后归并
        // （List.of 不容 null，无条件查询用 singletonList(null)）
        List<Long> targets = uids == null ? java.util.Collections.singletonList((Long) null)
                : uids.subList(0, Math.min(uids.size(), PERSON_UID_LIMIT));
        for (Long uid : targets) {
            PageResult<AuditLogVO> page = auditService.search(
                    new AuditQuery(tenantId, slots.action(), uid, slots.result(), from, to, ROW_CAP, 0));
            for (AuditLogVO v : page.items()) {
                rows.add(new AuditNlRow(v.createdAt(), AuditNlDictionary.SOURCE_AUDIT,
                        v.tenantId(), v.tenantName(), v.username(),
                        auditSummary(v), v.action(), v.result(), null));
            }
        }
        return rows;
    }

    private static String auditSummary(AuditLogVO v) {
        StringBuilder sb = new StringBuilder(v.action() == null ? "事件" : v.action());
        if (v.resourceType() != null && !v.resourceType().isBlank()) {
            sb.append(' ').append(v.resourceType());
            if (v.resourceId() != null && !v.resourceId().isBlank()) {
                sb.append('#').append(v.resourceId());
            }
        }
        if (v.targetUsername() != null && !v.targetUsername().isBlank()) {
            sb.append(" → 作用于 ").append(v.targetUsername());
        }
        return sb.toString();
    }

    // ==================== OPS：操作日志（复用 OperationLogService.search） ====================

    private List<AuditNlRow> opsRows(AuditQuerySlots slots, Long tenantId, LocalDateTime from,
                                     LocalDateTime to) {
        // module 槽不进 OPS 精确过滤：库内 module 是 deriveModule(uri) 派生的英文码，
        // LLM 给的中文模糊词精确匹配必空、误导结果；module 仅回显供前端筛选表单沿用，
        // 对象词走 keyword → uri LIKE（OperationLogMapper 原生模糊）。
        PageResult<OperationLogVO> page = operationLogService.search(new OperationQuery(
                tenantId, null, null, slots.keyword(), slots.username(), from, to, ROW_CAP, 0));
        List<AuditNlRow> rows = new ArrayList<>();
        for (OperationLogVO v : page.items()) {
            String status = v.httpStatus() == null ? null : String.valueOf(v.httpStatus());
            rows.add(new AuditNlRow(v.createdAt(), AuditNlDictionary.SOURCE_OPS,
                    v.tenantId(), v.tenantName(), v.username(),
                    (v.method() == null ? "" : v.method() + " ") + nvl(v.uri())
                            + " → " + nvl(status) + " (" + (v.durationMs() == null ? "-" : v.durationMs()) + "ms)",
                    v.method(), status, null));
        }
        return rows;
    }

    // ==================== QUERY：问数历史跨用户投影（新增显式列 Mapper） ====================

    private List<AuditNlRow> queryRows(Long tenantId, LocalDateTime from, LocalDateTime to,
                                       AuditQuerySlots slots, List<Long> uids, boolean personUnresolved) {
        if (personUnresolved) {
            return List.of();
        }
        List<QueryHistory> histories = queryHistoryMapper.selectAuditNlProjection(
                tenantId, from, to, slots.keyword(), uids, ROW_CAP);
        if (histories.isEmpty()) {
            return List.of();
        }
        // 投影行只有 uid/tenant_id：展示名批量补齐（一次 selectBatchIds，避免 N+1，照 AuditService 口径）
        Map<Long, String> userNames = loadUserNames(histories);
        Map<Long, String> tenantNames = tenantId == null ? loadTenantNames(histories) : Map.of();
        List<AuditNlRow> rows = new ArrayList<>();
        for (QueryHistory h : histories) {
            String who = h.getUid() == null ? null : userNames.get(h.getUid());
            rows.add(new AuditNlRow(h.getCreatedAt(), AuditNlDictionary.SOURCE_QUERY,
                    h.getTenantId(), tenantNames.get(h.getTenantId()),
                    who == null ? (h.getUid() == null ? "匿名" : "#" + h.getUid()) : who,
                    "[" + nvl(h.getSkill()) + "] " + nvl(h.getQuestion()),
                    h.getSkill(), h.getStatus(), resultSummary(h)));
        }
        return rows;
    }

    private Map<Long, String> loadUserNames(List<QueryHistory> histories) {
        Set<Long> uids = new HashSet<>();
        for (QueryHistory h : histories) {
            if (h.getUid() != null) {
                uids.add(h.getUid());
            }
        }
        Map<Long, String> map = new HashMap<>();
        if (!uids.isEmpty()) {
            for (User u : userMapper.selectBatchIds(uids)) {
                map.put(u.getId(), u.getNickname() != null && !u.getNickname().isBlank()
                        ? u.getNickname() : u.getUsername());
            }
        }
        return map;
    }

    private Map<Long, String> loadTenantNames(List<QueryHistory> histories) {
        Set<Long> tids = new HashSet<>();
        for (QueryHistory h : histories) {
            if (h.getTenantId() != null && h.getTenantId() != 0L) {
                tids.add(h.getTenantId());
            }
        }
        Map<Long, String> map = new HashMap<>();
        if (!tids.isEmpty()) {
            for (Tenant t : tenantMapper.selectBatchIds(tids)) {
                map.put(t.getId(), t.getName());
            }
        }
        return map;
    }

    /**
     * 问数结果快照摘要（拍板 P2）：{@code result_json} → "共 N 行 × M 列（c1,c2,c3…）；例： v1 | v2 | v3"。
     *
     * <p>行数取落库 {@code row_count}（快照本身截前 1000 行，总数以列为准）；示例值只取<b>首行前 3 列</b>、
     * 每值截 32 字符。老记录无快照、或快照 JSON 不可解析 → null（<b>不重跑 SQL</b>，宁缺勿错）。
     * 原始快照内容只在服务端折叠，绝不下发。
     */
    String resultSummary(QueryHistory h) {
        if (h.getResultJson() == null || h.getResultJson().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(h.getResultJson());
            JsonNode columns = node.path("columns");
            if (!columns.isArray() || columns.isEmpty()) {
                return null;
            }
            List<String> colNames = new ArrayList<>();
            for (JsonNode c : columns) {
                colNames.add(c.asText());
            }
            StringBuilder sb = new StringBuilder("共 ").append(h.getRowCount() == null ? "?" : h.getRowCount())
                    .append(" 行 × ").append(colNames.size()).append(" 列（")
                    .append(String.join("，", colNames.subList(0, Math.min(3, colNames.size()))));
            if (colNames.size() > 3) {
                sb.append("…");
            }
            sb.append('）');
            JsonNode rows = node.path("rows");
            if (rows.isArray() && !rows.isEmpty()) {
                JsonNode first = rows.get(0);
                List<String> vals = new ArrayList<>();
                if (first.isArray()) {
                    for (int i = 0; i < Math.min(3, first.size()); i++) {
                        vals.add(truncate(first.get(i).asText(), 32));
                    }
                }
                if (!vals.isEmpty()) {
                    sb.append("；例： ").append(String.join(" | ", vals));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // 快照坏损不影响出行为主信息（fail-soft，与历史回放同口径）
            log.debug("历史[{}] 快照摘要生成失败（忽略）：{}", h.getId(), e.getMessage());
            return null;
        }
    }

    // ==================== 工具 ====================

    /** 点名 → 本域 uid 集合（username/nickname LIKE，上限 10 候选）。 */
    List<Long> resolveUids(String username, Long tenantId) {
        QueryWrapper<User> w = new QueryWrapper<User>()
                .select("id")
                .and(x -> x.like("username", username).or().like("nickname", username))
                .last("LIMIT 10");
        if (tenantId != null) {
            w.eq("tenant_id", tenantId); // app_user 在 IGNORE 名单，租户条件必须显式带
        }
        List<Long> ids = new ArrayList<>();
        for (User u : userMapper.selectList(w)) {
            if (u.getId() != null) {
                ids.add(u.getId());
            }
        }
        return ids;
    }

    private static Set<String> normalizeSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return Set.of(AuditNlDictionary.SOURCE_AUDIT, AuditNlDictionary.SOURCE_OPS,
                    AuditNlDictionary.SOURCE_QUERY);
        }
        Set<String> set = new HashSet<>(sources);
        set.retainAll(AuditNlDictionary.SOURCES);
        return set.isEmpty() ? Set.of(AuditNlDictionary.SOURCE_AUDIT, AuditNlDictionary.SOURCE_OPS,
                AuditNlDictionary.SOURCE_QUERY) : set;
    }

    /** 槽位时间串解析（清洗器已验格式，这里失败只视为无条件，绝不抛出）。 */
    private static LocalDateTime parseTs(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(ts, AuditTimePhrases.TS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    /** 示例值截断：null → 空串，超长截 32 字符加省略号（控摘要体量）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
