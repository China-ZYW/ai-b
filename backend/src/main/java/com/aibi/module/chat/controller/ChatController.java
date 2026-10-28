package com.aibi.module.chat.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.UserContext;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.chat.dto.ChatQueryRequest;
import com.aibi.module.chat.dto.ChatQueryResponse;
import com.aibi.module.chat.service.ChatService;
import com.aibi.module.chat.service.FollowupService;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.service.SuggestionService;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.service.QueryHistoryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问数接口（同步一次性返回）：{@code POST /api/chat/query}。
 * 需分步进度推送的流式版本见 {@link ChatStreamController}（{@code GET /api/chat/query/stream}，M5/T4.1）。
 *
 * <p><b>权限点（L2）</b>：问数会消耗大模型配额，此前仅靠登录态约束——任何已登录成员（含计费联系人）
 * 都能直接驱动 LLM 调用。现补 {@code chat:query:execute} / {@code chat:suggestion:read}。
 *
 * <p><b>与组织开关的关系</b>：{@code tenant.allow_viewer_query} 控制"只读成员能否问数"，是<b>组织策略</b>；
 * 本处的权限点是"这个角色是否具备问数能力"。二者不冲突：权限字典中 VIEWER 是授予状态（YYYYYN 的第 5 位
 * 为 Y），故只读成员最终能否问数仍由组织开关决定，加注解不会把它的能力提前收走。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final SuggestionService suggestionService;
    private final FollowupService followupService;
    private final QueryHistoryService queryHistoryService;

    public ChatController(ChatService chatService, SuggestionService suggestionService,
                          FollowupService followupService, QueryHistoryService queryHistoryService) {
        this.chatService = chatService;
        this.suggestionService = suggestionService;
        this.followupService = followupService;
        this.queryHistoryService = queryHistoryService;
    }

    @PostMapping("/query")
    @RequiresPermission("chat:query:execute")
    public R<ChatQueryResponse> query(@RequestBody @Valid ChatQueryRequest request) {
        log.info("租户[{}] 用户[{}] 对数据源[{}] 问数[conv={}]: {}",
                TenantContext.tenantId(), UserContext.userId(), request.datasourceId(),
                request.conversationId(), request.question());
        ChatQueryResponse response = chatService.ask(
                request.datasourceId(), request.question(), request.conversationId(),
                request.skill(), request.clarifiedOrDefault(), ProgressListener.NOOP);
        return R.ok(response);
    }

    /**
     * 推荐问题（问题4）：依数据源 schema 由 LLM 智能生成，带进程内缓存与逐级降级（永不报错）。
     * 前端进入对话页/切换数据源时拉取，用于展示可点击的推荐问题。
     */
    @GetMapping("/suggestions")
    @RequiresPermission("chat:suggestion:read")
    public R<List<String>> suggestions(@RequestParam Long datasourceId) {
        return R.ok(suggestionService.suggest(datasourceId));
    }

    /**
     * 上下文追问：依本轮问数（问题 + SQL + 结果快照）由 LLM 生成 3~4 条逐轮追问。
     * 仅允许查本人（uid 隔离）的成功轮次；失败/非本人返回空列表，前端回落静态推荐。
     * 复用 {@code chat:suggestion:read} 权限点，不新增权限码（免抬 perm_version）。
     */
    @GetMapping("/followups")
    @RequiresPermission("chat:suggestion:read")
    public R<List<String>> followups(@RequestParam Long historyId) {
        QueryHistory h = queryHistoryService.findOwned(UserContext.userId(), historyId);
        return R.ok(followupService.followups(h));
    }
}
