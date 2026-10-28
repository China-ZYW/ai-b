package com.aibi.module.alert.controller;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.PageResult;
import com.aibi.common.response.R;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.alert.dto.AlertAttributionVO;
import com.aibi.module.alert.dto.AlertRuleForm;
import com.aibi.module.alert.dto.AlertRecordVO;
import com.aibi.module.alert.dto.AlertRuleVO;
import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.mapper.AlertRecordMapper;
import com.aibi.module.alert.service.AlertEngineService;
import com.aibi.module.alert.service.AlertRuleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 组织预警接口（§9.2 组织设置 / 智能预警，挂 {@code /api/org/alerts}）。
 *
 * <p><b>双重门禁</b>：① 方法级 {@link RequiresPermission} 按组织矩阵收敛（{@code alert:rule:*}/{@code alert:record:read}/
 * {@code alert:detect:execute} 均为 YYNNNN → 仅 OWNER/ADMIN），与本域 {@code /api/org/**} 的 fail-closed 治理口径对齐；
 * ② 服务层/控制器内 {@code assertManager()} 兜底（OWNER/ADMIN），即便权限字典被误改也不会放开越权。
 * 全部接口租户范围由 {@link AccessContextHolder#require()} 强制收敛，杜绝越权检索他组织规则/记录。
 *
 * <p>检测既可手动触发（{@code POST /detect}，对本组织跑一次），也由引擎 {@code @Scheduled} 全平台定时扫描。
 */
@RestController
@RequestMapping("/api/org/alerts")
public class AlertRuleController {

    private final AlertRuleService ruleService;
    private final AlertEngineService engineService;
    private final AlertRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    public AlertRuleController(AlertRuleService ruleService, AlertEngineService engineService,
                               AlertRecordMapper recordMapper, ObjectMapper objectMapper) {
        this.ruleService = ruleService;
        this.engineService = engineService;
        this.recordMapper = recordMapper;
        this.objectMapper = objectMapper;
    }

    private void assertManager() {
        AccessContext ctx = AccessContextHolder.require();
        if (!ctx.canManageMembers()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅组织管理员可管理预警");
        }
    }

    @GetMapping("/rules")
    @RequiresPermission("alert:rule:read")
    public R<List<AlertRuleVO>> listRules() {
        assertManager();
        return R.ok(ruleService.list().stream().map(AlertRuleVO::from).collect(Collectors.toList()));
    }

    @PostMapping("/rules")
    @RequiresPermission("alert:rule:write")
    public R<AlertRuleVO> createRule(@RequestBody AlertRuleForm form) {
        assertManager();
        return R.ok(AlertRuleVO.from(ruleService.create(form)));
    }

    @PutMapping("/rules/{id}")
    @RequiresPermission("alert:rule:write")
    public R<AlertRuleVO> updateRule(@PathVariable Long id, @RequestBody AlertRuleForm form) {
        assertManager();
        return R.ok(AlertRuleVO.from(ruleService.update(id, form)));
    }

    @DeleteMapping("/rules/{id}")
    @RequiresPermission("alert:rule:delete")
    public R<Void> deleteRule(@PathVariable Long id) {
        assertManager();
        ruleService.delete(id);
        return R.ok();
    }

    @PostMapping("/rules/{id}/enable")
    @RequiresPermission("alert:rule:write")
    public R<AlertRuleVO> enableRule(@PathVariable Long id) {
        assertManager();
        return R.ok(AlertRuleVO.from(ruleService.setEnabled(id, true)));
    }

    @PostMapping("/rules/{id}/disable")
    @RequiresPermission("alert:rule:write")
    public R<AlertRuleVO> disableRule(@PathVariable Long id) {
        assertManager();
        return R.ok(AlertRuleVO.from(ruleService.setEnabled(id, false)));
    }

    /**
     * 归因试跑（P1）：用表单里的指标键与维度**立即预跑一次归因**，不落库、不触发告警。
     *
     * <p>用途：管理员在开启「触发时自动归因」之前，先确认"这条规则真的能算出归因结论"
     * （维度是否可用、指标是否沉淀了口径、置信度够不够），避免开启后才发现每次都空跑。
     * 与真实触发共用 {@link AlertEngineService#preRunAttribution} 同一条代码路径，故试跑结果即线上结果。
     *
     * <p>权限复用 {@code alert:detect:execute}（执行类动作，OWNER/ADMIN）——试跑会真实查询业务库，
     * 且可能消耗 LLM 配额润色解读，故不放开给普通成员。
     */
    @PostMapping("/rules/preview-attribution")
    @RequiresPermission("alert:detect:execute")
    public R<AlertAttributionVO> previewAttribution(@RequestBody AlertRuleForm form) {
        assertManager();
        String metricKey = form == null ? null : form.metricKey();
        String dimension = form == null ? null : form.attributionDimension();
        return R.ok(engineService.preRunAttribution(metricKey, dimension));
    }

    /** 手动对本组织跑一次检测，返回触发条数（定时扫描亦会周期性执行）。 */
    @PostMapping("/detect")
    @RequiresPermission("alert:detect:execute")
    public R<Map<String, Object>> detect() {
        assertManager();
        Long tid = AccessContextHolder.require().requireTenantId();
        int fired = engineService.detectTenant(tid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tid);
        result.put("fired", fired);
        return R.ok(result);
    }

    /** 触发记录列表（按时间倒序，可据 ruleId 过滤）。租户范围由拦截器自动收敛。 */
    @GetMapping("/records")
    @RequiresPermission("alert:record:read")
    public R<PageResult<AlertRecordVO>> records(@RequestParam(required = false) Long ruleId,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "0") int offset) {
        assertManager();
        int l = Math.min(Math.max(limit, 1), 200);
        List<AlertRecordVO> items = recordMapper.selectList(
                        new LambdaQueryWrapper<AlertRecord>()
                                .eq(ruleId != null, AlertRecord::getRuleId, ruleId)
                                .orderByDesc(AlertRecord::getCreatedAt)
                                .last("LIMIT " + l + " OFFSET " + offset))
                .stream().map(r -> AlertRecordVO.from(r, objectMapper)).collect(Collectors.toList());
        long total = recordMapper.selectCount(
                new LambdaQueryWrapper<AlertRecord>().eq(ruleId != null, AlertRecord::getRuleId, ruleId));
        return R.ok(PageResult.of(items, total, l, offset));
    }
}
