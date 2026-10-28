package com.aibi.module.auth.controller;

import com.aibi.common.response.R;
import com.aibi.module.auth.dto.AuthResponse;
import com.aibi.module.auth.dto.MyTenantVO;
import com.aibi.module.auth.service.IdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多组织身份接口（C 缺口）：我的组织列表 + 组织切换。
 *
 * <p>均要求已登录（落在 {@code SecurityConfig} 的 {@code anyRequest().authenticated()}），
 * 租户范围由 {@code JwtAuthFilter} 从令牌还原，无需额外参数。组织切换 = 为当前身份重新签发令牌，
 * 而非请求期改租户，避免引入可被客户端伪造的租户头。
 */
@RestController
@RequestMapping("/api/auth")
public class IdentityController {

    private final IdentityService identityService;

    public IdentityController(IdentityService identityService) {
        this.identityService = identityService;
    }

    /** 当前自然人身份可切换的组织列表（组织切换器数据源）。 */
    @GetMapping("/me/tenants")
    public R<List<MyTenantVO>> myTenants() {
        return R.ok(identityService.listMyTenants());
    }

    /** 切换到当前身份的另一个成员组织，返回新令牌（前端换存后刷新权限快照）。 */
    @PostMapping("/switch-tenant")
    public R<AuthResponse> switchTenant(@RequestBody SwitchTenantRequest request) {
        return R.ok(identityService.switchTenant(request.tenantId()));
    }

    /** 切换组织请求体。 */
    public record SwitchTenantRequest(Long tenantId) {
    }
}
