package com.aibi.module.auth.dto;

/**
 * 用户资料视图对象（对外安全，不含密码哈希）。
 *
 * @param plan    所属租户套餐（FREE/PRO/ENTERPRISE）
 * @param role    平台角色（SUPER_ADMIN=超级管理员 / USER=普通租户用户）
 * @param uiTheme 账号级界面主题偏好（light/dark/system）；为空时前端按 system 处理
 */
public record UserVO(Long id, Long tenantId, String username, String nickname, String email, String plan, String role,
                     String uiTheme) {
}
