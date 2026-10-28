package com.aibi.module.embed.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.chat.dto.ChatQueryResponse;
import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.service.ChatService;
import com.aibi.module.chat.service.FollowupService;
import com.aibi.module.chat.service.SuggestionService;
import com.aibi.module.datasource.entity.DataSource;
import com.aibi.module.datasource.mapper.DataSourceMapper;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.embed.dto.ChatEmbedVO;
import com.aibi.module.embed.dto.EmbedHistoryItem;
import com.aibi.module.embed.entity.ChatEmbed;
import com.aibi.module.embed.entity.ChatEmbedLog;
import com.aibi.module.embed.mapper.ChatEmbedMapper;
import com.aibi.module.embed.mapper.ChatEmbedLogMapper;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.service.QueryHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 对话问数嵌入服务：把某个数据源以<b>匿名令牌</b>开放给第三方页面嵌入。
 *
 * <p>管理侧（建/列/删）走租户拦截器自动隔离；公开侧凭 {@code token} 匿名访问，
 * 解析出租户与数据源后<b>合成匿名访问上下文</b>，再复用问数主链路 {@link ChatService#ask}。
 *
 * <p>安全边界：
 * <ul>
 *   <li>令牌即能力：只暴露"对该数据源提问"这一件事，不含任何管理面能力；</li>
 *   <li>匿名上下文不带任何角色 → 字段级脱敏按"未授权"处理，敏感列不会返回明文；</li>
 *   <li>可选 {@code allowedOrigins} 限制可被嵌入的站点来源；</li>
 *   <li>任何异常都转为友好失败，绝不把堆栈抛给第三方页面。</li>
 * </ul>
 */
@Service
public class ChatEmbedService {

    private static final Logger log = LoggerFactory.getLogger(ChatEmbedService.class);

    private final ChatEmbedMapper mapper;
    private final ChatService chatService;
    private final DataSourceMapper dataSourceMapper;
    private final SuggestionService suggestionService;
    private final FollowupService followupService;
    private final QueryHistoryService queryHistoryService;
    private final ChatEmbedLogMapper chatEmbedLogMapper;
    private final AuditService auditService;

    public ChatEmbedService(ChatEmbedMapper mapper, ChatService chatService,
                            DataSourceMapper dataSourceMapper, SuggestionService suggestionService,
                            FollowupService followupService, QueryHistoryService queryHistoryService,
                            ChatEmbedLogMapper chatEmbedLogMapper, AuditService auditService) {
        this.mapper = mapper;
        this.chatService = chatService;
        this.dataSourceMapper = dataSourceMapper;
        this.suggestionService = suggestionService;
        this.followupService = followupService;
        this.queryHistoryService = queryHistoryService;
        this.chatEmbedLogMapper = chatEmbedLogMapper;
        this.auditService = auditService;
    }

    /** 列出当前租户的嵌入（租户隔离由拦截器注入）。 */
    public List<ChatEmbed> list() {
        return mapper.selectList(null);
    }

    /**
     * 管理侧列表：把实体转成视图对象——{@code datasourceIds} 解析为真正的列表、并带上数据源真实名称。
     *
     * <p>避免前端拿到逗号串当成数组遍历（数据源列会渲染成碎块且显示不出名称）。
     */
    public List<ChatEmbedVO> listViewos() {
        List<ChatEmbed> rows = list();
        Map<Long, String> names = nameMap(rows);
        List<ChatEmbedVO> out = new ArrayList<>(rows.size());
        for (ChatEmbed e : rows) {
            out.add(toVO(e, names));
        }
        return out;
    }

    /** 单个实体 → 视图对象（创建接口返回用）。 */
    public ChatEmbedVO toVO(ChatEmbed e) {
        return toVO(e, nameMap(List.of(e)));
    }

    /** 创建一个嵌入，返回带 token 的实体。数据源可多选。 */
    public ChatEmbed create(String name, List<Long> datasourceIds, String allowedOrigins, LocalDateTime expireAt) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "至少选择一个数据源");
        }
        List<Long> ids = datasourceIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "至少选择一个数据源");
        }
        ChatEmbed e = new ChatEmbed();
        e.setName(name == null || name.isBlank() ? "对话问数嵌入" : name.trim());
        // 多选：逗号分隔落库，datasource_id 取首个（兼容旧单数据源查询路径）
        e.setDatasourceIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        e.setDatasourceId(ids.get(0));
        e.setAllowedOrigins(allowedOrigins == null ? "" : allowedOrigins.trim());
        e.setExpireAt(expireAt);
        e.setToken(UUID.randomUUID().toString().replace("-", ""));
        e.setEnabled(1);
        e.setViewCount(0);
        e.setUid(currentUid());
        mapper.insert(e);
        log.info("创建对话问数嵌入: id={}, ds={}, tenant={}", e.getId(), ids, TenantContext.tenantId());
        return e;
    }

    /** 停用并删除一条嵌入（撤销后令牌立即失效）。 */
    public void revoke(Long id) {
        mapper.deleteById(id);
    }

    /**
     * 解析公开令牌。校验启用状态与有效期，不通过返回 {@code null}（调用方按"链接无效/已过期"处理）。
     */
    public ChatEmbed resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        ChatEmbed e = mapper.selectByToken(token.trim());
        if (e == null || e.getEnabled() == null || e.getEnabled() != 1) {
            return null;
        }
        if (e.getExpireAt() != null && e.getExpireAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        return e;
    }

    /** 来源校验：{@code allowedOrigins} 留空表示不限制，否则 origin 必须命中其一（忽略大小写）。 */
    public boolean originAllowed(ChatEmbed e, String origin) {
        String allow = e.getAllowedOrigins();
        if (allow == null || allow.isBlank()) {
            return true;
        }
        if (origin == null || origin.isBlank()) {
            return false;
        }
        String o = origin.trim().toLowerCase(Locale.ROOT);
        for (String part : allow.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && o.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 匿名执行一次问数：合成租户上下文后复用主链路。
     *
     * @param origin       第三方页面来源（用于 {@code allowedOrigins} 校验），可为 null
     * @param requestedDs  访客选定的数据源（多选嵌入中切换用）；为空或不在允许列表则取首个允许的数据源。
     *                     安全护栏：仅允许访问本嵌入开放的数据源，杜绝借 token 越权查其它数据源。
     * @param extUser      嵌入方传入的外部用户唯一值（{@code ?user=}，可空）；用于把同一访客的多次问数归并，
     *                     既作为审计留痕（detail 记录），又用于初始化时回看"该用户历史"。
     */
    public ChatQueryResponse query(ChatEmbed e, String question, String conversationId, String origin, Long requestedDs, String extUser) {
        List<Long> allowed = parseDatasourceIds(e);
        Long ds = chooseDatasource(allowed, requestedDs);
        if (ds == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该嵌入未配置可用数据源");
        }
        Long tid = e.getTenantId();
        // ★必须带上 uid：AccessContextHolder.require() 在 uid 为空时直接抛 40001，
        // 若只设 tenantId，进入问数主链路即被判"未认证"。以嵌入创建人作为操作人，
        // 配额与审计也因此归属到创建人；不授予任何角色 → 敏感字段仍按未授权脱敏（保守且安全）。
        Long uid = e.getUid();
        TenantContext.set(tid, null);
        AccessContextHolder.set(AccessContext.builder().tenantId(tid).uid(uid).build());
        try {
            mapper.incrementViewCount(e.getId());
            ChatQueryResponse res = chatService.ask(ds, question, conversationId, null,
                    ProgressListener.NOOP);
            // 外部用户对话留痕（审计 + 历史）：匿名访问没有登录身份，只能靠嵌入方传入的 ext_user 区分访客，
            // 故每次问数都把"用户问句 + 机器人结论"按 (embed_id, ext_user) 落 chat_embed_log，
            // 并在审计 detail 中记 ext_user/origin，便于回溯"哪个嵌入下的哪个外部用户问了什么"。
            // 失败不影响返回；续接多轮时 conversationId 沿用 res 返回的会话 id。
            logTurn(e, extUser, res.conversationId(), "USER", question);
            String botContent = botSummary(res);
            logTurn(e, extUser, res.conversationId(), "BOT", botContent);
            recordEmbedAudit(e, extUser, question, origin, res.conversationId(), botContent);
            return res;
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    /**
     * 某外部用户最近若干轮（最新在前），供嵌入页右侧历史面板初始化展示。
     * {@code extUser} 为空（嵌入方未传）时返回空列表——匿名访问无"用户"可言。
     */
    public List<EmbedHistoryItem> history(ChatEmbed e, String extUser, int limit) {
        String u = normalizeExtUser(extUser);
        if (u == null) {
            return List.of();
        }
        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return chatEmbedLogMapper.recent(e.getTenantId(), e.getId(), u, n).stream()
                .map(EmbedHistoryItem::of)
                .toList();
    }

    /**
     * 某次会话的全部轮次（最旧在前），供点击历史项续接时在主体还原完整对话。
     * {@code extUser} / {@code conversationId} 任一为空均返回空列表。
     */
    public List<EmbedHistoryItem> conversation(ChatEmbed e, String extUser, String conversationId) {
        String u = normalizeExtUser(extUser);
        if (u == null || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return chatEmbedLogMapper.conversation(e.getTenantId(), e.getId(), u, conversationId).stream()
                .map(EmbedHistoryItem::of)
                .toList();
    }

    /** 落一条外部用户对话轮次（USER 问句 / BOT 结论）；失败只告警、绝不反噬主链路。 */
    private void logTurn(ChatEmbed e, String extUser, String conversationId, String role, String content) {
        if (content == null) {
            content = "";
        }
        try {
            ChatEmbedLog row = new ChatEmbedLog();
            row.setTenantId(e.getTenantId());
            row.setEmbedId(e.getId());
            row.setToken(e.getToken());
            row.setExtUser(normalizeExtUser(extUser));
            row.setConversationId(conversationId);
            row.setRole(role);
            row.setContent(content.length() > 4000 ? content.substring(0, 4000) : content);
            row.setCreatedAt(LocalDateTime.now());
            chatEmbedLogMapper.insert(row);
        } catch (RuntimeException ex) {
            log.warn("嵌入对话日志落库失败（不影响返回）: {}", ex.getMessage());
        }
    }

    /** 嵌入问数审计：操作人即嵌入创建人（匿名上下文已置），detail 记外部用户/来源/会话，便于跨租户检索。 */
    private void recordEmbedAudit(ChatEmbed e, String extUser, String question, String origin,
                                  String conversationId, String botContent) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("embed", e.getName());
            detail.put("token", e.getToken());
            detail.put("extUser", extUser == null ? "" : extUser);
            detail.put("origin", origin == null ? "" : origin);
            detail.put("conversationId", conversationId == null ? "" : conversationId);
            detail.put("question", question.length() > 500 ? question.substring(0, 500) : question);
            auditService.record(AuditAction.EMBED_QUERY, AuditAction.RESULT_OK, e.getTenantId(),
                    null, "CHAT_EMBED", e.getToken(), detail);
        } catch (RuntimeException ex) {
            log.warn("嵌入问数审计失败（不影响返回）: {}", ex.getMessage());
        }
    }

    /**
     * 机器人应答文本（package-private 供单测）：优先 AI 解读结论；多意图拆分应答（T12-5）
     * 单结果字段全空、内容在 parts——逐段拼「序号. 子问：摘要/行数/未作答」，避免外部用户
     * 历史留痕只剩一句「已完成查询」丢光各段结论。
     */
    static String botSummary(ChatQueryResponse res) {
        if (res == null) {
            return "已完成查询";
        }
        // ChatQueryResponse / Insight / QueryResult 均为 record：访问器是 xxx() 而非 getXxx()
        if (res.parts() != null && !res.parts().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (com.aibi.module.chat.dto.SubAnswer p : res.parts()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(i++).append(". ").append(p.subQuestion()).append("：");
                if (!p.answered()) {
                    sb.append("未作答（请单独追问）");
                } else if (p.insight() != null && p.insight().summary() != null && !p.insight().summary().isBlank()) {
                    sb.append(p.insight().summary());
                } else {
                    sb.append(p.result() != null ? "已返回 " + p.result().rowCount() + " 行" : "已作答");
                }
            }
            return sb.toString();
        }
        Insight ins = res.insight();
        if (ins != null && ins.summary() != null && !ins.summary().isBlank()) {
            return ins.summary();
        }
        QueryResult r = res.result();
        int rows = r == null ? 0 : r.rowCount();
        return rows > 0 ? ("已返回 " + rows + " 行结果") : "已完成查询";
    }

    /** 外部用户唯一值归一：去空白、超长截断 128；空/空白一律视为"未传"（null）。 */
    private static String normalizeExtUser(String extUser) {
        if (extUser == null) {
            return null;
        }
        String t = extUser.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 128 ? t.substring(0, 128) : t;
    }

    /** 公开元信息用：返回本嵌入开放的数据源 id 列表（兼容仅含单数据源的旧数据）。 */
    public List<Long> datasourceIdList(ChatEmbed e) {
        return parseDatasourceIds(e);
    }

    /**
     * 公开元信息用：返回本嵌入开放的数据源 {@code id → 名称} 映射，按开放列表顺序，仅含真实存在的数据源。
     * 访客页面据此把下拉里的"数据源 1"显示成真实名称（如"销售订单库"）。
     */
    public Map<Long, String> datasourceNames(ChatEmbed e) {
        List<Long> ids = parseDatasourceIds(e);
        if (ids.isEmpty()) {
            return Map.of();
        }
        TenantContext.set(e.getTenantId(), null);
        try {
            List<DataSource> found = dataSourceMapper.selectBatchIds(ids);
            Map<Long, String> map = new LinkedHashMap<>();
            for (Long id : ids) {
                found.stream().filter(d -> id.equals(d.getId())).findFirst()
                        .ifPresent(d -> map.put(id, d.getName()));
            }
            return map;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 公开推荐问法：复用对话页的 LLM 推荐问题生成（依 schema + 语义层，带缓存与逐级降级）。
     * 取本嵌入<b>当前选中的数据源</b>作为语义源——访客在多选嵌入中切换数据源时，样例问法随之变化；
     * {@code datasourceId} 不在白名单或为空时回落首个允许的数据源（与问数 {@code chooseDatasource} 同护栏）。
     * 永不向第三方页面抛错——生成失败回落空列表，由前端以硬编码兜底，保证页面不空白。
     */
    public List<String> suggestQuestions(ChatEmbed e, Long datasourceId) {
        List<Long> ids = parseDatasourceIds(e);
        if (ids.isEmpty()) {
            return List.of();
        }
        Long ds = chooseDatasource(ids, datasourceId);
        Long tid = e.getTenantId();
        Long uid = e.getUid();
        TenantContext.set(tid, null);
        AccessContextHolder.set(AccessContext.builder().tenantId(tid).uid(uid).build());
        try {
            return suggestionService.suggest(ds);
        } catch (RuntimeException ex) {
            log.warn("嵌入推荐问法生成失败，回落空（前端以硬编码兜底）: token={} - {}", e.getToken(), ex.getMessage());
            return List.of();
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    /**
     * 公开上下文追问：依嵌入页某轮问数（{@code query_history} 记录）生成逐轮追问。
     * 安全校验：轮次必须属于本嵌入允许的数据源白名单，且 uid 必须是嵌入创建人
     * （嵌入问数复用 {@code chatService.ask}，历史落库时操作人即 {@code e.getUid()}）——
     * 防止外部访客用猜到的 historyId 探测同租户其他人的问数内容。
     * 永不向第三方页面抛错——失败回落空列表，前端以静态推荐兜底。
     */
    public List<String> followups(ChatEmbed e, Long historyId) {
        List<Long> ids = parseDatasourceIds(e);
        if (ids.isEmpty() || historyId == null) {
            return List.of();
        }
        Long tid = e.getTenantId();
        Long uid = e.getUid();
        TenantContext.set(tid, null);
        AccessContextHolder.set(AccessContext.builder().tenantId(tid).uid(uid).build());
        try {
            QueryHistory h = queryHistoryService.getById(historyId);
            if (h == null || h.getUid() == null || !h.getUid().equals(uid)
                    || h.getDatasourceId() == null || !ids.contains(h.getDatasourceId())) {
                return List.of();
            }
            return followupService.followups(h);
        } catch (RuntimeException ex) {
            log.warn("嵌入追问生成失败，回落空（前端以静态推荐兜底）: token={} - {}", e.getToken(), ex.getMessage());
            return List.of();
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    /** 实体 → 视图对象（附带数据源名称映射）。 */
    private ChatEmbedVO toVO(ChatEmbed e, Map<Long, String> names) {
        List<Long> ids = parseDatasourceIds(e);
        Map<Long, String> scoped = new LinkedHashMap<>();
        for (Long id : ids) {
            String n = names.get(id);
            if (n != null) {
                scoped.put(id, n);
            }
        }
        return new ChatEmbedVO(e.getId(), e.getName(), e.getToken(), e.getDatasourceId(), ids, scoped,
                e.getEnabled(), e.getExpireAt(), e.getAllowedOrigins(), e.getViewCount(), e.getCreatedAt());
    }

    /** 批量取数据源名称：一次查询覆盖传入所有嵌入用到的数据源（管理侧已有租户上下文，无需再设）。 */
    private Map<Long, String> nameMap(List<ChatEmbed> rows) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (ChatEmbed e : rows) {
            ids.addAll(parseDatasourceIds(e));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new LinkedHashMap<>();
        for (DataSource d : dataSourceMapper.selectBatchIds(ids)) {
            if (d.getId() != null) {
                map.put(d.getId(), d.getName());
            }
        }
        return map;
    }

    /** 解析 {@code datasource_ids} 逗号串；缺失时回退到单列的 {@code datasource_id}。 */
    private List<Long> parseDatasourceIds(ChatEmbed e) {
        List<Long> ids = new ArrayList<>();
        String raw = e.getDatasourceIds();
        if (raw != null && !raw.isBlank()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    try {
                        ids.add(Long.parseLong(t));
                    } catch (NumberFormatException ignored) {
                        // 跳过非法片段，保证列表干净
                    }
                }
            }
        }
        if (ids.isEmpty() && e.getDatasourceId() != null) {
            ids.add(e.getDatasourceId());
        }
        return ids;
    }

    /** 在允许列表内优先采用访客选定的数据源，否则取首个（列表为空返回 null）。 */
    private Long chooseDatasource(List<Long> allowed, Long requested) {
        if (requested != null && allowed.contains(requested)) {
            return requested;
        }
        return allowed.isEmpty() ? null : allowed.get(0);
    }

    private Long currentUid() {
        try {
            return com.aibi.common.security.UserContext.userId();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
