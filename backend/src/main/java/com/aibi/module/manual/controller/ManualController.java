package com.aibi.module.manual.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.manual.dto.PublishedManualVO;
import com.aibi.module.manual.service.ManualService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户手册阅读接口（全体已登录用户）：只返回已发布快照，不含草稿。
 *
 * <p>编辑/发布在 {@code /api/admin/manual}（SecurityConfig 限定 SUPER_ADMIN）。
 *
 * <p><b>权限点（L2）</b>：补挂 {@code manual:doc:read}。该码在权限字典中授予全部 6 个组织角色
 * （YYYYYY，含 BILLING），故对当前行为无任何影响；补挂的意义是让"未挂权限点即告警"的运维指标
 * 保持为真——若日后新增的角色不授予手册阅读权，此处应自然生效。
 */
@RestController
@RequestMapping("/api/manual")
public class ManualController {

    private final ManualService manualService;

    public ManualController(ManualService manualService) {
        this.manualService = manualService;
    }

    /** 已发布手册；从未发布时 data 为 null，前端提示"手册尚未发布"。 */
    @GetMapping
    @RequiresPermission("manual:doc:read")
    public R<PublishedManualVO> published() {
        return R.ok(manualService.getPublished());
    }
}
