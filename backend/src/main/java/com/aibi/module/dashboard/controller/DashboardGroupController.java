package com.aibi.module.dashboard.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.dashboard.dto.DashboardGroupForm;
import com.aibi.module.dashboard.dto.DashboardGroupVO;
import com.aibi.module.dashboard.service.ResourceGroupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 驾驶舱分组接口（多层级目录树）：扁平列表 + 增删改，前端据此组树渲染侧边栏。
 *
 * <p><b>只用 {@code DASHBOARD} 域</b>：底层表与服务是驾驶舱、指标库<b>共用</b>的
 * （{@code resource_group} + {@link ResourceGroupService}），本控制器固定传
 * {@link ResourceGroupService#TYPE_DASHBOARD}，指标侧由 {@code MetricGroupController} 用
 * {@code METRIC} 域——两棵树彼此隔离，互不越界。
 *
 * <p><b>路径挂在 {@code /api/dashboard/group} 而非 {@code /api/dashboard-group}</b>：
 * fail-closed 域清单按 Ant 通配匹配，{@code /api/dashboard/**} 罩不住 {@code /api/dashboard-group/**}，
 * 漏域即静默失效；同挂一处则天然继承该域的扫描与告警。
 * 与 {@code DashboardController} 的 {@code GET /api/dashboard/{id}} 不冲突——字面量段优先于路径变量。
 *
 * <p><b>权限点（L2）</b>：全部复用驾驶舱既有码（不发明新码）——读 {@code dashboard:item:read}、
 * 写 {@code dashboard:item:write}、删 {@code dashboard:item:delete}，与前端侧边栏按钮门禁同名同义。
 */
@RestController
@RequestMapping("/api/dashboard/group")
public class DashboardGroupController {

    private final ResourceGroupService groupService;

    public DashboardGroupController(ResourceGroupService groupService) {
        this.groupService = groupService;
    }

    /** 全量分组（扁平列表，前端组树）。 */
    @GetMapping
    @RequiresPermission("dashboard:item:read")
    public R<List<DashboardGroupVO>> list() {
        return R.ok(groupService.list(ResourceGroupService.TYPE_DASHBOARD));
    }

    /** 新建分组（{@code parentId} 空=根级）。 */
    @PostMapping
    @RequiresPermission("dashboard:item:write")
    public R<DashboardGroupVO> create(@RequestBody @Valid DashboardGroupForm form) {
        return R.ok(groupService.create(ResourceGroupService.TYPE_DASHBOARD, form));
    }

    /** 重命名 / 移动分组（可整体搬移子树；禁止移到自身或后代之下）。 */
    @PutMapping("/{id}")
    @RequiresPermission("dashboard:item:write")
    public R<DashboardGroupVO> update(@PathVariable Long id, @RequestBody @Valid DashboardGroupForm form) {
        return R.ok(groupService.update(ResourceGroupService.TYPE_DASHBOARD, id, form));
    }

    /** 删除分组（仅限空分组：无子分组且无驾驶舱归属）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("dashboard:item:delete")
    public R<Void> delete(@PathVariable Long id) {
        groupService.delete(ResourceGroupService.TYPE_DASHBOARD, id);
        return R.ok();
    }
}
