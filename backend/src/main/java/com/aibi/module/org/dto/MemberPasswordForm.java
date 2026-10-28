package com.aibi.module.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置成员口令表单（{@code member:password:reset}，矩阵 YYNNNN → 仅 OWNER/ADMIN）。
 *
 * <p>口令走请求体而非查询串（避免落入访问日志与浏览器历史）。服务层用 BCrypt 编码后写
 * {@code user_identity.password_hash}（存量无 identity 者回落 {@code app_user.password_hash}），
 * 并 {@code token_version++} + 吊销该成员全部会话，强制其重新登录。审计 detail <b>绝不记口令明文</b>。
 */
public record MemberPasswordForm(
        @NotBlank(message = "新口令不能为空")
        @Size(min = 6, max = 64, message = "口令长度需在 6-64 位之间") String newPassword) {
}
