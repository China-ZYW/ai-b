package com.aibi.module.auth.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.auth.dto.AuthResponse;
import com.aibi.module.auth.dto.LoginRequest;
import com.aibi.module.auth.dto.RegisterRequest;
import com.aibi.module.auth.dto.UpdateProfileRequest;
import com.aibi.module.auth.dto.UserVO;
import com.aibi.module.auth.service.UserService;
import com.aibi.module.rbac.dto.PermissionsView;
import com.aibi.module.rbac.service.PermissionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与用户接口（T1.4）：注册 / 登录 / 登出 / 当前用户 / 更新资料。
 *
 * <p>已由演示登录升级为真实用户模块：注册自助开通租户（FREE/POOL），密码 BCrypt 存储，
 * 令牌携带完整角色与会话 claim 支撑多租户隔离与权限判定。{@code login/register} 在
 * {@code SecurityConfig} 中放行，{@code me/logout} 需携带有效令牌。
 *
 * <p>{@code logout} 已是<b>真吊销</b>：置 {@code login_session.revoked_at}，后续携同一令牌的请求
 * 由 {@code JwtAuthFilter} 直接回 401 + {@code 40311}。前端仍需丢弃本地令牌（否则每个请求都会多一次 401 往返）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final PermissionService permissionService;

    public AuthController(UserService userService, PermissionService permissionService) {
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @PostMapping("/register")
    public R<AuthResponse> register(@RequestBody @Valid RegisterRequest request) {
        return R.ok(userService.register(request));
    }

    @PostMapping("/login")
    public R<AuthResponse> login(@RequestBody @Valid LoginRequest request) {
        return R.ok(userService.login(request));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        userService.logout();
        return R.ok();
    }

    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(userService.currentUser());
    }

    @PutMapping("/me")
    public R<UserVO> updateProfile(@RequestBody @Valid UpdateProfileRequest request) {
        return R.ok(userService.updateProfile(request));
    }

    /**
     * 当前上下文的有效权限快照（P1，见《角色权限体系与组织协同设计》6.9 / 11.1）。
     *
     * <p>前端登录/刷新后拉取一次，据此驱动动态菜单、按钮显隐与数据范围提示；
     * 只要求认证（落在 {@code SecurityConfig} 的 {@code anyRequest().authenticated()}），
     * 不需额外权限点——它返回的正是"当前身份能做什么"本身。
     */
    @GetMapping("/permissions")
    public R<PermissionsView> permissions() {
        return R.ok(permissionService.buildView(AccessContextHolder.require()));
    }
}
