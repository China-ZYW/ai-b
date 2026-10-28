package com.aibi.module.chat.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.chat.dto.ChatQueryResponse;
import com.aibi.module.chat.service.ChatService;
import com.aibi.module.chat.sse.SseProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 流式问数接口（M5 / 开发计划 T4.1）：{@code GET /api/chat/query/stream}。
 *
 * <p>相比一次性返回的 {@code POST /api/chat/query}，本接口先建立 SSE 连接，随后由后台线程把核心引擎的
 * 分步进度（加载表结构 → 识别意图 → 生成 SQL → 执行 → 推荐图表）以 {@code event: stage} 实时推送，
 * 最终以 {@code event: result} 推送完整 {@link ChatQueryResponse}；出错则以 {@code event: error} 推送 code+message。
 *
 * <p><b>上下文传播</b>：JWT 过滤器在请求返回后会清理 ThreadLocal，而 SSE 的产出发生在后台线程，
 * 故这里在请求线程内快照完整的 {@link AccessContext}（租户 + 用户 + 角色 + 团队）并在工作线程内重建、
 * 结束后清理，杜绝串租户。不再分别快照 {@code TenantContext}/{@code UserContext}：
 * 那两个门面已合并为单一上下文，分开快照会丢失角色与团队维度。
 * 用 EventSource 时以 GET + 查询参数携带 token 之外的入参；鉴权仍走 Authorization 头（见 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    /** SSE 心跳间隔（毫秒）：长期无事件产出时周期性发 {@code :ping} 注释事件保活连接。 */
    private static final long HEARTBEAT_MS = 15_000L;

    private final ChatService chatService;
    private final ThreadPoolTaskExecutor sseExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final long sseTimeoutMs;

    public ChatStreamController(ChatService chatService,
                                @Qualifier("sseExecutor") ThreadPoolTaskExecutor sseExecutor,
                                @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
                                @Value("${aibi.chat.sse-timeout-ms:600000}") long sseTimeoutMs) {
        this.chatService = chatService;
        this.sseExecutor = sseExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.sseTimeoutMs = sseTimeoutMs;
    }

    /**
     * 流式问数（SSE）。权限点 {@code chat:query:execute} 与同步版 {@link ChatController#query} 同源——
     * 两者是同一能力的两种传输形态，配额消耗一致，不应存在"绕开闸门走流式"的旁路。
     *
     * <p>注解在<b>请求线程</b>内被切面判定（早于 SSE 连接建立与线程池投递），
     * 故此处的 {@link AccessContextHolder} 快照必然发生在鉴权通过之后。
     */
    @GetMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission("chat:query:execute")
    public SseEmitter stream(@RequestParam Long datasourceId,
                             @RequestParam String question,
                             @RequestParam(required = false) String conversationId,
                             @RequestParam(required = false) String skill,
                             @RequestParam(required = false, defaultValue = "false") boolean clarified) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        // 超时不再静默 complete：先尽力推一条 error 事件告知前端，避免"等很久最后什么都没有"
        emitter.onTimeout(() -> {
            log.warn("SSE 问数超时（{}ms），推送超时提示并关闭连接", sseTimeoutMs);
            sendError(emitter, ErrorCode.SYSTEM_ERROR.getCode(), "问数处理超时，请稍后重试或简化问题");
        });
        emitter.onError(e -> log.debug("SSE 连接异常：{}", e.getMessage()));
        // 心跳保活：定期发 SSE 注释事件（:ping），直到连接完成 / 超时 / 出错
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception ignored) {
                // 客户端已断开或 emitter 已完成：发送失败属正常，连接结束时由 onCompletion 取消
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> heartbeat.cancel(false));

        if (question == null || question.isBlank()) {
            sendError(emitter, ErrorCode.PARAM_ERROR.getCode(), "问题不能为空");
            return emitter;
        }

        // 在请求线程内快照完整访问上下文（过滤器返回后会清理 ThreadLocal），传播到后台工作线程
        AccessContext accessContext = AccessContextHolder.get();

        try {
            sseExecutor.execute(() -> run(emitter, accessContext, datasourceId, question, conversationId, skill, clarified));
        } catch (RejectedExecutionException e) {
            log.warn("SSE 任务被拒绝（线程池已满）");
            sendError(emitter, ErrorCode.TOO_MANY_REQUESTS.getCode(), "服务繁忙，请稍后重试");
        }
        return emitter;
    }

    /** 后台工作线程：重建上下文 → 执行问数（带进度推送）→ 推送结果 / 错误 → 清理上下文。 */
    private void run(SseEmitter emitter, AccessContext accessContext,
                     Long datasourceId, String question, String conversationId, String skill, boolean clarified) {
        if (accessContext != null) {
            AccessContextHolder.set(accessContext);
        }
        try {
            SseProgressListener listener = new SseProgressListener(emitter);
            ChatQueryResponse response = chatService.ask(datasourceId, question, conversationId, skill, clarified, listener);
            emitter.send(SseEmitter.event().name("result").data(response));
            emitter.complete();
        } catch (BizException e) {
            sendError(emitter, e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("SSE 问数失败", e);
            sendError(emitter, ErrorCode.SYSTEM_ERROR.getCode(), "问数失败：" + e.getMessage());
        } finally {
            AccessContextHolder.clear();
        }
    }

    private void sendError(SseEmitter emitter, int code, String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("message", message == null ? "" : message);
            emitter.send(SseEmitter.event().name("error").data(payload));
        } catch (Exception e) {
            log.debug("SSE 错误事件推送失败（客户端可能已断开）：{}", e.getMessage());
        } finally {
            emitter.complete();
        }
    }
}
