package com.aibi.module.mcp.controller;

import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.tenant.PlanType;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.mcp.config.McpProperties;
import com.aibi.module.mcp.dto.JsonRpcError;
import com.aibi.module.mcp.service.McpAuthException;
import com.aibi.module.mcp.service.McpProgressSink;
import com.aibi.module.mcp.service.McpRateLimiter;
import com.aibi.module.mcp.service.McpRpcException;
import com.aibi.module.mcp.service.McpServerService;
import com.aibi.module.mcp.service.McpTokenResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.ServletOutputStream;

/**
 * MCP 对外端点：单端点 {@code POST /mcp}（Streamable HTTP）。
 *
 * <p>2026-07-28 起的 <b>stateless core</b>：没有 {@code Mcp-Session-Id}、不在服务端保存任何会话状态，
 * 因此可以跑在普通轮询负载均衡后，<b>不需要 sticky session</b>——这与本项目的无状态 JWT 体系天然契合。
 * {@code initialize} 仍会被处理，但只做<b>协议版本协商</b>与能力声明（按客户端请求的版本回显，兼容新旧 SDK），
 * 不建立会话。每个请求自带身份（PAT / 外部 IdP JWT）。
 *
 * <p>响应形态：单条 JSON-RPC 响应（{@code application/json}）。批处理数组按 JSON-RPC 2.0 逐条处理，
 * 逐条返回（不做 SSE 流——SSE 属旧传输的降级路径，新服务不采用）。
 *
 * <p><b>鉴权在此处完成，不由 Spring Security 代劳</b>：MCP 客户端带的是 PAT 或外部 IdP 签发的 JWT，
 * 都不是本系统登录令牌，交给 {@link com.aibi.common.security.JwtAuthFilter} 只会被判为未认证。
 * 401 必须按 RFC 6750 带 {@code WWW-Authenticate}（客户端据此发现授权服务器）。
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    /** 标记"本请求已自行写出响应"（401 分支），防止外层再覆盖状态码。 */
    private static final String RESPONDED = McpController.class.getName() + ".responded";

    private final McpProperties props;
    private final McpServerService server;
    private final McpTokenResolver resolver;
    private final McpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final TenantMapper tenantMapper;

    public McpController(McpProperties props,
                         McpServerService server,
                         McpTokenResolver resolver,
                         McpRateLimiter rateLimiter,
                         ObjectMapper objectMapper,
                         TenantMapper tenantMapper) {
        this.props = props;
        this.server = server;
        this.resolver = resolver;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.tenantMapper = tenantMapper;
    }

    /**
     * ★Streamable HTTP 规范（2025-03-26）：服务器不提供 GET（SSE 下行流）/DELETE（会话终止）时
     * 必须返回 <b>405</b>，绝不能落空到 Spring 兜成 500——部分客户端（实测豆包）握手阶段会先探测 GET，
     * 收到 500 直接判定服务不可用（2026-09-24「豆包连不上」事故根因之一）。
     */
    @GetMapping("/mcp")
    public void handleGet(HttpServletResponse response) throws IOException {
        methodNotAllowed(response);
    }

    /** 无状态实现没有会话可终止，DELETE 同样按规范回 405。 */
    @DeleteMapping("/mcp")
    public void handleDelete(HttpServletResponse response) throws IOException {
        methodNotAllowed(response);
    }

    private void methodNotAllowed(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setHeader("Allow", "POST");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(json(error(null, -32000,
                "Method Not Allowed：本端点为无状态 Streamable HTTP，仅支持 POST")));
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public void handle(@RequestBody(required = false) String body,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        // 禁止缓存：MCP 响应含租户数据，绝不能进任何共享缓存
        response.setHeader("Cache-Control", "no-store");

        if (!props.isEnabled()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            write(response, 404, json(error(null, JsonRpcError.METHOD_NOT_FOUND, "MCP 服务未启用")));
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            write(response, 200, json(error(null, JsonRpcError.PARSE_ERROR, "请求不是合法 JSON")));
            return;
        }
        if (root == null || root.isMissingNode()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            write(response, 200, json(error(null, JsonRpcError.INVALID_REQUEST, "空请求")));
            return;
        }

        // ★Streamable HTTP 传输协商：客户端 {@code Accept} 含 {@code text/event-stream} 时走 SSE 流式，
        // 可实时下发 notifications/progress——这是解"长问数（实测 200~260s）期间连接空转被代理/客户端掐断、
        // 进而不断重试"根因的核心手段。否则按传统 JSON 单响应返回。
        if (acceptContainsEventStream(request)) {
            handleSse(root, request, response);
        } else {
            handleJson(root, request, response);
        }
    }

    // ==================== JSON 模式（传统单响应）====================

    private void handleJson(JsonNode root, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");

        // 批处理：逐条处理，逐条返回（JSON-RPC 2.0 §6）
        if (root.isArray()) {
            var arr = objectMapper.createArrayNode();
            for (JsonNode item : root) {
                Map<String, Object> res = one(item, request, response, McpProgressSink.NOOP);
                if (request.getAttribute(RESPONDED) != null) {
                    return;   // 401 分支已写出响应，不再追加
                }
                if (res != null) {
                    arr.add(objectMapper.valueToTree(res));
                }
            }
            if (arr.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
                return;
            }
            write(response, 200, arr.toString());
            return;
        }
        Map<String, Object> res = one(root, request, response, McpProgressSink.NOOP);
        if (request.getAttribute(RESPONDED) != null) {
            return;   // 401 分支已写出响应（状态码保持 401）
        }
        if (res == null) {
            // 通知（无 id）：按 JSON-RPC 2.0 不回响应体
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            return;
        }
        write(response, 200, objectMapper.writeValueAsString(res));
    }

    // ==================== SSE 模式（Streamable HTTP 流式）====================

    /**
     * SSE 模式处理入口。
     *
     * <p>★鉴权与限流必须<b>前置</b>到开流之前：一旦写出 {@code text/event-stream} 响应头，
     * 就不能再回一个干净的 JSON 401 了（客户端已按流解析）。故此处先把身份解析好，
     * 失败就按 RFC 6750 回 JSON 401 + {@code WWW-Authenticate}（与 JSON 模式一致），不进入流。
     *
     * <p>流的特性：
     * <ul>
     *   <li>每个 JSON-RPC 响应（结果或协议 error）都是一条 {@code event: message} SSE 事件；</li>
     *   <li>工具执行期间（尤其 ask_data）经 {@link McpProgressSink} 实时下发
     *       {@code notifications/progress} 事件，让客户端进度条走动、连接保持活跃；</li>
     *   <li>独立心跳线程每 15s 发一条 {@code : ping} 注释事件，防 nginx/代理空闲超时掐断长连接；</li>
     *   <li>{@code X-Accel-Buffering: no} 提示 nginx 关闭响应缓冲（配合生产 nginx 的
     *       {@code proxy_buffering off}，否则事件被攒批导致超时）。</li>
     * </ul>
     */
    private void handleSse(JsonNode root, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 1) 鉴权前置
        McpTokenResolver.Identity identity;
        try {
            identity = resolver.resolve(request.getHeader("Authorization"));
        } catch (McpAuthException e) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate",
                    "Bearer realm=\"aibi-mcp\", resource=\"" + props.getResourceUrl() + "\"");
            response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", "",
                    "error", Map.of("code", -32001, "message", e.getMessage()))));
            return;
        }

        // 2) 开 SSE 流（无状态：不设置 Mcp-Session-Id）
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        // 用 ServletOutputStream 而非 PrintWriter：OutputStream.write/flush 会在连接断开时抛 IOException，
        // 心跳线程据此感知断连、及时停发，避免对死连接空转写。
        ServletOutputStream out = response.getOutputStream();
        final Object lock = new Object();
        final AtomicBoolean closed = new AtomicBoolean(false);

        // 3) 心跳：每 15s 一条 : ping 注释事件保活（SSE 注释行以冒号开头，客户端忽略）
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            synchronized (lock) {
                try {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ex) {
                    closed.set(true);
                }
            }
        }, 15, 15, TimeUnit.SECONDS);

        try {
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (closed.get()) {
                        break;
                    }
                    processSseItem(item, request, out, lock, identity, closed);
                }
            } else {
                processSseItem(root, request, out, lock, identity, closed);
            }
        } finally {
            heartbeat.shutdownNow();
            synchronized (lock) {
                try {
                    out.flush();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 处理 SSE 流中的单条 JSON-RPC 请求。
     *
     * <p>与 JSON 模式共享"限流按套餐分级"逻辑；进度令牌来自 {@code params.meta.progressToken}，
     * 有则构造一个把阶段事件写成 {@code notifications/progress} 的 sink，无则 {@link McpProgressSink#NOOP}。
     */
    private void processSseItem(JsonNode req, HttpServletRequest request, ServletOutputStream out, Object lock,
                                McpTokenResolver.Identity identity, AtomicBoolean closed) {
        if (req == null || !req.isObject()) {
            try {
                writeSseEvent(out, lock, error(null, JsonRpcError.INVALID_REQUEST, "请求必须是 JSON-RPC 2.0 对象"));
            } catch (IOException ignored) {
            }
            return;
        }
        JsonNode idNode = req.get("id");
        Object id = idNode == null || idNode.isNull() ? null :
                (idNode.isNumber() ? idNode.asLong() : idNode.asText());
        String method = req.path("method").asText("");

        // 通知（无 id）：按规范不回响应体
        if (id == null) {
            return;
        }

        // 限流：先按用户再按租户（与 JSON 模式一致）
        Long uid = identity.context().uid();
        Long tid = identity.context().tenantId();
        if (uid != null && !rateLimiter.allow("mcp:u:" + uid, resolveMcpRate(tid, true))) {
            try {
                writeSseEvent(out, lock, error(id, -32003, "调用过于频繁（按用户限流 " + resolveMcpRate(tid, true) + " 次/分钟）"));
            } catch (IOException ignored) {
            }
            return;
        }
        if (tid != null && !rateLimiter.allow("mcp:t:" + tid, resolveMcpRate(tid, false))) {
            try {
                writeSseEvent(out, lock, error(id, -32003, "调用过于频繁（按组织限流 " + resolveMcpRate(tid, false) + " 次/分钟）"));
            } catch (IOException ignored) {
            }
            return;
        }

        // 进度令牌 → sink：把引擎阶段映射成 notifications/progress 实时下发
        Object progressToken = extractProgressToken(req);
        McpProgressSink sink = (progressToken == null) ? McpProgressSink.NOOP
                : (tok, stage, progress, message) -> {
                    if (closed.get()) {
                        return;
                    }
                    Map<String, Object> notif = new LinkedHashMap<>();
                    notif.put("jsonrpc", "2.0");
                    notif.put("method", "notifications/progress");
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("progressToken", progressToken);
                    params.put("progress", progress);
                    params.put("total", 100);
                    params.put("message", message == null ? "" : message);
                    notif.put("params", params);
                    try {
                        writeSseEvent(out, lock, notif);
                    } catch (IOException ignored) {
                    }
                };

        TenantContext.set(tid, null);
        AccessContextHolder.set(identity.context());
        try {
            Object result = server.dispatch(method, req.get("params"), identity, sink);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id == null ? "" : id);
            resp.put("result", result == null ? Map.of() : result);
            writeSseEvent(out, lock, resp);
        } catch (McpRpcException e) {
            try {
                writeSseEvent(out, lock, error(id, e.code(), e.getMessage()));
            } catch (IOException ignored) {
            }
        } catch (Exception e) {
            log.warn("MCP SSE 方法执行异常 method={}: {}", method, e.getMessage());
            try {
                writeSseEvent(out, lock, error(id, JsonRpcError.INTERNAL_ERROR, "内部错误：" + e.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    /** 写一条 SSE 事件：固定 {@code event: message}，data 为 JSON-RPC 消息。 */
    private void writeSseEvent(ServletOutputStream out, Object lock, Map<String, Object> payload) throws IOException {
        synchronized (lock) {
            try {
                String data = objectMapper.writeValueAsString(payload);
                out.write(("event: message\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                // 序列化兜底：绝不让一条坏消息把整个流打挂
                out.write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,"
                        + "\"message\":\"序列化失败\"}}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /** 提取客户端在 tools/call 的 {@code params.meta.progressToken}（可为 null）。 */
    private static Object extractProgressToken(JsonNode req) {
        JsonNode tok = req.path("params").path("meta").path("progressToken");
        if (tok.isMissingNode() || tok.isNull()) {
            return null;
        }
        return tok.isNumber() ? tok.asLong() : tok.asText();
    }

    /** 客户端是否请求 SSE 流式：{@code Accept} 头含 {@code text/event-stream} 即视为流式。 */
    private static boolean acceptContainsEventStream(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    /** 处理单条请求；鉴权失败时通过 response 直接写 401 并返回 null（调用方按已响应处理）。 */
    private Map<String, Object> one(JsonNode req, HttpServletRequest request, HttpServletResponse response, McpProgressSink sink)
            throws IOException {
        if (req == null || !req.isObject()) {
            return error(null, JsonRpcError.INVALID_REQUEST, "请求必须是 JSON-RPC 2.0 对象");
        }
        JsonNode idNode = req.get("id");
        Object id = idNode == null || idNode.isNull() ? null :
                (idNode.isNumber() ? idNode.asLong() : idNode.asText());
        String method = req.path("method").asText("");

        // 通知（无 id）：按规范不回响应体
        if (id == null) {
            return null;
        }

        McpTokenResolver.Identity identity;
        try {
            identity = resolver.resolve(request.getHeader("Authorization"));
        } catch (McpAuthException e) {
            // ★必须用请求属性而不是 response.isCommitted() 标记"已响应"：
            // 响应体短小时 Tomcat 尚未 flush，isCommitted() 仍为 false，外层会继续走
            // "res == null → 202" 分支把 401 覆盖掉（客户端因此看不到鉴权失败）。
            request.setAttribute(RESPONDED, Boolean.TRUE);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate",
                    "Bearer realm=\"aibi-mcp\", resource=\"" + props.getResourceUrl() + "\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", id == null ? "" : id,
                    "error", Map.of("code", -32001, "message", e.getMessage()))));
            return null;
        }

        // 限流：先按用户再按租户（Agent 循环很容易把 QPS 打满）；速率按套餐分级（FREE/PRO/ENT）。
        Long uid = identity.context().uid();
        Long tid = identity.context().tenantId();
        if (uid != null && !rateLimiter.allow("mcp:u:" + uid, resolveMcpRate(tid, true))) {
            return error(id, -32003, "调用过于频繁（按用户限流 " + resolveMcpRate(tid, true) + " 次/分钟）");
        }
        if (tid != null && !rateLimiter.allow("mcp:t:" + tid, resolveMcpRate(tid, false))) {
            return error(id, -32003, "调用过于频繁（按组织限流 " + resolveMcpRate(tid, false) + " 次/分钟）");
        }

        TenantContext.set(tid, null);
        AccessContextHolder.set(identity.context());
        try {
            Object result = server.dispatch(method, req.get("params"), identity, sink);
            return Map.of("jsonrpc", "2.0", "id", id == null ? "" : id, "result", result == null ? Map.of() : result);
        } catch (McpRpcException e) {
            return error(id, e.code(), e.getMessage());
        } catch (Exception e) {
            log.warn("MCP 方法执行异常 method={}: {}", method, e.getMessage());
            return error(id, JsonRpcError.INTERNAL_ERROR, "内部错误：" + e.getMessage());
        } finally {
            TenantContext.clear();
            AccessContextHolder.clear();
        }
    }

    /**
     * 按租户套餐解析 MCP 调用速率档（{@code FREE=10 / PRO=60 / ENTERPRISE=300} 次/分钟）。
     * tid 为空或无租户记录时回落到 {@code McpProperties} 默认值，保证限流永不失效。
     */
    private int resolveMcpRate(Long tid, boolean perUser) {
        if (tid == null) {
            return perUser ? props.getRateLimitPerUser() : props.getRateLimitPerTenant();
        }
        Tenant t = tenantMapper.selectById(tid);
        PlanType p = PlanType.parse(t != null ? t.getPlan() : null);
        return perUser ? p.mcpRatePerUser() : p.mcpRatePerTenant();
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        return Map.of("jsonrpc", "2.0", "id", id == null ? "" : id,
                "error", Map.of("code", code, "message", message));
    }

    /** 序列化（此处只用于协议级错误响应，失败只可能是结构异常，故吞掉并给最小可用体）。 */
    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"序列化失败\"}}";
        }
    }

    private void write(HttpServletResponse response, int status, String payload) throws IOException {
        // 401 分支已自行写出，这里只在未被写出的情况下覆盖状态码
        if (!response.isCommitted()) {
            response.setStatus(status);
        }
        response.getWriter().write(payload);
    }
}
