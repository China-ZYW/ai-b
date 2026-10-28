package com.aibi.module.mcp.service;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.chat.dto.ChatQueryResponse;
import com.aibi.module.chat.dto.SubAnswer;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.chat.service.ChatService;
import com.aibi.module.dashboard.dto.MetricDataVO;
import com.aibi.module.dashboard.dto.MetricVO;
import com.aibi.module.dashboard.service.MetricService;
import com.aibi.module.datasource.dto.DataSourceVO;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.mcp.config.McpProperties;
import com.aibi.module.rbac.service.PermissionService;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.service.SemanticService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 服务端（协议层）：{@code initialize / tools/list / tools/call / resources/list / resources/read}。
 *
 * <p>《对外 MCP 能力方案》的核心约束在这里落地：
 * <ul>
 *   <li><b>只做协议适配与鉴权，业务能力全复用现有 Service</b>——问数走 {@link ChatService#ask}，
 *       因此只读校验、字段脱敏、质量校验、<b>配额扣减</b>全部自动生效，MCP 不是治理旁路；</li>
 *   <li><b>意图级而非 SQL 级</b>：只给 {@code ask_data} 这类"问一个问题"的工具，
 *       刻意不提供 {@code run_sql}——把外部输入拼进 SQL 是 MCP 最典型的安全事故；</li>
 *   <li><b>权限点校验按调用者真实角色</b>：{@code ask_data} 需 {@code chat:query:execute} 等，
 *       与网页端完全同码，不设"MCP 专用权限"；</li>
 *   <li><b>每次调用写审计</b>，与现有 {@code audit_log} 同源。</li>
 * </ul>
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    /** 本服务实现的 MCP 协议版本（stateless core）。 */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    /**
     * 服务端支持协商的协议版本集。
     *
     * <p>★按 MCP 规范做<b>版本协商</b>而非写死：客户端在 {@code initialize} 的 {@code params.protocolVersion}
     * 里报出它支持的版本，服务端应回显该版本（若在支持集内）。早期写死 {@code 2026-07-28} 导致用旧 SDK
     * （2025-06-18 / 2024-11-05 等）的第三方客户端在握手阶段直接报
     * "Server's protocol version is not supported: 2026-07-28" 而连不上（2026-09-24 故障根因）。
     *
     * <p>本服务的 stateless core 不依赖任何版本专属行为，故"客户端报哪个版本就回哪个版本"永远安全。
     */
    private static final java.util.List<String> SUPPORTED_PROTOCOL_VERSIONS = java.util.List.of(
            "2026-07-28", "2025-06-18", "2025-03-26", "2024-11-05");

    /** 单次问数返回的最大行数：MCP 结果会进模型上下文，必须限流体积。 */
    private static final int MAX_ROWS = 200;
    private static final int DEFAULT_ROWS = 100;

    private final McpProperties props;
    private final ChatService chatService;
    private final DataSourceService dataSourceService;
    private final MetricService metricService;
    private final SemanticService semanticService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public McpServerService(McpProperties props,
                           ChatService chatService,
                           DataSourceService dataSourceService,
                           MetricService metricService,
                           SemanticService semanticService,
                           PermissionService permissionService,
                           AuditService auditService,
                           ObjectMapper objectMapper) {
        this.props = props;
        this.chatService = chatService;
        this.dataSourceService = dataSourceService;
        this.metricService = metricService;
        this.semanticService = semanticService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ==================== 协议入口 ====================

    /**
     * 分发一个 JSON-RPC 方法。
     *
     * @param sink  进度下沉（SSE 模式下实时上报 {@code notifications/progress}；JSON 模式传 {@link McpProgressSink#NOOP}）
     * @throws McpRpcException 参数/方法/权限问题（由控制器转成 JSON-RPC error）
     */
    public Map<String, Object> dispatch(String method, JsonNode params, McpTokenResolver.Identity identity, McpProgressSink sink) {
        if (method == null || method.isBlank()) {
            throw McpRpcException.invalidRequest("缺少 method");
        }
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", tools(identity.context()));
            case "tools/call" -> callTool(params, identity, sink);
            case "resources/list" -> Map.of("resources", resources(identity.context()));
            case "resources/read" -> readResource(params, identity);
            default -> throw McpRpcException.methodNotFound("不支持的方法: " + method);
        };
    }

    /**
     * {@code initialize}：stateless core 下无握手会话，只声明能力与版本。
     *
     * <p>★版本协商采用"<b>客户端报哪个就回哪个</b>"策略：客户端既然在 {@code params.protocolVersion}
     * 里发出某版本，必然支持该版本；本服务 stateless core 不依赖任何版本专属行为，故原样回显永远安全。
     * 这能彻底规避旧实现"写死 2026-07-28 → 旧 SDK 客户端握手报
     * Server's protocol version is not supported"的故障（2026-09-24 豆包连不上根因）。
     */
    private Map<String, Object> initialize(JsonNode params) {
        String requested = params != null && params.has("protocolVersion")
                ? params.get("protocolVersion").asText(null) : null;
        String protocolVersion = negotiateProtocolVersion(requested);
        if (log.isInfoEnabled()) {
            log.info("MCP initialize 协议版本协商: requested={}, negotiated={}", requested, protocolVersion);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("protocolVersion", protocolVersion);
        res.put("serverInfo", Map.of("name", "aibi", "version", "1.0.0"));
        res.put("capabilities", Map.of("tools", Map.of(), "resources", Map.of()));
        // 便于客户端在连接页展示"这是谁"
        res.put("instructions", "用 ask_data 以自然语言查询 aibi 已接入的数据源；"
                + "可先用 list_datasources / list_metrics 定位数据源与已沉淀指标。不提供裸 SQL 执行能力。");
        return res;
    }

    /**
     * 协议版本协商：<b>客户端报哪个版本就回哪个版本</b>。
     *
     * <p>★为何直接回显而非"取支持集交集"：第三方客户端（豆包 / Cursor / 各类旧 SDK）的
     * {@code protocolVersion} 取值不可枚举，若服务端只在"自己认识的几个版本"里挑，一旦客户端发来一个
     * 服务端不认识的版本就只能回落到 {@link #PROTOCOL_VERSION}，而该版本客户端未必支持，于是握手报
     * "Server's protocol version is not supported"。本服务 stateless core 对版本零依赖，
     * 客户端发出即代表它支持，原样回显即可保证任何客户端都能握手成功。仅在客户端完全没传版本时，
     * 才用本服务最新版兜底（此时客户端通常也会接受）。
     */
    private String negotiateProtocolVersion(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return PROTOCOL_VERSION;
    }

    // ==================== tools ====================

    /**
     * 工具清单。
     *
     * <p>按调用者权限裁剪：没权限的工具不出现在清单里——避免模型看到（并尝试调用）它本不该用的能力，
     * 这是方案 §4.2 的"按租户裁剪"在权限维度的落地。
     */
    private List<Map<String, Object>> tools(AccessContext ctx) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (has(ctx, "chat:query:execute")) {
            tools.add(tool("ask_data",
                    "用自然语言查询数据。返回结构化结果（列 + 数据行）与 AI 结论。只读，不会执行写操作。"
                            + "★注意：不传 datasource_id 时只会在【第一个】可见数据源上查询；"
                            + "本系统通常接入了多个业务域数据源（销售/财务/人力等），"
                            + "提问前应先调用 list_datasources 确认数据源清单，"
                            + "问题涉及哪个业务域就在 datasource_id 里显式传入对应 id，"
                            + "否则容易在错误的数据源上找不到字段。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "question", Map.of("type", "string", "description", "自然语言问题，如「上个月的销售额是多少」"),
                                    "datasource_id", Map.of("type", "integer", "description", "数据源 id（清单见 list_datasources）；不传则默认取第一个可见数据源"),
                                    "max_rows", Map.of("type", "integer", "description", "返回行数上限（默认 100，最大 200）")
                            ),
                            "required", List.of("question")
                    )));
        }
        if (has(ctx, "datasource:config:read")) {
            tools.add(tool("list_datasources", "列出当前身份可见的数据源（id + 名称 + 类型），供提问前定位。",
                    Map.of("type", "object", "properties", Map.of())));
        }
        if (has(ctx, "metric:item:read")) {
            tools.add(tool("list_metrics",
                    "列出已沉淀的指标（中文名、口径描述、所属数据源）。鼓励复用已验证口径，而不是重新提问。",
                    Map.of("type", "object", "properties", Map.of(
                            "keyword", Map.of("type", "string", "description", "按名称/别名/描述过滤，可空")
                    ))));
        }
        if (has(ctx, "metric:item:refresh")) {
            tools.add(tool("refresh_metric", "刷新指定指标取最新值（确定性、口径稳定、比重新提问快）。",
                    Map.of("type", "object", "properties", Map.of(
                            "metric_id", Map.of("type", "integer", "description", "指标 id")
                    ), "required", List.of("metric_id"))));
        }
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    /**
     * {@code tools/call}。
     *
     * <p><b>工具执行失败返回 {@code isError=true} 而非抛 JSON-RPC error</b>：SQL 生成失败、指标不存在等
     * 是给模型看的可恢复信息（模型可据此换个问法重试），抛协议错误会让客户端以为整个 MCP 链路坏了。
     */
    private Map<String, Object> callTool(JsonNode params, McpTokenResolver.Identity identity, McpProgressSink sink) {
        String name = text(params, "name");
        if (name == null || name.isBlank()) {
            throw McpRpcException.invalidParams("缺少 name");
        }
        JsonNode args = params == null ? null : params.get("arguments");
        Map<String, Object> payload;
        try {
            payload = switch (name) {
                case "ask_data" -> askData(args, identity, sink);
                case "list_datasources" -> listDatasources(identity);
                case "list_metrics" -> listMetrics(args, identity);
                case "refresh_metric" -> refreshMetric(args, identity);
                default -> throw McpRpcException.invalidParams("未知工具: " + name
                        + "（可用工具见 tools/list；本服务不提供裸 SQL 执行）");
            };
        } catch (McpRpcException e) {
            throw e;
        } catch (com.aibi.common.exception.BizException e) {
            // 配额耗尽 / 权限 / 参数等业务异常：作为"工具级失败"反馈给模型，保留可读原因
            return toolResult(errorText(e.getMessage()), null, true);
        } catch (Exception e) {
            log.warn("MCP 工具执行异常 tool={}: {}", name, e.getMessage());
            return toolResult(errorText("执行失败：" + e.getMessage()), null, true);
        }
        audit(name, identity, args);
        return toolResult(toJson(payload), payload, false);
    }

    private static Map<String, Object> toolResult(String text, Map<String, Object> structured, boolean isError) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("content", List.of(Map.of("type", "text", "text", text)));
        if (structured != null) {
            res.put("structuredContent", structured);
        }
        res.put("isError", isError);
        return res;
    }

    private static String errorText(String message) {
        return "错误：" + message;
    }

    // ==================== 工具实现 ====================

    private Map<String, Object> askData(JsonNode args, McpTokenResolver.Identity identity, McpProgressSink sink) {
        require(identity.context(), "chat:query:execute");
        String question = text(args, "question");
        if (question == null || question.isBlank()) {
            throw McpRpcException.invalidParams("question 不能为空");
        }
        if (question.length() > 500) {
            throw McpRpcException.invalidParams("question 过长（上限 500 字符）");
        }
        int maxRows = intArg(args, "max_rows", DEFAULT_ROWS, 1, MAX_ROWS);
        Long dsId = longArg(args, "datasource_id");

        Long tenantId = identity.context().requireTenantId();
        TenantContext.set(tenantId, null);
        AccessContextHolder.set(identity.context());
        try {
            // 一次查询三用：默认数据源 / 越权校验 / 结果里回带数据源标识（让模型知道查的是哪个库）
            List<DataSourceVO> visible = dataSourceService.listViewos();
            if (visible.isEmpty()) {
                throw McpRpcException.invalidParams("当前身份没有任何可见数据源，无法问数");
            }
            Long ds;
            String dsName = null;
            if (dsId != null) {
                // 显式指定时也必须落在可见范围内：否则 MCP 就成了绕过数据源可见性的通路
                ds = visible.stream().map(DataSourceVO::id).filter(dsId::equals).findFirst().orElse(null);
                if (ds == null) {
                    throw McpRpcException.invalidParams("数据源不可见或不存在: " + dsId);
                }
                dsName = visible.stream().filter(d -> dsId.equals(d.id()))
                        .map(DataSourceVO::name).findFirst().orElse(null);
            } else {
                ds = visible.get(0).id();
                dsName = visible.get(0).name();
            }
            // SSE 模式：把引擎的 15 个阶段回调映射成 0~100 百分比，经 sink 实时上报进度。
            // progressToken 已绑定在 sink 闭包里（由 Controller 按客户端 tools/call 的 meta.progressToken 创建），
            // 这里只关心 (stage, detail)，故 token 传 null。
            ProgressListener listener = new ProgressListener() {
                @Override
                public void onStage(String stage, String detail) {
                    sink.onProgress(null, stage, stageProgress(stage), detail);
                }
            };
            ChatQueryResponse res = chatService.ask(ds, question.trim(), null, null, listener);
            return summarize(question, res, maxRows, ds, dsName);
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    /**
     * 把问数引擎阶段名映射成 0~100 的整数进度，用于 {@code notifications/progress} 的 {@code progress} 字段。
     *
     * <p>各阶段按"典型耗时占比"赋权：理解/生成 SQL/执行是瓶颈，给到更大跨度，避免前端进度条长期卡在 0%。
     * 未知阶段回落到 50，保证不抛错。
     */
    private static int stageProgress(String stage) {
        return switch (stage) {
            case ProgressListener.STAGE_START -> 2;
            case ProgressListener.STAGE_ROUTE -> 8;
            case ProgressListener.STAGE_UNDERSTAND -> 12;
            case ProgressListener.STAGE_GUARD -> 16;
            case ProgressListener.STAGE_SCHEMA -> 24;
            case ProgressListener.STAGE_INTENT -> 34;
            case ProgressListener.STAGE_GENERATE -> 44;
            case ProgressListener.STAGE_CONSISTENCY -> 56;
            case ProgressListener.STAGE_SQL -> 66;
            case ProgressListener.STAGE_EXECUTE -> 76;
            case ProgressListener.STAGE_REPAIR -> 82;
            case ProgressListener.STAGE_CHART_AI -> 86;
            case ProgressListener.STAGE_CHART -> 88;
            case ProgressListener.STAGE_INSIGHT -> 92;
            case ProgressListener.STAGE_VERIFY -> 96;
            case ProgressListener.STAGE_DONE -> 100;
            default -> 50;
        };
    }

    /**
     * 问数结果收敛成"给模型看"的结构：结论 + 质量分 + 有限行数。
     *
     * <p>★结果里必须回带 {@code datasourceId}/{@code datasourceName}：不传 {@code datasource_id} 的调用
     * 默认落在第一个可见数据源，若结果不带"本次查的是哪个库"，模型无法发现自己问错了业务域
     * （2026-09-25 豆包实例：连问两次都落在电商销售库，模型误以为系统只有 orders 一张表）。
     */
    private Map<String, Object> summarize(String question, ChatQueryResponse res, int maxRows,
                                          Long datasourceId, String datasourceName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        out.put("datasourceId", datasourceId);
        if (datasourceName != null) {
            out.put("datasourceName", datasourceName);
        }
        if (res == null) {
            out.put("answered", false);
            out.put("note", "未返回结果");
            return out;
        }
        out.put("conversationId", res.conversationId());
        // 多意图拆分应答（T12-5 验收补网）：复合问的 result 为空、内容全在 parts——不消费的话
        // 模型收到 answered=true 的无内容空壳，会误判查询失败。逐段收敛为结构化摘要（各段再回
        // rows 会让 token 成倍膨胀，故只给 sql/rowCount/summary——模型要的是结论不是明细）。
        if (res.result() == null && res.parts() != null && !res.parts().isEmpty()) {
            out.put("parts", partsDigest(res.parts()));
            out.put("answered", res.parts().stream().anyMatch(SubAnswer::answered));
            out.put("note", "复合问题已拆为多个子问分别作答（见 parts；answered=false 的段为超时/代价拦截未作答）");
            return out;
        }
        if (res.result() != null) {
            var r = res.result();
            out.put("sql", r.sql());
            out.put("columns", r.columns());
            out.put("columnLabels", r.columnLabels());
            // 枚举字典（码值→中文标签）：MCP 的消费方是大模型，让它知道 status=1 是「已支付」，
            // 回答里才不会直接念出裸码值；不在这里替掉数据，原始码值仍照常返回。
            if (r.enumDicts() != null) {
                out.put("enumDicts", r.enumDicts());
            }
            List<List<Object>> rows = new ArrayList<>();
            if (r.rows() != null) {
                for (int i = 0; i < r.rows().size() && i < maxRows; i++) {
                    rows.add(r.rows().get(i));
                }
            }
            out.put("rows", rows);
            out.put("rowCount", r.rowCount());
            out.put("truncated", r.rows() != null && r.rows().size() > maxRows);
            out.put("executionTimeMs", r.executionTimeMs());
        }
        if (res.insight() != null) {
            out.put("summary", res.insight().summary());
            out.put("keyPoints", res.insight().keyPoints());
            out.put("anomalies", res.insight().anomalies());
        }
        if (res.verify() != null) {
            out.put("qualityScore", res.verify().score());
        }
        out.put("answered", true);
        return out;
    }

    /**
     * 多意图 parts 摘要（纯函数，package-private 供单测）：逐子问输出
     * {@code subQuestion / answered / sql? / rowCount? / summary?}，供外部大模型理解分段作答结果。
     */
    static List<Map<String, Object>> partsDigest(List<SubAnswer> parts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SubAnswer p : parts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subQuestion", p.subQuestion());
            m.put("answered", p.answered());
            QueryResult r = p.result();
            if (r != null) {
                m.put("sql", r.sql());
                m.put("rowCount", r.rowCount());
            }
            if (p.insight() != null && p.insight().summary() != null && !p.insight().summary().isBlank()) {
                m.put("summary", p.insight().summary());
            }
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> listDatasources(McpTokenResolver.Identity identity) {
        require(identity.context(), "datasource:config:read");
        TenantContext.set(identity.context().requireTenantId(), null);
        AccessContextHolder.set(identity.context());
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (DataSourceVO d : dataSourceService.listViewos()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.id());
                m.put("name", d.name());
                m.put("type", d.type());
                m.put("database", d.database());
                items.add(m);
            }
            return Map.of("datasources", items);
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    private Map<String, Object> listMetrics(JsonNode args, McpTokenResolver.Identity identity) {
        require(identity.context(), "metric:item:read");
        String keyword = text(args, "keyword");
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        TenantContext.set(identity.context().requireTenantId(), null);
        AccessContextHolder.set(identity.context());
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (MetricVO m : metricService.list()) {
                if (!kw.isEmpty() && !matches(m, kw)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", m.id());
                item.put("name", m.name());
                item.put("alias", m.alias());
                item.put("description", m.description());
                item.put("datasourceId", m.datasourceId());
                item.put("refreshedAt", String.valueOf(m.refreshedAt()));
                items.add(item);
            }
            return Map.of("metrics", items);
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    private Map<String, Object> refreshMetric(JsonNode args, McpTokenResolver.Identity identity) {
        require(identity.context(), "metric:item:refresh");
        Long metricId = longArg(args, "metric_id");
        if (metricId == null) {
            throw McpRpcException.invalidParams("metric_id 不能为空");
        }
        TenantContext.set(identity.context().requireTenantId(), null);
        AccessContextHolder.set(identity.context());
        try {
            MetricDataVO data = metricService.refresh(metricId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("metricId", data.metricId());
            out.put("name", data.name());
            if (data.result() != null) {
                var r = data.result();
                out.put("sql", r.sql());
                out.put("columns", r.columns());
                List<List<Object>> rows = new ArrayList<>();
                if (r.rows() != null) {
                    for (int i = 0; i < r.rows().size() && i < DEFAULT_ROWS; i++) {
                        rows.add(r.rows().get(i));
                    }
                }
                out.put("rows", rows);
                out.put("rowCount", r.rowCount());
            }
            return out;
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    // ==================== resources ====================

    private List<Map<String, Object>> resources(AccessContext ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(resource("aibi://faq", "常见问法示例",
                "aibi 问数的常见问法与最佳实践，供模型学习如何提问。", "text/plain"));
        if (has(ctx, "metric:item:read")) {
            TenantContext.set(ctx.requireTenantId(), null);
            AccessContextHolder.set(ctx);
            try {
                for (MetricVO m : metricService.list()) {
                    list.add(resource("metric://" + m.id(),
                            m.name() == null ? ("指标 " + m.id()) : m.name(),
                            m.description() == null ? "指标定义与口径" : m.description(),
                            "application/json"));
                }
            } finally {
                TenantContext.clear();
                AccessContextHolder.clear();
            }
        }
        if (has(ctx, "datasource:schema:read")) {
            TenantContext.set(ctx.requireTenantId(), null);
            AccessContextHolder.set(ctx);
            try {
                for (DataSourceVO d : dataSourceService.listViewos()) {
                    list.add(resource("datasource://" + d.id() + "/schema",
                            "数据源结构：" + d.name(),
                            "该数据源的表/字段与业务别名（语义层）",
                            "application/json"));
                }
            } finally {
                TenantContext.clear();
                AccessContextHolder.clear();
            }
        }
        return list;
    }

    private static Map<String, Object> resource(String uri, String name, String description, String mime) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("uri", uri);
        r.put("name", name);
        r.put("description", description);
        r.put("mimeType", mime);
        return r;
    }

    /**
     * {@code resources/read}。
     *
     * <p><b>resources 同样受权限约束</b>：MCP 的 resources 会被客户端直接塞进上下文，
     * 若不受权限约束就成了"绕过工具鉴权读数据"的侧门。
     */
    private Map<String, Object> readResource(JsonNode params, McpTokenResolver.Identity identity) {
        String uri = text(params, "uri");
        if (uri == null || uri.isBlank()) {
            throw McpRpcException.invalidParams("缺少 uri");
        }
        if ("aibi://faq".equals(uri)) {
            return Map.of("contents", List.of(Map.of(
                    "uri", uri, "mimeType", "text/plain", "text", faq())));
        }
        if (uri.startsWith("metric://")) {
            require(identity.context(), "metric:item:read");
            Long id = parseId(uri.substring("metric://".length()));
            TenantContext.set(identity.context().requireTenantId(), null);
            AccessContextHolder.set(identity.context());
            try {
                MetricVO m = metricService.get(id);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", m.id());
                body.put("name", m.name());
                body.put("alias", m.alias());
                body.put("description", m.description());
                body.put("definition", m.definition());
                body.put("sql", m.sql());
                body.put("datasourceId", m.datasourceId());
                body.put("refreshedAt", String.valueOf(m.refreshedAt()));
                return contents(uri, toJson(body));
            } finally {
                TenantContext.clear();
                AccessContextHolder.clear();
            }
        }
        if (uri.startsWith("datasource://")) {
            require(identity.context(), "datasource:schema:read");
            String rest = uri.substring("datasource://".length());
            int slash = rest.indexOf('/');
            Long id = parseId(slash > 0 ? rest.substring(0, slash) : rest);
            TenantContext.set(identity.context().requireTenantId(), null);
            AccessContextHolder.set(identity.context());
            try {
                List<SemanticMeta> meta = semanticService.list(id);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("datasourceId", id);
                List<Map<String, Object>> tables = new ArrayList<>();
                Map<String, Map<String, Object>> byTable = new LinkedHashMap<>();
                for (SemanticMeta s : meta) {
                    String table = s.getTableName() == null ? "" : s.getTableName();
                    Map<String, Object> t = byTable.computeIfAbsent(table, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("table", k);
                        m.put("columns", new ArrayList<Map<String, Object>>());
                        return m;
                    });
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column", s.getColumnName());
                    col.put("alias", s.getAliasCn());
                    col.put("description", s.getDescription());
                    col.put("role", s.getColumnRole());
                    // 敏感列只标注"是否敏感"，不在此处展开可见角色等治理细节
                    col.put("sensitive", s.getSensitive() != null && s.getSensitive() == 1);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> cols = (List<Map<String, Object>>) t.get("columns");
                    cols.add(col);
                }
                tables.addAll(byTable.values());
                body.put("tables", tables);
                return contents(uri, toJson(body));
            } finally {
                TenantContext.clear();
                AccessContextHolder.clear();
            }
        }
        throw McpRpcException.invalidParams("不支持的资源 uri: " + uri);
    }

    private static Map<String, Object> contents(String uri, String text) {
        return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "application/json", "text", text)));
    }

    /** 常见问法：与前端兜底样例同口径，帮外部模型学会"怎么问"。 */
    private static String faq() {
        return """
                aibi 问数常见问法示例：
                1. 聚合：「上个月的销售额是多少」「总订单量」
                2. 分组：「按品类统计销售额」
                3. 排序：「销售额最高的前 10 个客户」
                4. 趋势：「近 6 个月的销售趋势」
                5. 占比：「各渠道订单占比」
                6. 对比/同环比：「本月销售额同比去年增长多少」

                最佳实践：
                - 先用 list_metrics 看看有没有已沉淀的指标，有则优先用 refresh_metric（口径稳定且快）。
                - 提问尽量带上时间范围与分组维度，避免歧义。
                - 本服务只做只读查询，不提供裸 SQL 执行。
                """;
    }

    // ==================== 辅助 ====================

    private void require(AccessContext ctx, String... codes) {
        if (!permissionService.hasAll(ctx, codes)) {
            throw McpRpcException.invalidParams("当前身份缺少权限：" + String.join(", ", codes)
                    + "（MCP 与网页端同权限，不做例外放行）");
        }
    }

    private boolean has(AccessContext ctx, String... codes) {
        return permissionService.hasAll(ctx, codes);
    }

    private static boolean matches(MetricVO m, String kw) {
        return contains(m.name(), kw) || contains(m.alias(), kw) || contains(m.description(), kw);
    }

    private static boolean contains(String v, String kw) {
        return v != null && v.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long longArg(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return v.asLong();
        } catch (Exception e) {
            throw McpRpcException.invalidParams(field + " 不是合法整数");
        }
    }

    private static int intArg(JsonNode node, String field, int def, int min, int max) {
        Long v = longArg(node, field);
        if (v == null) {
            return def;
        }
        return (int) Math.max(min, Math.min(max, v));
    }

    private static Long parseId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            throw McpRpcException.invalidParams("uri 中的 id 不是合法数字: " + raw);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    /**
     * 工具调用审计：谁、哪个组织、用什么凭据、调了什么。
     *
     * <p>★必须在此处重建访问上下文：各工具实现都在自己的 {@code finally} 里清了
     * {@code AccessContextHolder}，而审计在工具返回之后执行——若不重建，
     * {@code AuditService} 取不到 uid，审计行会失去"谁调用的"这一最关键字段。
     */
    private void audit(String tool, McpTokenResolver.Identity identity, JsonNode args) {
        TenantContext.set(identity.context().tenantId(), null);
        AccessContextHolder.set(identity.context());
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("tool", tool);
            detail.put("method", identity.method().name());
            detail.put("subject", identity.subject());
            String q = text(args, "question");
            if (q != null && !q.isBlank()) {
                detail.put("question", q.length() > 500 ? q.substring(0, 500) : q);
            }
            Long metricId = longArg(args, "metric_id");
            if (metricId != null) {
                detail.put("metricId", metricId);
            }
            auditService.record(AuditAction.MCP_TOOL_CALL, AuditAction.RESULT_OK,
                    identity.context().tenantId(), identity.context().uid(),
                    "MCP", tool, detail);
        } catch (RuntimeException e) {
            log.warn("MCP 工具调用审计失败（不影响返回）: {}", e.getMessage());
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    /** 当前服务对外声明的 resource（供 well-known 元数据复用）。 */
    public String resourceUrl() {
        return props.getResourceUrl();
    }
}
