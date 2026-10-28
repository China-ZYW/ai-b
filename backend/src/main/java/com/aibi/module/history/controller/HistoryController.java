package com.aibi.module.history.controller;

import com.aibi.common.response.PageResult;
import com.aibi.common.response.R;
import com.aibi.common.security.UserContext;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.chat.service.ChatService;
import com.aibi.module.history.dto.ConversationVO;
import com.aibi.module.history.dto.FeedbackCorrection;
import com.aibi.module.history.dto.HistoryDetailVO;
import com.aibi.module.history.dto.HistoryVO;
import com.aibi.module.history.service.QueryHistoryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 查询历史接口（M4 / 开发计划 T4.4）：我的历史列表、会话列表、单条详情、删除。
 *
 * <p>全部以当前租户 + 当前用户为边界；历史落库由问数链路（{@code ChatService}）自动完成。
 *
 * <p><b>权限点（L2）</b>：六个端点此前均无注解，仅靠自作用域收敛。按权限字典补齐
 * {@code history:query:read/delete/feedback}，与前端「查询历史」路由与删除按钮门禁同名同义。
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final QueryHistoryService historyService;
    private final ChatService chatService;

    public HistoryController(QueryHistoryService historyService, ChatService chatService) {
        this.historyService = historyService;
        this.chatService = chatService;
    }

    /** 分页查询"我的历史"，可按数据源过滤（租户由拦截器自动隔离）。 */
    @GetMapping
    @RequiresPermission("history:query:read")
    public R<PageResult<HistoryVO>> list(@RequestParam(required = false) Long datasourceId,
                                         @RequestParam(defaultValue = "20") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        Long uid = UserContext.userId();
        List<HistoryVO> items = historyService.listHistory(uid, datasourceId, limit, offset);
        long total = historyService.countHistory(uid, datasourceId);
        return R.ok(PageResult.of(items, total, limit, offset));
    }

    /** 会话列表（多轮对话侧边栏）。 */
    @GetMapping("/conversations")
    @RequiresPermission("history:query:read")
    public R<List<ConversationVO>> conversations(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(historyService.listConversations(UserContext.userId(), limit));
    }

    /** 单条历史详情。 */
    @GetMapping("/{id}")
    @RequiresPermission("history:query:read")
    public R<HistoryVO> get(@PathVariable Long id) {
        return R.ok(historyService.getHistory(UserContext.userId(), id));
    }

    /**
     * 历史回放详情：结果快照（老记录重跑已存 SQL 回放）+ 图表规格，
     * 供前端点击历史会话还原"问数当时"的数据与图表；不耗 LLM、不计配额。
     */
    @GetMapping("/{id}/detail")
    @RequiresPermission("history:query:read")
    public R<HistoryDetailVO> detail(@PathVariable Long id) {
        return R.ok(chatService.historyDetail(UserContext.userId(), id));
    }

    /** 删除单条历史。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("history:query:delete")
    public R<Void> delete(@PathVariable Long id) {
        historyService.deleteHistory(UserContext.userId(), id);
        return R.ok();
    }

    /**
     * 反馈（点赞/点踩）：body {@code {"vote":1|-1, "expectedSql"?:"...", "note"?:"...", "reason"?:"..."}}。
     * 赞且 OK 的历史进入少样本晋升池，形成"使用→反馈→更准"的持续学习闭环。
     *
     * <p>点踩时可附修正建议（T11-2，均可空）：仅 {@code vote==-1} 时组装 {@link FeedbackCorrection} 传入，
     * 落库作治理转写预填草稿（不自动进少样本/指标）。
     *
     * <p>该动作会写入反馈（影响后续问数效果），故使用独立权限点 {@code history:query:feedback}。
     */
    @PostMapping("/{id}/feedback")
    @RequiresPermission("history:query:feedback")
    public R<Void> feedback(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object voteRaw = body == null ? null : body.get("vote");
        int vote = voteRaw instanceof Number n ? n.intValue() : 0;
        FeedbackCorrection correction = null;
        if (vote == -1 && body != null) {
            correction = new FeedbackCorrection(
                    asText(body.get("expectedSql")), asText(body.get("note")), asText(body.get("reason")));
        }
        historyService.feedback(UserContext.userId(), id, vote, correction);
        return R.ok();
    }

    private static String asText(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
