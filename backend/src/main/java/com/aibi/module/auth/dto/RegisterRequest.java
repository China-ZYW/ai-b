package com.aibi.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。注册即自助开通一个租户（免费套餐 / POOL 隔离）；
 * 若携带 {@code inviteToken} 且有效，则在邀请所属组织内建账号并自动接受（不再新建租户）。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需 3-32")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需 6-64")
        String password,

        @Size(max = 64, message = "昵称过长")
        String nickname,

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱过长")
        String email,

        /** 邀请令牌：携带时走「注册即加入组织」链路，不为空但无效时注册失败。 */
        String inviteToken) {
}
