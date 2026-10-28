package com.aibi.module.auth.dto;

/**
 * 认证响应：令牌 + 用户资料。前端存 token 后续请求置于 {@code Authorization: Bearer <token>}。
 *
 * @param token     JWT 访问令牌
 * @param tokenType 令牌类型（固定 Bearer）
 * @param expiresIn 有效期（秒）
 * @param user      登录用户资料
 */
public record AuthResponse(String token, String tokenType, long expiresIn, UserVO user) {
}
