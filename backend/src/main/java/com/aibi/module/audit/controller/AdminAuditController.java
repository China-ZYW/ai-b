package com.aibi.module.audit.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.PageResult;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.dto.AuditLogVO;
import com.aibi.module.audit.dto.AuditQuery;
import com.aibi.module.audit.nlquery.AuditNlQueryService;
import com.aibi.module.audit.nlquery.AuditNlRequest;
import com.aibi.module.audit.nlquery.AuditNlResult;
import com.aibi.module.audit.service.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台后台 · 审计日志接口（§9.2 治理组，跨租户检索 + 导出）。
 *
 * <p>受 {@code SecurityConfig} 的 {@code /api/admin/**} 域门禁 + 方法级 {@code admin:audit:read}
 * （{@code platformOnly=true}）双重保护。权限矩阵 {@code YNYN} → 仅 SUPER_ADMIN / AUDITOR 可访问，
 * 且必须是平台域身份（{@code tenantId==0}），组织身份无法越权检索全平台审计。
 *
 * <p>全部为只读投影，不修改任何审计行（审计只增不改不删，保留期由归档任务处理，见设计文档 10.2）。
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private final AuditService auditService;
    private final AuditNlQueryService nlQueryService;

    public AdminAuditController(AuditService auditService, AuditNlQueryService nlQueryService) {
        this.auditService = auditService;
        this.nlQueryService = nlQueryService;
    }

    /**
     * 跨租户分页检索审计日志。全部过滤条件可空（空则不约束），按时间倒序。
     *
     * @param from 起始时间（含），格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param to   截止时间（含），格式同上
     */
    @GetMapping
    @RequiresPermission(value = "admin:audit:read", platformOnly = true)
    public R<PageResult<AuditLogVO>> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long uid,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return R.ok(auditService.search(new AuditQuery(tenantId, action, uid, result, from, to, limit, offset)));
    }

    /**
     * 导出（同检索条件，一次性至多 {@link AuditService#EXPORT_MAX} 条），前端据此生成 CSV。
     *
     * <p>§10.2「审计查询自身也记审计」：每次导出一条 {@code AUDIT_EXPORT} 事件（记过滤条件与导出行数），
     * 防止「看日志的人无人看」。该自审事件在业务事务之外立即异步写入。
     */
    @GetMapping("/export")
    @RequiresPermission(value = "admin:audit:read", platformOnly = true)
    public R<List<AuditLogVO>> export(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long uid,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        AuditQuery q = new AuditQuery(tenantId, action, uid, result, from, to, AuditService.EXPORT_MAX, 0);
        List<AuditLogVO> rows = auditService.exportList(q);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rows", rows.size());
        if (action != null && !action.isBlank()) {
            detail.put("action", action);
        }
        if (tenantId != null) {
            detail.put("tenantId", tenantId);
        }
        if (uid != null) {
            detail.put("uid", uid);
        }
        if (result != null && !result.isBlank()) {
            detail.put("result", result);
        }
        auditService.record(AuditAction.AUDIT_EXPORT, AuditAction.RESULT_OK, 0L, null, "AUDIT", null, detail);
        return R.ok(rows);
    }

    /**
     * 审计智能查询（T11-3 平台域）：与组织域同链路，但<b>跨租户</b>检索（tenantId 传 null），
     * 命中行带租户名列。受 {@code /api/admin/**} 域门禁 + {@code admin:audit:read}（platformOnly）
     * 双重保护，仅 SUPER_ADMIN/AUDITOR 平台身份可达；门控 {@code AUDIT / nl-query} 未启用时 403。
     *
     * <p>自审留痕同口径（归属平台域 0L，照既有 export 先例）。
     */
    @PostMapping("/nl-query")
    @RequiresPermission(value = "admin:audit:read", platformOnly = true)
    public R<AuditNlResult> nlQuery(@RequestBody AuditNlRequest req) {
        String question = AuditNlRequest.clamp(req.question());
        if (question == null || question.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请输入查询内容");
        }
        AuditNlResult result = nlQueryService.nlQuery(question, null);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("question", question);
        detail.put("scope", "PLATFORM");
        detail.put("degraded", result.degraded());
        detail.put("hits", result.total());
        if (result.slots() != null && result.slots().action() != null) {
            detail.put("action", result.slots().action());
        }
        auditService.record(AuditAction.AUDIT_NL_QUERY, AuditAction.RESULT_OK, 0L, null, "AUDIT", null, detail);
        return R.ok(result);
    }
}
