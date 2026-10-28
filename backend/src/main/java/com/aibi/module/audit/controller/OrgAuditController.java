package com.aibi.module.audit.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.PageResult;
import com.aibi.common.response.R;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
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
 * 组织内 · 审计日志接口（§9.2 组织设置，只读本组织检索 + 导出）。
 *
 * <p>与平台后台的 {@link AdminAuditController}（{@code admin:audit:read}，跨租户）形成对照：本接口供
 * 租户 OWNER/ADMIN 查看<b>本组织</b>的审计流水。权限点 {@code audit:log:read}（组织矩阵 {@code YYNNNN}
 * → 仅 OWNER/ADMIN），且<b>不接受</b>任何 {@code tenantId} 入参——租户范围一律由
 * {@link AccessContext#requireTenantId()} 从当前登录上下文强制推导，杜绝越权检索他组织审计。
 *
 * <p>挂在 {@code /api/org/**} 域（{@code SecurityConfig} 中为 {@code authenticated()}，非平台门禁），
 * 复用 {@link AuditService} 的检索/导出能力：仅把 {@code AuditQuery.tenantId} 钉死为当前租户。
 * 全部为只读投影，不修改任何审计行（审计只增不改不删，见设计文档 10.2）。
 */
@RestController
@RequestMapping("/api/org/audit")
public class OrgAuditController {

    private final AuditService auditService;
    private final AuditNlQueryService nlQueryService;

    public OrgAuditController(AuditService auditService, AuditNlQueryService nlQueryService) {
        this.auditService = auditService;
        this.nlQueryService = nlQueryService;
    }

    /**
     * 分页检索<b>本组织</b>审计日志。过滤条件可空（空则不约束），按时间倒序；租户范围强制为当前上下文租户。
     *
     * @param from 起始时间（含），格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param to   截止时间（含），格式同上
     */
    @GetMapping
    @RequiresPermission("audit:log:read")
    public R<PageResult<AuditLogVO>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long uid,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Long tenantId = AccessContextHolder.require().requireTenantId();
        return R.ok(auditService.search(new AuditQuery(tenantId, action, uid, result, from, to, limit, offset)));
    }

    /**
     * 导出（同检索条件，一次性至多 {@link AuditService#EXPORT_MAX} 条），前端据此生成 CSV。租户范围同样强制收敛。
     *
     * <p>§10.2「审计查询自身也记审计」：每次导出一条 {@code AUDIT_EXPORT} 事件（归属<b>当前租户</b>，
     * 记过滤条件与导出行数），与平台后台导出口径一致，防止「看日志的人无人看」。
     */
    @GetMapping("/export")
    @RequiresPermission("audit:log:read")
    public R<List<AuditLogVO>> export(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long uid,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        Long tenantId = AccessContextHolder.require().requireTenantId();
        AuditQuery q = new AuditQuery(tenantId, action, uid, result, from, to, AuditService.EXPORT_MAX, 0);
        List<AuditLogVO> rows = auditService.exportList(q);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rows", rows.size());
        detail.put("scope", "ORG");
        if (action != null && !action.isBlank()) {
            detail.put("action", action);
        }
        if (uid != null) {
            detail.put("uid", uid);
        }
        if (result != null && !result.isBlank()) {
            detail.put("result", result);
        }
        auditService.record(AuditAction.AUDIT_EXPORT, AuditAction.RESULT_OK, tenantId, null, "AUDIT", null, detail);
        return R.ok(rows);
    }

    /**
     * 审计智能查询（T11-3）：一句话抽槽位后三源融合检索，返回可编辑的过滤条件与命中行。
     *
     * <p>权限与本域检索完全同门（{@code audit:log:read}，仅 OWNER/ADMIN）；租户范围照旧不接受任何
     * 入参、由登录上下文钉死。门控 {@code AUDIT / nl-query} 默认关（涉 LLM），未启用时 403 拒用；
     * LLM 解析失败不报错而是 {@code degraded} 响应，前端回落人工筛选。
     *
     * <p>§10.2「看日志的人也要被看」延伸：每次 AI 查询落一条 {@code AUDIT_NL_QUERY} 自审事件
     * （原句、降级与否、命中数进 detail，绝不含命中行内容）。
     */
    @PostMapping("/nl-query")
    @RequiresPermission("audit:log:read")
    public R<AuditNlResult> nlQuery(@RequestBody AuditNlRequest req) {
        String question = AuditNlRequest.clamp(req.question());
        if (question == null || question.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请输入查询内容");
        }
        Long tenantId = AccessContextHolder.require().requireTenantId();
        AuditNlResult result = nlQueryService.nlQuery(question, tenantId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("question", question);
        detail.put("scope", "ORG");
        detail.put("degraded", result.degraded());
        detail.put("hits", result.total());
        if (result.slots() != null && result.slots().action() != null) {
            detail.put("action", result.slots().action());
        }
        auditService.record(AuditAction.AUDIT_NL_QUERY, AuditAction.RESULT_OK, tenantId, null,
                "AUDIT", null, detail);
        return R.ok(result);
    }
}
