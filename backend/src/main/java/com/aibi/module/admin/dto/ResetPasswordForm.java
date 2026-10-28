package com.aibi.module.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置密码表单（超管后台）。
 */
public record ResetPasswordForm(
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间") String password) {
}
