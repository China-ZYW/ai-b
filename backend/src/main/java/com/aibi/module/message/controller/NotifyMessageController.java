package com.aibi.module.message.controller;

import com.aibi.common.response.PageResult;
import com.aibi.common.response.R;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.message.dto.NotifyMessageVO;
import com.aibi.module.message.service.NotifyMessageService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信收件箱接口（挂 {@code /api/messages}）。
 *
 * <p><b>铁律：只服务"当前登录用户本人"</b>。每个端点都从 {@link AccessContextHolder#require()} 取
 * {@code uid}/{@code tenantId} 作为查询/写入的强制收敛条件，即使租户拦截器漏挡也读不到别人的信。
 *
 * <p>权限点 {@code message:inbox:read} 标记 {@code YYYYYY}（全员可读自己的站内信），
 * 与"站内信天然只属于本人"的语义一致；控制器不再叠 @RequiresPermission 之外的越权校验，
 * 因为 uid 已是身份本身的强约束。
 */
@RestController
@RequestMapping("/api/messages")
public class NotifyMessageController {

    private final NotifyMessageService service;

    public NotifyMessageController(NotifyMessageService service) {
        this.service = service;
    }

    /** 收件箱列表（倒序，可只取未读）。 */
    @GetMapping("/list")
    @RequiresPermission("message:inbox:read")
    public R<PageResult<NotifyMessageVO>> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly) {
        AccessContext ctx = AccessContextHolder.require();
        return R.ok(service.list(ctx.requireUid(), ctx.requireTenantId(), unreadOnly, limit, offset));
    }

    /** 未读计数（顶栏铃铛角标用，轮询/进入即拉）。 */
    @GetMapping("/unread-count")
    @RequiresPermission("message:inbox:read")
    public R<Long> unreadCount() {
        AccessContext ctx = AccessContextHolder.require();
        return R.ok(service.unreadCount(ctx.requireUid(), ctx.requireTenantId()));
    }

    /** 标记单条已读。 */
    @PostMapping("/{id}/read")
    @RequiresPermission("message:inbox:read")
    public R<Void> read(@PathVariable Long id) {
        AccessContext ctx = AccessContextHolder.require();
        service.markRead(id, ctx.requireUid(), ctx.requireTenantId());
        return R.ok();
    }

    /** 全部标记已读。 */
    @PostMapping("/read-all")
    @RequiresPermission("message:inbox:read")
    public R<Void> readAll() {
        AccessContext ctx = AccessContextHolder.require();
        service.markAllRead(ctx.requireUid(), ctx.requireTenantId());
        return R.ok();
    }

    /** 删除单条（仅本人）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("message:inbox:read")
    public R<Void> delete(@PathVariable Long id) {
        AccessContext ctx = AccessContextHolder.require();
        service.delete(id, ctx.requireUid(), ctx.requireTenantId());
        return R.ok();
    }
}
