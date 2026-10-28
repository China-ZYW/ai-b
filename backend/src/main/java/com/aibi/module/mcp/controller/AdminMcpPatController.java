package com.aibi.module.mcp.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.mcp.dto.McpPatCreated;
import com.aibi.module.mcp.dto.McpPatVO;
import com.aibi.module.mcp.service.McpPatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台后台「MCP 令牌」接口（{@code /api/admin/mcp-pats}）：按组织签发 / 列出 / 吊销对外 MCP 的个人访问令牌。
 *
 * <p>令牌<b>绑定到具体组织的具体用户</b>：调用时以该用户的真实角色执行，权限、行级范围、字段脱敏、
 * 配额、审计全部照常生效。平台代签只是省去"让对方自己点一下"，不改变安全语义。
 *
 * <p><b>明文只在创建响应里出现一次</b>：库里只有 SHA-256 哈希，故后端也无法再次展示，
 * 前端必须提示"请立即保存"。吊销立即生效（每次调用都重新读行，无缓存）。
 */
@RestController
@RequestMapping("/api/admin/mcp-pats")
public class AdminMcpPatController {

    private final McpPatService patService;

    public AdminMcpPatController(McpPatService patService) {
        this.patService = patService;
    }

    /** 某组织的令牌列表。 */
    @GetMapping
    @RequiresPermission(value = "admin:mcp:read", platformOnly = true)
    public R<List<McpPatVO>> list(@RequestParam Long tenantId) {
        return R.ok(patService.listByTenant(tenantId));
    }

    /** 签发请求体。 */
    public record CreateForm(Long tenantId, Long uid, String name, Integer days) {
    }

    /** 签发一枚令牌；响应里的 token 是明文，仅此一次。 */
    @PostMapping
    @RequiresPermission(value = "admin:mcp:write", platformOnly = true)
    public R<McpPatCreated> create(@RequestBody CreateForm form) {
        if (form == null || form.tenantId() == null || form.uid() == null) {
            return R.fail(com.aibi.common.exception.ErrorCode.PARAM_ERROR, "请选择组织与所属用户");
        }
        return R.ok(patService.createFor(form.tenantId(), form.uid(), form.name(), form.days()));
    }

    /** 吊销（幂等）。 */
    @PostMapping("/{id}/revoke")
    @RequiresPermission(value = "admin:mcp:write", platformOnly = true)
    public R<Void> revoke(@PathVariable Long id) {
        patService.revokeAny(id);
        return R.ok(null);
    }
}
