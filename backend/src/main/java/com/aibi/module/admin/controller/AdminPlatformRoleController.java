package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.admin.dto.PlatformRoleGrantForm;
import com.aibi.module.admin.dto.PlatformRoleHolderVO;
import com.aibi.module.admin.service.PlatformRoleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台后台 · 平台角色授予/回收接口（§9.2 治理组，唯一写侧治理页）。
 *
 * <p>受 {@code SecurityConfig} 的 {@code /api/admin/**} 域门禁 + 方法级 {@code admin:role:assign}
 * （{@code platformOnly=true}）双重保护。权限矩阵中 {@code admin:role:assign=YNNN}——仅
 * {@code SUPER_ADMIN} 可授出/回收平台角色，故本页对 OPERATOR/AUDITOR/SUPPORT 全程不可见（菜单+路由同源过滤）。
 *
 * <p>平台角色存放于 {@code user_role}（{@code tenant_id=0 / scope_type=PLATFORM}），授予/回收后由服务层
 * {@code evict} 版本缓存即时生效。护栏（不可回收自己/最后一个 SUPER_ADMIN）在服务层强制，前端仅呈现错误。
 */
@RestController
@RequestMapping("/api/admin/platform-roles")
public class AdminPlatformRoleController {

    private final PlatformRoleService platformRoleService;

    public AdminPlatformRoleController(PlatformRoleService platformRoleService) {
        this.platformRoleService = platformRoleService;
    }

    /** 全部平台角色持有者（联 app_user 取账号信息）。 */
    @GetMapping
    @RequiresPermission(value = "admin:role:assign", platformOnly = true)
    public R<List<PlatformRoleHolderVO>> holders() {
        return R.ok(platformRoleService.listHolders());
    }

    /** 授予平台角色给某平台账号。 */
    @PostMapping
    @RequiresPermission(value = "admin:role:assign", platformOnly = true)
    public R<PlatformRoleHolderVO> grant(@RequestBody @Valid PlatformRoleGrantForm form) {
        return R.ok(platformRoleService.grant(form));
    }

    /** 回收一条平台角色授予（按 user_role 行主键）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin:role:assign", platformOnly = true)
    public R<Void> revoke(@PathVariable Long id) {
        platformRoleService.revoke(id);
        return R.ok();
    }
}
