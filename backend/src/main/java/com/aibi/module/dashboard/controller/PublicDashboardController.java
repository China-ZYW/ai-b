package com.aibi.module.dashboard.controller;

import com.aibi.common.response.R;
import com.aibi.module.dashboard.dto.DashboardAccessResponse;
import com.aibi.module.dashboard.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 大屏公开访问接口（问题2，免认证）：凭 token 实时访问已分享的大屏。
 *
 * <p>路径位于 {@code /api/public/**}，已在 {@code SecurityConfig} 放行。与结果分享的"快照"不同，此处返回
 * <b>实时</b>数据：无密码 → 直接返回大屏配置 + 各 widget 实时数据；有密码且未提供 → {@code PASSWORD_REQUIRED}
 * （前端弹密码框后带 {@code password} 重试）；过期 → {@code EXPIRED}；token 不存在/已关闭分享 → {@code NOT_FOUND}。
 */
@RestController
@RequestMapping("/api/public/dashboard")
public class PublicDashboardController {

    private final DashboardService dashboardService;

    public PublicDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 公开访问一个已分享的大屏（实时数据）。filter 为可选下钻/联动筛选 JSON，透传后整屏按维度切片。 */
    @GetMapping("/{token}")
    public R<DashboardAccessResponse> access(@PathVariable String token,
                                             @RequestParam(required = false) String password,
                                             @RequestParam(required = false) String filter) {
        return R.ok(dashboardService.accessByToken(token, password, filter));
    }
}
