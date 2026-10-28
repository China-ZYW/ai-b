package com.aibi.module.embed.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.module.chat.dto.ChatQueryResponse;
import com.aibi.module.embed.dto.EmbedHistoryItem;
import com.aibi.module.embed.entity.ChatEmbed;
import com.aibi.module.embed.service.ChatEmbedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话问数嵌入的<b>公开</b>访问接口（免登录）：第三方页面凭 {@code token} 匿名提问。
 *
 * <p>位于 {@code /api/public/**}，已在 {@code SecurityConfig} 放行，故不加 {@code @RequiresPermission}。
 * 与分享"快照"不同，这里返回<b>实时</b>问数结果：合成匿名访问上下文后复用问数主链路。
 *
 * <p>失败一律返回友好文案（令牌无效/过期、来源不允许、问题为空），不向第三方页面抛堆栈。
 */
@RestController
@RequestMapping("/api/public/chat-embed")
public class PublicChatEmbedController {

    private static final Logger log = LoggerFactory.getLogger(PublicChatEmbedController.class);

    private final ChatEmbedService service;

    public PublicChatEmbedController(ChatEmbedService service) {
        this.service = service;
    }

    /** 匿名提问请求。{@code conversationId} 用于多轮连续追问；{@code datasourceId} 为多选嵌入中切换的数据源（可空）；
     *  {@code user} 为嵌入方传入的外部用户唯一值（可空），用于把同一访客的问数归并到其名下（审计 + 历史）。 */
    public record QueryForm(String question, String conversationId, Long datasourceId, String user) {
    }

    /** 嵌入元信息：用于第三方页面展示标题与可选数据源，不含任何敏感配置。 */
    @GetMapping("/{token}/meta")
    public R<Map<String, Object>> meta(@PathVariable String token,
                                       @RequestHeader(value = "Origin", required = false) String origin) {
        ChatEmbed e = service.resolve(token);
        if (e == null) {
            return R.fail(ErrorCode.RESOURCE_FORBIDDEN, "嵌入链接无效或已过期");
        }
        if (!service.originAllowed(e, origin)) {
            return R.fail(ErrorCode.FORBIDDEN, "当前站点未被允许嵌入");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getName());
        m.put("datasourceIds", service.datasourceIdList(e));
        m.put("datasourceNames", service.datasourceNames(e));
        return R.ok(m);
    }

    /**
     * 匿名推荐问法：由 LLM 依数据源 schema + 语义层生成（带缓存与降级）。
     * 与 {@code meta} 分开，避免首屏（标题/名称）被一次 LLM 调用拖慢；访客页面 meta 就绪后再异步拉取。
     * 生成失败一律回落空列表，前端以硬编码兜底，绝不阻断页面。
     */
    @GetMapping("/{token}/suggestions")
    public R<List<String>> suggestions(@PathVariable String token,
                                       @RequestParam(value = "datasourceId", required = false) Long datasourceId,
                                       @RequestHeader(value = "Origin", required = false) String origin) {
        ChatEmbed e = service.resolve(token);
        if (e == null) {
            return R.fail(ErrorCode.RESOURCE_FORBIDDEN, "嵌入链接无效或已过期");
        }
        if (!service.originAllowed(e, origin)) {
            return R.fail(ErrorCode.FORBIDDEN, "当前站点未被允许嵌入");
        }
        try {
            // 访客切换数据源时带入 datasourceId；服务层按本嵌入白名单校验，越权/缺失回落首个允许的数据源。
            return R.ok(service.suggestQuestions(e, datasourceId));
        } catch (RuntimeException ex) {
            log.warn("嵌入推荐问法接口异常，返回空（前端兜底）: token={} - {}", token, ex.getMessage());
            return R.ok(List.of());
        }
    }

    /**
     * 匿名上下文追问：依某轮问数生成逐轮追问（须传 {@code historyId}，即问数返回的 {@code historyId}）。
     * 服务层校验"轮次属于本嵌入的数据源白名单 + uid 匹配"，防跨嵌入/跨用户探测。
     * 失败一律回落空列表，前端以静态推荐兜底。
     */
    @GetMapping("/{token}/followups")
    public R<List<String>> followups(@PathVariable String token,
                                     @RequestParam(value = "historyId", required = false) Long historyId,
                                     @RequestHeader(value = "Origin", required = false) String origin) {
        ChatEmbed e = service.resolve(token);
        if (e == null) {
            return R.fail(ErrorCode.RESOURCE_FORBIDDEN, "嵌入链接无效或已过期");
        }
        if (!service.originAllowed(e, origin)) {
            return R.fail(ErrorCode.FORBIDDEN, "当前站点未被允许嵌入");
        }
        try {
            return R.ok(service.followups(e, historyId));
        } catch (RuntimeException ex) {
            log.warn("嵌入追问接口异常，返回空（前端兜底）: token={} - {}", token, ex.getMessage());
            return R.ok(List.of());
        }
    }

    /** 匿名执行一次问数。 */
    @PostMapping("/{token}/query")
    public R<ChatQueryResponse> query(@PathVariable String token,
                                      @RequestBody(required = false) QueryForm form,
                                      @RequestHeader(value = "Origin", required = false) String origin) {
        ChatEmbed e = service.resolve(token);
        if (e == null) {
            return R.fail(ErrorCode.RESOURCE_FORBIDDEN, "嵌入链接无效或已过期");
        }
        if (!service.originAllowed(e, origin)) {
            return R.fail(ErrorCode.FORBIDDEN, "当前站点未被允许嵌入");
        }
        String question = form == null ? null : form.question();
        if (question == null || question.isBlank()) {
            return R.fail(ErrorCode.PARAM_ERROR, "问题不能为空");
        }
        String conv = form == null ? null : form.conversationId();
        Long ds = form == null ? null : form.datasourceId();
        String extUser = form == null ? null : form.user();
        try {
            return R.ok(service.query(e, question.trim(), conv, origin, ds, extUser));
        } catch (BizException ex) {
            return R.fail(ex.getErrorCode(), ex.getMessage());
        }
    }

    /**
     * 外部用户历史（最新在前）：嵌入页初始化时右侧悬浮面板拉取。
     * 仅按嵌入方传入的 {@code user} 归并——未传则无"用户"可言，返回空列表。
     */
    @GetMapping("/{token}/history")
    public R<List<EmbedHistoryItem>> history(@PathVariable String token,
                                             @RequestParam(value = "user", required = false) String user,
                                             @RequestParam(value = "limit", defaultValue = "50") int limit,
                                             @RequestHeader(value = "Origin", required = false) String origin) {
        ChatEmbed e = service.resolve(token);
        if (e == null) {
            return R.fail(ErrorCode.RESOURCE_FORBIDDEN, "嵌入链接无效或已过期");
        }
        if (!service.originAllowed(e, origin)) {
            return R.fail(ErrorCode.FORBIDDEN, "当前站点未被允许嵌入");
        }
        try {
            return R.ok(service.history(e, user, limit));
        } catch (RuntimeException ex) {
            log.warn("嵌入历史接口异常，返回空: token={} - {}", token, ex.getMessage());
            return R.ok(List.of());
        }
    }

    /**
     * 某次会话的全部轮次（最旧在前）：点击历史项续接时在主体还原完整对话。
     * 须同时带 {@code user} 与 {@code conversationId}，二者缺一返回空列表。
     */
    @GetMapping("/{token}/conversation")
    public R<List<EmbedHistoryItem>> conversation(@PathVariable String token,
                                                  @RequestParam(value = "user", required = false) String user,
                                                  @RequestParam(value = "conversationId", required = false) String conversationId,
                                                  @RequestHeader(value = "Origin", required = false) String origin) {
        ChatEmbed e = service.resolve(token);
        if (e == null) {
            return R.fail(ErrorCode.RESOURCE_FORBIDDEN, "嵌入链接无效或已过期");
        }
        if (!service.originAllowed(e, origin)) {
            return R.fail(ErrorCode.FORBIDDEN, "当前站点未被允许嵌入");
        }
        try {
            return R.ok(service.conversation(e, user, conversationId));
        } catch (RuntimeException ex) {
            log.warn("嵌入会话接口异常，返回空: token={} - {}", token, ex.getMessage());
            return R.ok(List.of());
        }
    }
}
