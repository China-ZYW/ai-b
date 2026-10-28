package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.admin.dto.AdminUserForm;
import com.aibi.module.admin.dto.AdminUserVO;
import com.aibi.module.admin.dto.ResetPasswordForm;
import com.aibi.module.admin.service.UserAdminService;
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
 * 超管 · 账号管理接口。受 {@code /api/admin/** → hasRole('SUPER_ADMIN')} 保护。
 *
 * <p>跨租户账号 CRUD、重置密码、启停。{@code GET} 不带 {@code tenantId} 时返回全平台账号。
 * 响应绝不含密码哈希；密码只写不读。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAdminService userAdminService;

    public AdminUserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    @RequiresPermission(value = "admin:user:read", platformOnly = true)
    public R<List<AdminUserVO>> list(@RequestParam(required = false) Long tenantId) {
        return R.ok(userAdminService.list(tenantId));
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "admin:user:read", platformOnly = true)
    public R<AdminUserVO> get(@PathVariable Long id) {
        return R.ok(userAdminService.get(id));
    }

    @PostMapping
    @RequiresPermission(value = "admin:user:write", platformOnly = true)
    public R<AdminUserVO> create(@RequestBody @Valid AdminUserForm form) {
        return R.ok(userAdminService.create(form));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "admin:user:write", platformOnly = true)
    public R<AdminUserVO> update(@PathVariable Long id, @RequestBody @Valid AdminUserForm form) {
        return R.ok(userAdminService.update(id, form));
    }

    @PostMapping("/{id}/reset-password")
    @RequiresPermission(value = "admin:user:reset", platformOnly = true)
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody @Valid ResetPasswordForm form) {
        userAdminService.resetPassword(id, form.password());
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @RequiresPermission(value = "admin:user:write", platformOnly = true)
    public R<AdminUserVO> setStatus(@PathVariable Long id, @RequestParam Integer status) {
        return R.ok(userAdminService.setStatus(id, status));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin:user:write", platformOnly = true)
    public R<Void> delete(@PathVariable Long id) {
        userAdminService.delete(id);
        return R.ok();
    }
}
