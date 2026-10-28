package com.aibi.module.theme.controller;

import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.theme.entity.ThemeConfig;
import com.aibi.module.theme.service.ThemeService;
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
 * 主题令牌接口（配置化升级 V2，需认证）：列表 / 按 code 取 / 租户自定义新建更新 / 删除。
 * 全局内置预设由 DataSeeder 播种、超管经 {@code /api/admin} 通道维护；本控制器写入落本租户行。
 *
 * <p><b>权限点（L2）</b>：三个写端点补挂 {@code theme:config:write}。此前它们<b>无任何注解</b>，
 * 任何已登录用户（含只读成员与计费联系人）都能新建/改写/删除租户主题——而主题是全租户共享的展示资产，
 * 恶意改主题等价于对全组织做视觉篡改。该码在权限字典中为 {@code YYYNNN}（仅 OWNER/ADMIN/DATA_STEWARD），
 * 与前端「主题令牌」维护入口的门禁一致。
 *
 * <p><b>为什么两个读端点仍不挂权限点</b>：权限字典中<b>不存在</b> theme 域的读码（只有
 * {@code theme:config:write}）；而主题在应用启动时由前端 store 为<b>所有已认证用户</b>拉取
 * （驱动全局配色）。若强行用写码门禁读，只读成员会因 403 而丢失整套配色——那是行为回归。
 * 故此处的"未标注"属<b>有意基线放行</b>，非漏配；若日后需要收紧，应先增加
 * {@code theme:config:read} 权限点并同步权限矩阵，而不是复用写码。
 *
 * <p><b>域级护栏</b>：本控制器此前未落在 {@code ApiPermissionRegistry} 的 fail-closed 域清单内，
 * 这正是"写端点裸奔却长期无人发现"的根因，已在 {@link com.aibi.common.security.ApiPermissionRegistry}
 * 中补入 {@code /api/theme/**}。
 */
@RestController
@RequestMapping("/api/theme")
public class ThemeController {

    private final ThemeService themeService;

    public ThemeController(ThemeService themeService) {
        this.themeService = themeService;
    }

    /** 当前租户可见的主题列表（全局预设 + 租户自定义）。 */
    @GetMapping("/list")
    public R<List<ThemeConfig>> list() {
        return R.ok(themeService.list());
    }

    /** 按 code 取单个主题（租户优先，回落全局预设）。 */
    @GetMapping("/{code}")
    public R<ThemeConfig> get(@PathVariable String code) {
        ThemeConfig theme = themeService.get(code);
        return theme == null ? R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "主题不存在：" + code) : R.ok(theme);
    }

    /** 新建租户自定义主题（需主题自定义写权，避免任意已认证用户越权建主题）。 */
    @PostMapping
    @RequiresPermission("theme:config:write")
    public R<ThemeConfig> create(@RequestBody ThemeConfig form) {
        form.setId(null);
        ThemeConfig saved = themeService.save(form);
        return saved == null ? R.fail(ErrorCode.PARAM_ERROR, "主题 code/tokens 不能为空") : R.ok(saved);
    }

    /** 更新租户自定义主题（需主题自定义写权）。 */
    @PutMapping
    @RequiresPermission("theme:config:write")
    public R<ThemeConfig> update(@RequestBody ThemeConfig form) {
        if (form.getId() == null) {
            return R.fail(ErrorCode.PARAM_ERROR, "缺少主题 id");
        }
        ThemeConfig saved = themeService.save(form);
        return saved == null ? R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "主题不存在或无权访问") : R.ok(saved);
    }

    /** 删除租户自定义主题（内置预设不可删；需主题自定义写权）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("theme:config:write")
    public R<Void> delete(@PathVariable Long id) {
        return themeService.delete(id) ? R.ok()
                : R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "主题不存在、内置或无权删除");
    }
}
