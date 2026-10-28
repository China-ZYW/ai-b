package com.aibi.module.org.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.org.dto.InvitationAcceptResult;
import com.aibi.module.org.dto.InvitationCreateForm;
import com.aibi.module.org.dto.InvitationCreateResult;
import com.aibi.module.org.dto.InvitationVO;
import com.aibi.module.org.service.InvitationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组织内 · 邀请管理接口（§9.2 组织设置 · 邀请，管理侧 create/list/revoke）。
 *
 * <p>挂在 {@code /api/org/**} 域（{@code SecurityConfig} 中为 {@code authenticated()}，且在
 * {@code fail-closed-domains} 内）。方法级 {@code @RequiresPermission} 按组织矩阵收敛：
 * {@code member:invite:read}（YYNNNN）、{@code member:invite:create}（YYNNNN）、
 * {@code member:invite:revoke}（YYNNNN），故仅 OWNER/ADMIN 可见可用。
 *
 * <p>租户范围由服务层从当前登录上下文强制推导，<b>不接受 {@code tenantId} 入参</b>。创建结果里的
 * {@code token} 明文仅返回一次，服务端只持久化其哈希。接受链路（accept）本轮延后，见
 * {@link InvitationService} 类注释。
 */
@RestController
@RequestMapping("/api/org/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /** 本组织邀请列表（不含令牌哈希）。 */
    @GetMapping
    @RequiresPermission("member:invite:read")
    public R<List<InvitationVO>> list() {
        return R.ok(invitationService.list());
    }

    /** 发起邀请，返回一次性令牌明文与可分发链接。 */
    @PostMapping
    @RequiresPermission("member:invite:create")
    public R<InvitationCreateResult> create(@RequestBody @Valid InvitationCreateForm form) {
        return R.ok(invitationService.create(form));
    }

    /** 撤销邀请（置 REVOKED，令牌随即失效）。 */
    @PostMapping("/{id}/revoke")
    @RequiresPermission("member:invite:revoke")
    public R<Void> revoke(@PathVariable Long id) {
        invitationService.revoke(id);
        return R.ok();
    }

    /**
     * 被邀请者接受邀请（登录后调用）：凭明文令牌加入组织。无需管理权限，仅要求已登录
     * （{@code /api/org/**} 在 {@code SecurityConfig} 中为 {@code authenticated()}），由
     * {@code InvitationService.accept} 内部完成令牌校验、席位消费与角色授予。
     */
    @PostMapping("/accept")
    public R<InvitationAcceptResult> accept(@RequestParam String token) {
        return R.ok(invitationService.accept(token));
    }
}
