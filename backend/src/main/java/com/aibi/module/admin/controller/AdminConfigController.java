package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.config.dto.ConfigForm;
import com.aibi.module.config.entity.SysConfig;
import com.aibi.module.config.service.ConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 配置中心超管接口（/api/admin/** 已由 SecurityConfig 限定 SUPER_ADMIN 角色）：
 * 管理平台级（GLOBAL scope）配置默认值，所有租户在未覆盖时继承。
 */
@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {

    private final ConfigService configService;

    public AdminConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /** 平台默认配置行（按 category）。 */
    @GetMapping("/rows")
    @RequiresPermission(value = "admin:config:read", platformOnly = true)
    public R<List<SysConfig>> rows(@RequestParam String category) {
        return R.ok(configService.listRows(ConfigService.SCOPE_GLOBAL, null, category));
    }

    /**
     * upsert 平台默认配置。
     *
     * <p>{@code managed=true} 时该 (category, configKey) 升格为<b>平台管控</b>：所有租户的覆盖行立即失效
     * 且不可再写入（{@code ConfigService} 在解析与写入两处拦截）。关闭管控后租户覆盖原样复活——管控期间
     * 不删除任何租户数据，故可安全来回切换。
     *
     * <p><b>幂等</b>：值 / 说明 / 管控标记均无变化时服务层不写库，{@code data} 返回 {@code null}——
     * 前端据此提示"未发生变化"，避免重复点击把 {@code version} 顶上去。
     */
    @PutMapping
    @RequiresPermission(value = "admin:config:write", platformOnly = true)
    public R<SysConfig> upsert(@RequestBody @Valid ConfigForm form) {
        return R.ok(configService.upsert(ConfigService.SCOPE_GLOBAL, null,
                form.category(), form.configKey(), form.valueJson(), form.description(), form.managed()));
    }

    /** 删除平台默认配置行。 */
    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin:config:write", platformOnly = true)
    public R<Void> delete(@PathVariable Long id) {
        configService.delete(id, ConfigService.SCOPE_GLOBAL, null);
        return R.ok();
    }
}
