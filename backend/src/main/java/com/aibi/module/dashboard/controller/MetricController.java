package com.aibi.module.dashboard.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.CheckResourceAccess;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.dashboard.dto.MetricDataVO;
import com.aibi.module.dashboard.dto.DashboardMoveForm;
import com.aibi.module.dashboard.dto.MetricForm;
import com.aibi.module.dashboard.dto.MetricVO;
import com.aibi.module.dashboard.service.MetricService;
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
 * 指标接口（Feature 2）：保存问数结果为可复用指标 + CRUD + 实时刷新。
 *
 * <p>全部操作以当前租户为边界（{@code metric} 自动注入 tenant_id）。{@code POST /{id}/refresh} 重跑已存 SQL，
 * <b>不消耗 LLM、不计入问数配额</b>，供指标预览与驾驶舱 widget 取实时数据。
 *
 * <p><b>权限点（L2）</b>：指标是可执行 SQL 的业务对象（刷新即按已存 SQL 查业务库），故读写闸门必须闭合。
 * 此前 {@code GET} 与 {@code POST}（新建）无任何注解，{@code PUT}/{@code DELETE} 仅靠
 * {@code @CheckResourceAccess}——而资源 ACL 对无授权行的资源是 fail-open，等于只读角色也能改删任意指标。
 * 现按权限字典补齐，权限码与前端按钮门禁（{@code can('metric:item:write')} 等）同名同义。
 */
@RestController
@RequestMapping("/api/metric")
public class MetricController {

    private final MetricService metricService;

    public MetricController(MetricService metricService) {
        this.metricService = metricService;
    }

    @GetMapping
    @RequiresPermission("metric:item:read")
    public R<List<MetricVO>> list() {
        return R.ok(metricService.list());
    }

    @GetMapping("/{id}")
    @RequiresPermission("metric:item:read")
    public R<MetricVO> get(@PathVariable Long id) {
        return R.ok(metricService.get(id));
    }

    @PostMapping
    @RequiresPermission("metric:item:write")
    public R<MetricVO> create(@RequestBody @Valid MetricForm form) {
        return R.ok(metricService.create(form));
    }

    @PutMapping("/{id}")
    @RequiresPermission("metric:item:write")
    @CheckResourceAccess(type = "METRIC", idParam = "id", permission = "WRITE")
    public R<MetricVO> update(@PathVariable Long id, @RequestBody @Valid MetricForm form) {
        return R.ok(metricService.update(id, form));
    }

    /**
     * 移动指标到指定分组（{@code groupId} 空=移出分组）。
     *
     * <p>分组 CRUD 在 {@code MetricGroupController}（{@code /api/metric/group}）；本端点只管单个指标的归属。
     * 与驾驶舱侧同构：走专用端点而非并入全量 update，避免编辑器 payload 不携带分组而把归属清空。
     */
    @PutMapping("/{id}/group")
    @RequiresPermission("metric:item:write")
    public R<MetricVO> moveGroup(@PathVariable Long id, @RequestBody DashboardMoveForm form) {
        return R.ok(metricService.moveToGroup(id, form == null ? null : form.groupId()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission("metric:item:delete")
    @CheckResourceAccess(type = "METRIC", idParam = "id", permission = "WRITE")
    public R<Void> delete(@PathVariable Long id) {
        metricService.delete(id);
        return R.ok();
    }

    /** 实时刷新：重跑已存 SQL 返回最新数据（不消耗 LLM、不计入配额）。 */
    @PostMapping("/{id}/refresh")
    @RequiresPermission("metric:item:refresh")
    public R<MetricDataVO> refresh(@PathVariable Long id) {
        return R.ok(metricService.refresh(id));
    }
}
