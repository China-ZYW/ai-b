package com.aibi.module.dashboard.controller;

import com.aibi.common.response.R;
import com.aibi.module.dashboard.dto.DashboardTemplateVO;
import com.aibi.module.dashboard.service.DashboardTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 驾驶舱内置模板接口（只读，所有已登录用户可用）：列出全局模板供“从模板新建驾驶舱”，含骨架布局与槽位定义。
 *
 * <p>{@code dashboard_template} 为全局表，所有租户共享。模板的增改删由超管在
 * {@code /api/admin/dashboard-templates} 下进行（见 {@code AdminDashboardTemplateController}）。
 */
@RestController
@RequestMapping("/api/dashboard-template")
public class DashboardTemplateController {

    private final DashboardTemplateService templateService;

    public DashboardTemplateController(DashboardTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public R<List<DashboardTemplateVO>> list() {
        return R.ok(templateService.list());
    }

    @GetMapping("/{id}")
    public R<DashboardTemplateVO> get(@PathVariable Long id) {
        return R.ok(templateService.get(id));
    }
}
