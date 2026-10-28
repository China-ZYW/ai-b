package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.dashboard.dto.DashboardTemplateForm;
import com.aibi.module.dashboard.dto.DashboardTemplateVO;
import com.aibi.module.dashboard.service.DashboardTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 超管 · 驾驶舱模板管理接口。受 {@code /api/admin/** → hasRole('SUPER_ADMIN')} 保护。
 *
 * <p>模板为全局资源（所有租户共享），故其增改删归口超管；普通用户的只读列举见
 * {@code /api/dashboard-template}。复用 {@link DashboardTemplateService}。
 */
@RestController
@RequestMapping("/api/admin/dashboard-templates")
public class AdminDashboardTemplateController {

    private final DashboardTemplateService templateService;

    public AdminDashboardTemplateController(DashboardTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    @RequiresPermission(value = "admin:content:write", platformOnly = true)
    public R<List<DashboardTemplateVO>> list() {
        return R.ok(templateService.list());
    }

    @PostMapping
    @RequiresPermission(value = "admin:content:write", platformOnly = true)
    public R<DashboardTemplateVO> create(@RequestBody @Valid DashboardTemplateForm form) {
        return R.ok(templateService.create(form));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "admin:content:write", platformOnly = true)
    public R<DashboardTemplateVO> update(@PathVariable Long id, @RequestBody @Valid DashboardTemplateForm form) {
        return R.ok(templateService.update(id, form));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin:content:write", platformOnly = true)
    public R<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return R.ok();
    }
}
