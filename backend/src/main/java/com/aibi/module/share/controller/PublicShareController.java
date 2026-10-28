package com.aibi.module.share.controller;

import com.aibi.common.response.R;
import com.aibi.module.share.dto.ShareAccessResponse;
import com.aibi.module.share.service.ShareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分享公开访问接口（M7，免认证）：凭 token 只读访问分享快照。
 *
 * <p>路径位于 {@code /api/public/**}，已在 {@code SecurityConfig} 放行。前端"公开访问页"直接消费本接口：
 * 无密码 → 返回快照；有密码且未提供 → {@code PASSWORD_REQUIRED}（前端弹密码框后带 {@code password} 重试）；
 * 过期 → {@code EXPIRED}；token 不存在 → {@code NOT_FOUND}。
 */
@RestController
@RequestMapping("/api/public/share")
public class PublicShareController {

    private final ShareService shareService;

    public PublicShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    /** 公开访问一个分享。 */
    @GetMapping("/{token}")
    public R<ShareAccessResponse> access(@PathVariable String token,
                                         @RequestParam(required = false) String password) {
        return R.ok(shareService.access(token, password));
    }
}
