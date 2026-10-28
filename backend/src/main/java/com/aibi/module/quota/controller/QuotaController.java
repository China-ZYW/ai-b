package com.aibi.module.quota.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.quota.dto.QuotaStatus;
import com.aibi.module.quota.service.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配额查询接口（M6）：前端在问数面板展示"今日剩余次数"，超限前给出预警。
 *
 * <p><b>权限点（L2）</b>：补挂 {@code quota:usage:read}（字典中授予全部 6 个组织角色 YYYYYY），
 * 对当前行为无影响，纯粹为消除"未挂权限点"的告警噪声。
 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 当前租户配额状态（每日 + 累计 + 期限）。 */
    @GetMapping
    @RequiresPermission("quota:usage:read")
    public R<QuotaStatus> status() {
        return R.ok(quotaService.status(TenantContext.requireTenantId()));
    }
}
