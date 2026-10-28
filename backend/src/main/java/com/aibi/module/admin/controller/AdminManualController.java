package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.security.UserContext;
import com.aibi.module.manual.dto.ManualSaveForm;
import com.aibi.module.manual.dto.ManualVO;
import com.aibi.module.manual.service.ManualService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户手册超管接口（/api/admin/** 已由 SecurityConfig 限定 SUPER_ADMIN 角色）：
 * 编辑草稿、发布、查看草稿与已发布快照。
 */
@RestController
@RequestMapping("/api/admin/manual")
public class AdminManualController {

    private final ManualService manualService;

    public AdminManualController(ManualService manualService) {
        this.manualService = manualService;
    }

    /** 编辑视图：草稿 + 已发布快照（含 status/version/是否已发布）。 */
    @GetMapping
    @RequiresPermission(value = "admin:manual:publish", platformOnly = true)
    public R<ManualVO> get() {
        return R.ok(manualService.getAdminView());
    }

    /** 保存草稿（不影响线上发布版）。 */
    @PutMapping
    @RequiresPermission(value = "admin:manual:publish", platformOnly = true)
    public R<ManualVO> saveDraft(@RequestBody @Valid ManualSaveForm form) {
        return R.ok(manualService.saveDraft(form, UserContext.userId()));
    }

    /** 发布：把当前草稿拷贝为发布版，version+1。 */
    @PostMapping("/publish")
    @RequiresPermission(value = "admin:manual:publish", platformOnly = true)
    public R<ManualVO> publish() {
        return R.ok(manualService.publish(UserContext.userId()));
    }
}
