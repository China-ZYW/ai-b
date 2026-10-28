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
 * 指标库分组接口（多层级目录树）：与驾驶舱<b>共用</b> {@code resource_group} 表和
 * {@link ResourceGroupService}，本控制器固定传 {@link ResourceGroupService#TYPE_METRIC}，
 * 因此指标树与驾驶舱树彼此独立、互不越界。
 *
 * <p><b>路径挂在 {@code /api/metric/group}</b>：与驾驶舱侧对称，天然继承 {@code /api/metric/**}
 * 的 fail-closed 域扫描与告警；与 {@code MetricController} 的 {@code GET /api/metric/{id}}
 * 不冲突——字面量段优先于路径变量。
 *
 * <p><b>权限点（L2）</b>：全部复用指标库既有码（不发明新码）——读 {@code metric:item:read}、
 * 写 {@code metric:item:write}、删 {@code metric:item:delete}，与前端按钮门禁同名同义。
 */
@RestController
@RequestMapping("/api/metric/group")
public class MetricGroupController {

    private final ResourceGroupService groupService;

    public MetricGroupController(ResourceGroupService groupService) {
        this.groupService = groupService;
    }

    /** 全量分组（扁平列表，前端组树）。 */
    @GetMapping
    @RequiresPermission("metric:item:read")
    public R<List<DashboardGroupVO>> list() {
        return R.ok(groupService.list(ResourceGroupService.TYPE_METRIC));
    }

    /** 新建分组（{@code parentId} 空=根级）。 */
    @PostMapping
    @RequiresPermission("metric:item:write")
    public R<DashboardGroupVO> create(@RequestBody @Valid DashboardGroupForm form) {
        return R.ok(groupService.create(ResourceGroupService.TYPE_METRIC, form));
    }

    /** 重命名 / 移动分组（可整体搬移子树；禁止移到自身或后代之下）。 */
    @PutMapping("/{id}")
    @RequiresPermission("metric:item:write")
    public R<DashboardGroupVO> update(@PathVariable Long id, @RequestBody @Valid DashboardGroupForm form) {
        return R.ok(groupService.update(ResourceGroupService.TYPE_METRIC, id, form));
    }

    /** 删除分组（仅限空分组：无子分组且无指标归属）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("metric:item:delete")
    public R<Void> delete(@PathVariable Long id) {
        groupService.delete(ResourceGroupService.TYPE_METRIC, id);
        return R.ok();
    }
}
