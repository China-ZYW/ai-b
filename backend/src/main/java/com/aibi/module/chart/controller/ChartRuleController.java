package com.aibi.module.chart.controller;

import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.chart.entity.ChartRule;
import com.aibi.module.chart.service.ChartRuleService;
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
 * 图表规则管理接口（配置化升级 V2，需认证）：列出生效规则 / 新建 / 编辑 / 删除。
 *
 * <p>{@code chart_rule} 的 tenant_id 可空，本控制器写入一律落本租户行。
 * <b>覆盖语义（重要）</b>：KEYWORD 规则为「租户整体覆盖全局」——本租户一旦有任何启用 KEYWORD 行，
 * 全局内置种子关键词对该租户<b>全部失效</b>（不是追加）。若只想扩展而非替换，需把要保留的内置规则也一并补回。
 * THRESHOLD 规则为合并（同 key 的全局值被租户值覆盖）。全局内置种子由 DataSeeder 播种、超管经 {@code /api/admin} 通道维护。
 */
@RestController
@RequestMapping("/api/chart-rule")
public class ChartRuleController {

    private final ChartRuleService chartRuleService;

    public ChartRuleController(ChartRuleService chartRuleService) {
        this.chartRuleService = chartRuleService;
    }

    /** 生效范围内的规则（本租户行 + 全局行）。 */
    @GetMapping
    @RequiresPermission("chart:rule:read")
    public R<List<ChartRule>> list() {
        return R.ok(chartRuleService.list());
    }

    /** 新建规则。 */
    @PostMapping
    @RequiresPermission("chart:rule:write")
    public R<ChartRule> create(@RequestBody ChartRule form) {
        form.setId(null);
        ChartRule saved = chartRuleService.save(form);
        return saved == null ? R.fail(ErrorCode.PARAM_ERROR, "规则类型/pattern 不能为空") : R.ok(saved);
    }

    /** 编辑规则。 */
    @PutMapping
    @RequiresPermission("chart:rule:write")
    public R<ChartRule> update(@RequestBody ChartRule form) {
        if (form.getId() == null) {
            return R.fail(ErrorCode.PARAM_ERROR, "缺少规则 id");
        }
        ChartRule saved = chartRuleService.save(form);
        return saved == null ? R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "规则不存在或无权访问") : R.ok(saved);
    }

    /** 删除规则（仅本租户行）。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("chart:rule:write")
    public R<Void> delete(@PathVariable Long id) {
        return chartRuleService.delete(id) ? R.ok()
                : R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "规则不存在或无权删除");
    }
}
