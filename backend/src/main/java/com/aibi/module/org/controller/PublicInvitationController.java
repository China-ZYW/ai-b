package com.aibi.module.org.controller;

import com.aibi.common.response.R;
import com.aibi.module.org.dto.InvitationPreviewVO;
import com.aibi.module.org.service.InvitationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织邀请 · 公开预览接口（免认证，「加入组织」落地页 {@code /org/join} 消费）。
 *
 * <p>路径位于 {@code /api/public/**}，已在 {@code SecurityConfig} 放行，且<b>不在</b>
 * {@code fail-closed-domains} 内，故无需 {@code @RequiresPermission}（匿名可访问、启动不告警）。
 * 与同域的 {@code PublicShareController}/{@code PublicDashboardController} 一致，属只读公开端点。
 *
 * <p>凭明文令牌只读返回邀请概要（组织名/角色/留言/有效期/可用性状态），绝不回传令牌哈希或邀请 id。
 * 被邀请者登录后由 {@link InvitationController#accept} 完成接受；本接口只做匿名预览，供落地页展示信息并引导登录。
 * 与管理侧 {@link InvitationController}（{@code /api/org/invitations}，需认证 + {@code member:invite:*}）
 * 分离，互不影响。
 */
@RestController
@RequestMapping("/api/public/invitation")
public class PublicInvitationController {

    private final InvitationService invitationService;

    public PublicInvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /** 凭明文令牌预览邀请概要（匿名，只读，无副作用）。 */
    @GetMapping("/preview")
    public R<InvitationPreviewVO> preview(@RequestParam String token) {
        return R.ok(invitationService.preview(token));
    }
}
