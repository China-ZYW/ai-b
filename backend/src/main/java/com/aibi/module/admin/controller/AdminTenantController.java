package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.admin.dto.SeatQuotaForm;
import com.aibi.module.admin.dto.TenantForm;
import com.aibi.module.admin.dto.TenantVO;
import com.aibi.module.admin.service.TenantAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 超管 · 租户管理接口。受 {@code SecurityConfig} 的 {@code /api/admin/** → hasRole('SUPER_ADMIN')} 保护。
 *
 * <p>提供租户 CRUD、套餐/隔离模式/使用期限/配额（每日 + 累计）配置、启停。跨租户操作，不受当前上下文租户限制。
 */
@RestController
@RequestMapping("/api/admin/tenants")
public class AdminTenantController {

    private final TenantAdminService tenantAdminService;

    public AdminTenantController(TenantAdminService tenantAdminService) {
        this.tenantAdminService = tenantAdminService;
    }

    @GetMapping
    @RequiresPermission(value = "admin:tenant:read", platformOnly = true)
    public R<List<TenantVO>> list() {
        return R.ok(tenantAdminService.list());
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "admin:tenant:read", platformOnly = true)
    public R<TenantVO> get(@PathVariable Long id) {
        return R.ok(tenantAdminService.get(id));
    }

    @PostMapping
    @RequiresPermission(value = "admin:tenant:write", platformOnly = true)
    public R<TenantVO> create(@RequestBody @Valid TenantForm form) {
        return R.ok(tenantAdminService.create(form));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "admin:tenant:write", platformOnly = true)
    public R<TenantVO> update(@PathVariable Long id, @RequestBody @Valid TenantForm form) {
        return R.ok(tenantAdminService.update(id, form));
    }

    /** 逐租户席位/配额调整（§9.2 席位与配额）。 */
    @PutMapping("/{id}/seats")
    @RequiresPermission(value = "admin:tenant:write", platformOnly = true)
    public R<TenantVO> updateSeats(@PathVariable Long id, @RequestBody @Valid SeatQuotaForm form) {
        return R.ok(tenantAdminService.updateSeats(id, form));
    }

    /** 批量按套餐重置席位与每日上限（§9.2）。{@code plan} 省略则重置全部；返回受影响租户数。 */
    @PostMapping("/seats/reset-by-plan")
    @RequiresPermission(value = "admin:tenant:write", platformOnly = true)
    public R<Integer> resetSeatsByPlan(@RequestParam(required = false) String plan) {
        return R.ok(tenantAdminService.resetSeatsByPlan(plan));
    }

    @PutMapping("/{id}/status")
    @RequiresPermission(value = "admin:tenant:disable", platformOnly = true)
    public R<TenantVO> setStatus(@PathVariable Long id, @RequestParam Integer status) {
        return R.ok(tenantAdminService.setStatus(id, status));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin:tenant:dissolve", platformOnly = true)
    public R<Void> delete(@PathVariable Long id) {
        tenantAdminService.delete(id);
        return R.ok();
    }
}
