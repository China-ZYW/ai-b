package com.aibi.module.audit.controller;

import com.aibi.common.response.PageResult;
import com.aibi.common.response.R;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.dto.OperationLogVO;
import com.aibi.module.audit.dto.OperationQuery;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.audit.service.OperationLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局操作日志接口（V4.4）：请求级自动采集的检索/导出。
 *
 * <p>与 {@link AdminAuditController}/{@link OrgAuditController}（事件审计 {@code auth_audit_log}）同构，
 * 复用其权限点：平台后台用 {@code admin:audit:read}（{@code platformOnly=true}，跨租户）、
 * 组织内用 {@code audit:log:read}（租户范围强制钉死当前上下文）。
 *
 * <p>本接口自身位于 {@code /audit/} 路径下，被 {@code OperationLogInterceptor#shouldSkip} 排除，
 * 因此「查看操作日志」这一行为不会被反复记入操作日志，避免自递归噪音。
 *
 * <p>全部只读投影，不修改任何审计行（审计只增不改不删，见设计文档 10.2）。
 */
@RestController
public class OperationLogController {

    private final OperationLogService operationLogService;
    private final AuditService auditService;

    public OperationLogController(OperationLogService operationLogService, AuditService auditService) {
        this.operationLogService = operationLogService;
        this.auditService = auditService;
    }

    /**
     * 平台后台 · 跨租户分页检索操作日志。全部过滤条件可空（空则不约束），按时间倒序。
     */
    @GetMapping("/api/admin/audit/operations")
    @RequiresPermission(value = "admin:audit:read", platformOnly = true)
    public R<PageResult<OperationLogVO>> adminList(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String uri,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return R.ok(operationLogService.search(
                new OperationQuery(tenantId, module, method, uri, username, from, to, limit, offset)));
    }

    /**
     * 组织内 · 分页检索本组织操作日志。租户范围强制收敛为当前上下文租户（不接受入参，杜绝越权检索）。
     */
    @GetMapping("/api/org/audit/operations")
    @RequiresPermission("audit:log:read")
    public R<PageResult<OperationLogVO>> orgList(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String uri,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Long tenantId = AccessContextHolder.require().requireTenantId();
        return R.ok(operationLogService.search(
                new OperationQuery(tenantId, module, method, uri, username, from, to, limit, offset)));
    }

    /**
     * 平台后台 · 导出操作日志（同检索条件，一次性至多 {@link OperationLogService#EXPORT_MAX} 条）。
     *
     * <p>§10.2「审计查询自身也记审计」：导出一条 {@code AUDIT_EXPORT} 事件（资源类型 OPERATION_LOG），
     * 防止「看操作日志的人无人看」。
     */
    @GetMapping("/api/admin/audit/operations/export")
    @RequiresPermission(value = "admin:audit:read", platformOnly = true)
    public R<List<OperationLogVO>> adminExport(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String uri,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        List<OperationLogVO> rows = operationLogService.exportList(
                new OperationQuery(tenantId, module, method, uri, username, from, to, OperationLogService.EXPORT_MAX, 0));
        recordExport(tenantId, rows.size());
        return R.ok(rows);
    }

    /**
     * 组织内 · 导出本组织操作日志（租户范围强制收敛）。同样记一条自审事件。
     */
    @GetMapping("/api/org/audit/operations/export")
    @RequiresPermission("audit:log:read")
    public R<List<OperationLogVO>> orgExport(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String uri,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        Long tenantId = AccessContextHolder.require().requireTenantId();
        List<OperationLogVO> rows = operationLogService.exportList(
                new OperationQuery(tenantId, module, method, uri, username, from, to, OperationLogService.EXPORT_MAX, 0));
        recordExport(tenantId, rows.size());
        return R.ok(rows);
    }

    /** 自审：导出操作日志记一条事件审计（与权限/登录审计同源 {@code auth_audit_log}）。 */
    private void recordExport(Long tenantId, int rows) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("rows", rows);
            detail.put("scope", tenantId == null || tenantId == 0L ? "PLATFORM" : "ORG");
            auditService.record(AuditAction.AUDIT_EXPORT, AuditAction.RESULT_OK,
                    tenantId == null ? 0L : tenantId, null, "OPERATION_LOG", null, detail);
        } catch (Exception e) {
            // 自审失败不影响导出本身
        }
    }
}
