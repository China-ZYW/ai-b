package com.aibi.module.rbac.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.rbac.dto.CustomRoleEffectiveVO;
import com.aibi.module.rbac.dto.CustomRoleForm;
import com.aibi.module.rbac.dto.CustomRolePermissionForm;
import com.aibi.module.rbac.dto.CustomRoleVO;
import com.aibi.module.rbac.service.CustomRoleService;
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
 * 组织角色可编辑接口（《组织治理专项设计》§2.7，前缀 {@code /api/org/custom-roles}）。
 *
 * <p>全部端点走 {@code org:customrole:write}（矩阵 YYNNNN，仅 OWNER/ADMIN）。租户范围由服务层从当前
 * 登录上下文强制推导，<b>不接受任何 {@code tenantId} 入参</b>，杜绝越权操作他组织角色。
 */
@RestController
@RequestMapping("/api/org/custom-roles")
public class CustomRoleController {

    private final CustomRoleService customRoleService;

    public CustomRoleController(CustomRoleService customRoleService) {
        this.customRoleService = customRoleService;
    }

    /** 本组织自定义角色列表（含 override 数）。 */
    @GetMapping
    @RequiresPermission("org:customrole:write")
    public R<List<CustomRoleVO>> list() {
        return R.ok(customRoleService.list());
    }

    /** 预览某自定义角色的有效权限点（base − DENY + GRANT）。 */
    @GetMapping("/{id}/effective")
    @RequiresPermission("org:customrole:write")
    public R<CustomRoleEffectiveVO> effective(@PathVariable Long id) {
        return R.ok(customRoleService.effective(id));
    }

    /** 新建自定义角色（带 base_role_code、name）。 */
    @PostMapping
    @RequiresPermission("org:customrole:write")
    public R<CustomRoleVO> create(@RequestBody @Valid CustomRoleForm form) {
        return R.ok(customRoleService.create(form));
    }

    /** 改名/描述/启用/停用/data_scope（不可改 base）。 */
    @PutMapping("/{id}")
    @RequiresPermission("org:customrole:write")
    public R<CustomRoleVO> update(@PathVariable Long id, @RequestBody @Valid CustomRoleForm form) {
        return R.ok(customRoleService.update(id, form));
    }

    /** 整体设置 DENY/GRANT 覆盖（窄化护栏在服务层）。 */
    @PutMapping("/{id}/permissions")
    @RequiresPermission("org:customrole:write")
    public R<CustomRoleVO> setPermissions(@PathVariable Long id, @RequestBody @Valid CustomRolePermissionForm form) {
        return R.ok(customRoleService.setOverrides(id, form));
    }

    /** 删除（仍有成员持有时拒 {@code CUSTOM_ROLE_IN_USE}）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("org:customrole:write")
    public R<Void> delete(@PathVariable Long id) {
        customRoleService.delete(id);
        return R.ok();
    }
}
