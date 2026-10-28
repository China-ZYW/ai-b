package com.aibi.module.theme.controller;

import com.aibi.common.response.R;
import com.aibi.module.theme.entity.ThemeConfig;
import com.aibi.module.theme.service.ThemeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主题公开只读接口（配置化升级 V2，免认证）：公开分享页/大屏取全局内置主题令牌。
 *
 * <p>路径位于 {@code /api/public/**}，已在 {@code SecurityConfig} 放行；仅返回全局预设，不含租户私有主题。
 */
@RestController
@RequestMapping("/api/public/theme")
public class PublicThemeController {

    private final ThemeService themeService;

    public PublicThemeController(ThemeService themeService) {
        this.themeService = themeService;
    }

    /** 全局内置主题列表。 */
    @GetMapping("/list")
    public R<List<ThemeConfig>> list() {
        return R.ok(themeService.listPublic());
    }

    /**
     * 集合根路径（{@code GET /api/public/theme}，无子路径）：与 {@link #list()} 等价返回全局内置主题列表。
     * 避免裸路径因无映射处理器而返回 500（Spring 对未匹配的处理器方法默认抛出）。
     */
    @GetMapping
    public R<List<ThemeConfig>> index() {
        return R.ok(themeService.listPublic());
    }

    /** 按 code 取全局内置主题。 */
    @GetMapping("/{code}")
    public R<ThemeConfig> get(@PathVariable String code) {
        return R.ok(themeService.getPublic(code));
    }
}
