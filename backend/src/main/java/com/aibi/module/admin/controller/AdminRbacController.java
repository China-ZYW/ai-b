package com.aibi.module.admin.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.ApiPermissionRegistry;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.admin.dto.ApiMatrixVO;
import com.aibi.module.admin.dto.PermissionVO;
import com.aibi.module.admin.dto.RoleVO;
import com.aibi.module.rbac.entity.Permission;
import com.aibi.module.rbac.entity.Role;
import com.aibi.module.rbac.entity.RolePermission;
import com.aibi.module.rbac.mapper.PermissionMapper;
import com.aibi.module.rbac.mapper.RoleMapper;
import com.aibi.module.rbac.mapper.RolePermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台后台 · 权限治理只读接口（§9.2 治理组，权限字典 / 接口权限矩阵）。
 *
 * <p>受 {@code SecurityConfig} 的 {@code /api/admin/**} 域门禁 + 方法级 {@code admin:rbac:read}
 * （{@code platformOnly=true}）双重保护。全部为只读投影，不改动任何字典——权限点/角色矩阵的
 * <b>编辑</b>属 {@code admin:rbac:write}，另行落地，避免治理页误改单一事实源。
 *
 * <p>三个字典表（permission/role/role_permission）均为全局表、已列入租户拦截器 IGNORE 名单，
 * 故此处查询不含 {@code tenant_id}，读取的是全平台共享的同一份定义。
 */
@RestController
@RequestMapping("/api/admin/rbac")
public class AdminRbacController {

    private final PermissionMapper permissionMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final ApiPermissionRegistry apiPermissionRegistry;

    public AdminRbacController(PermissionMapper permissionMapper,
                               RoleMapper roleMapper,
                               RolePermissionMapper rolePermissionMapper,
                               ApiPermissionRegistry apiPermissionRegistry) {
        this.permissionMapper = permissionMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.apiPermissionRegistry = apiPermissionRegistry;
    }

    /** 权限点字典（全部已启用项，按 module/sort 排序）。 */
    @GetMapping("/permissions")
    @RequiresPermission(value = "admin:rbac:read", platformOnly = true)
    public R<List<PermissionVO>> permissions() {
        List<PermissionVO> list = new ArrayList<>();
        for (Permission p : permissionMapper.selectEnabled()) {
            list.add(new PermissionVO(p.getCode(), p.getName(), p.getModule(), p.getLevel(), p.getType(),
                    p.getMenuCode(), p.getApiMethod(), p.getApiPattern(), p.getBuiltin(), p.getEnabled(),
                    p.getSort(), p.getDescription()));
        }
        return R.ok(list);
    }

    /** 角色字典（含平台/组织/团队三层，按 sort 排序，作为权限矩阵列头）。 */
    @GetMapping("/roles")
    @RequiresPermission(value = "admin:rbac:read", platformOnly = true)
    public R<List<RoleVO>> roles() {
        List<Role> roles = roleMapper.selectList(new QueryWrapper<Role>().orderByAsc("sort", "id"));
        List<RoleVO> list = new ArrayList<>();
        for (Role r : roles) {
            list.add(new RoleVO(r.getCode(), r.getName(), r.getLevel(), r.getDataScope(), r.getInheritToChild(),
                    r.getBuiltin(), r.getEnabled(), r.getSort(), r.getDescription()));
        }
        return R.ok(list);
    }

    /**
     * 角色→权限点矩阵：{@code roleCode → [permissionCode...]}。
     *
     * <p>一次性取回全部关联行后在内存按角色分组，避免逐角色 N+1 查询。前端据此渲染
     * 「权限点 × 角色」勾选矩阵（只读）。
     */
    @GetMapping("/matrix")
    @RequiresPermission(value = "admin:rbac:read", platformOnly = true)
    public R<Map<String, List<String>>> matrix() {
        Map<String, List<String>> matrix = new LinkedHashMap<>();
        for (RolePermission rp : rolePermissionMapper.selectList(null)) {
            matrix.computeIfAbsent(rp.getRoleCode(), k -> new ArrayList<>()).add(rp.getPermissionCode());
        }
        return R.ok(matrix);
    }

    /**
     * 接口权限矩阵（§6.4 扫描结果）：全部接口的权限画像 + fail-closed 域未标注告警统计。
     *
     * <p>消费 {@link ApiPermissionRegistry#snapshot()} 的启动期扫描结果，逐接口标注是否已挂权限点、
     * 是否落在 fail-closed 域。{@code unannotatedFailClosed>0} 即存在越权风险的漏配接口。
     */
    @GetMapping("/api-matrix")
    @RequiresPermission(value = "admin:rbac:read", platformOnly = true)
    public R<ApiMatrixVO> apiMatrix() {
        List<ApiPermissionRegistry.ApiEndpoint> snapshot = apiPermissionRegistry.snapshot();
        List<ApiMatrixVO.Row> rows = new ArrayList<>(snapshot.size());
        int annotated = 0;
        int unannotatedFailClosed = 0;
        for (ApiPermissionRegistry.ApiEndpoint e : snapshot) {
            boolean isAnnotated = e.permissionCodes() != null && !e.permissionCodes().isEmpty();
            boolean failClosed = apiPermissionRegistry.isFailClosed(e.pattern());
            if (isAnnotated) {
                annotated++;
            } else if (failClosed) {
                unannotatedFailClosed++;
            }
            rows.add(new ApiMatrixVO.Row(e.httpMethod(), e.pattern(), e.controller(), e.handler(),
                    e.permissionCodes(), e.resourceType(), isAnnotated, failClosed));
        }
        return R.ok(new ApiMatrixVO(rows.size(), annotated, unannotatedFailClosed,
                apiPermissionRegistry.failClosedDomains(), rows));
    }
}
