package com.aibi.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * 更新个人资料请求（昵称 / 邮箱 / 界面主题）。字段为 null 表示不修改。
 *
 * <p>{@code uiTheme} 只接受 {@code light}/{@code dark}/{@code system} 三值：这是持久化到
 * {@code app_user.ui_theme} 再回读到前端 {@code <html>} class 的值，属于"会被浏览器解释"的
 * 输入，必须白名单收敛——否则非法值会让前端拿到未知模式并回落到不确定表现。
 */
public record UpdateProfileRequest(
        @Size(max = 64, message = "昵称过长") String nickname,
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱过长") String email,
        @Pattern(regexp = "light|dark|system", message = "界面主题只能为 light/dark/system") String uiTheme) {

    /** 合法主题值集合（服务层校验与缺省回落共用，避免两处各写一份字面量）。 */
    public static final Set<String> UI_THEMES = Set.of("light", "dark", "system");
}
