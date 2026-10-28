package com.aibi.module.report.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.report.dto.ReportDtos;
import com.aibi.module.report.service.ReportService;
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
 * 定时报表接口（T12-1，{@code /api/reports}）。
 *
 * <p><b>权限复用的取舍</b>（同 {@code QueryController} 口径，不新建权限点）：执行类动作
 * （创建/更新/删除/立即跑）复用 {@code chat:query:execute}——能保存并运行模板的人才配得起定时化；
 * 查看类复用 {@code history:query:read}。新增权限点会抬 {@code perm_version} 逼全员重登，无额外管控价值。
 *
 * <p><b>门控</b>：{@code ASK / schedule}（默认开）关闭则全域拒用（业务码 40301）；
 * 归属边界在服务层——所有操作仅属主（uid 比对），跨租户由拦截器硬挡。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final AibiConfig aibiConfig;

    public ReportController(ReportService reportService, AibiConfig aibiConfig) {
        this.reportService = reportService;
        this.aibiConfig = aibiConfig;
    }

    /** 我的报表列表（含上轮执行状态）。 */
    @GetMapping
    @RequiresPermission("history:query:read")
    public R<List<ReportDtos.ReportVO>> list() {
        assertEnabled();
        return R.ok(reportService.listMine(currentUid()));
    }

    /** 创建报表任务。 */
    @PostMapping
    @RequiresPermission("chat:query:execute")
    public R<Long> create(@RequestBody @Valid ReportDtos.ReportUpsertCmd cmd) {
        assertEnabled();
        return R.ok(reportService.create(cmd, currentTenantId(), currentUid()));
    }

    /** 覆盖式更新（启停并入）。 */
    @PutMapping("/{id}")
    @RequiresPermission("chat:query:execute")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid ReportDtos.ReportUpsertCmd cmd) {
        assertEnabled();
        reportService.update(id, cmd, currentUid());
        return R.ok();
    }

    /** 删除报表任务。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("chat:query:execute")
    public R<Void> delete(@PathVariable Long id) {
        assertEnabled();
        reportService.delete(id, currentUid());
        return R.ok();
    }

    /** 手动立即执行（与调度同一条 runOnce 链路，验证入口）。 */
    @PostMapping("/{id}/run-now")
    @RequiresPermission("chat:query:execute")
    public R<String> runNow(@PathVariable Long id) {
        assertEnabled();
        return R.ok(reportService.runNow(id, currentUid()));
    }

    private void assertEnabled() {
        if (!aibiConfig.scheduleEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "定时报表未启用（ASK / schedule），请联系管理员开启");
        }
    }

    private Long currentUid() {
        return AccessContextHolder.require().uid();
    }

    private Long currentTenantId() {
        return AccessContextHolder.require().tenantId();
    }
}
