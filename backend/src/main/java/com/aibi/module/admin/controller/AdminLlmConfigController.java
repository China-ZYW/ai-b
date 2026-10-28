package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.admin.dto.LlmConfigForm;
import com.aibi.module.admin.dto.LlmConfigVO;
import com.aibi.module.admin.service.LlmConfigAdminService;
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
 * 超管 · 大模型配置接口。受 {@code /api/admin/** → hasRole('SUPER_ADMIN')} 保护。
 *
 * <p>全局默认（{@code tenantId} 空）+ 租户覆盖的 CRUD、启停、连通性测试。
 * API Key 只写不读：响应仅回 {@code hasApiKey}，绝不回填明文/密文。
 */
@RestController
@RequestMapping("/api/admin/llm-configs")
public class AdminLlmConfigController {

    private final LlmConfigAdminService llmConfigAdminService;

    public AdminLlmConfigController(LlmConfigAdminService llmConfigAdminService) {
        this.llmConfigAdminService = llmConfigAdminService;
    }

    @GetMapping
    @RequiresPermission(value = "admin:llm:read", platformOnly = true)
    public R<List<LlmConfigVO>> list(@RequestParam(required = false) Long tenantId) {
        return R.ok(llmConfigAdminService.list(tenantId));
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "admin:llm:read", platformOnly = true)
    public R<LlmConfigVO> get(@PathVariable Long id) {
        return R.ok(llmConfigAdminService.get(id));
    }

    @PostMapping
    @RequiresPermission(value = "admin:llm:write", platformOnly = true)
    public R<LlmConfigVO> create(@RequestBody @Valid LlmConfigForm form) {
        return R.ok(llmConfigAdminService.create(form));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "admin:llm:write", platformOnly = true)
    public R<LlmConfigVO> update(@PathVariable Long id, @RequestBody @Valid LlmConfigForm form) {
        return R.ok(llmConfigAdminService.update(id, form));
    }

    @PutMapping("/{id}/enabled")
    @RequiresPermission(value = "admin:llm:write", platformOnly = true)
    public R<LlmConfigVO> setEnabled(@PathVariable Long id, @RequestParam Integer enabled) {
        return R.ok(llmConfigAdminService.setEnabled(id, enabled));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin:llm:write", platformOnly = true)
    public R<Void> delete(@PathVariable Long id) {
        llmConfigAdminService.delete(id);
        return R.ok();
    }

    /** 测试表单里的配置（未保存/刚编辑），明文 Key 优先。 */
    @PostMapping("/test")
    @RequiresPermission(value = "admin:llm:write", platformOnly = true)
    public R<Void> test(@RequestBody @Valid LlmConfigForm form) {
        llmConfigAdminService.test(form);
        return R.ok();
    }

    /** 测试已保存的配置（用其已存密文 Key）。 */
    @PostMapping("/{id}/test")
    @RequiresPermission(value = "admin:llm:write", platformOnly = true)
    public R<Void> testById(@PathVariable Long id) {
        llmConfigAdminService.testById(id);
        return R.ok();
    }
}
