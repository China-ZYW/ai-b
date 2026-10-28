package com.aibi.module.share.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.UserContext;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.share.dto.ShareRequest;
import com.aibi.module.share.dto.ShareVO;
import com.aibi.module.share.service.ShareService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分享管理接口（M7，需认证）：创建分享、我的分享列表、删除分享。
 *
 * <p>公开只读访问在 {@link PublicShareController}（{@code /api/public/share/**}，免认证）。
 *
 * <p><b>权限点（L2）</b>：三个端点此前均无注解。虽然都以 {@code UserContext.userId()} 为边界
 * （只能操作自己的分享），但创建分享会把租户数据以公开链接外发，属敏感动作——按字典补
 * {@code share:link:read/write/delete}，与前端路由/按钮门禁同名同义。
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    /** 创建一个分享链接，返回可分享的公开 url（租户由拦截器自动隔离）。 */
    @PostMapping
    @RequiresPermission("share:link:write")
    public R<ShareVO> create(@RequestBody @Valid ShareRequest request) {
        return R.ok(shareService.createShare(UserContext.userId(), TenantContext.planType(), request));
    }

    /** 我的分享列表。 */
    @GetMapping
    @RequiresPermission("share:link:read")
    public R<List<ShareVO>> list() {
        return R.ok(shareService.listShares(UserContext.userId()));
    }

    /** 删除分享。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("share:link:delete")
    public R<Void> delete(@PathVariable Long id) {
        shareService.deleteShare(UserContext.userId(), id);
        return R.ok();
    }
}
