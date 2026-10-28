package com.aibi.module.fewshot.controller;

import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.fewshot.entity.FewShotExample;
import com.aibi.module.fewshot.service.FewShotService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问数样例库管理接口（配置化升级 V2）：curated "问法 → SQL" 样例的列表 / 新建 / 编辑 / 删除。
 * 租户隔离由拦截器保证（few_shot_example 为租户隔离表）。
 */
@RestController
@RequestMapping("/api/fewshot")
public class FewShotController {

    private final FewShotService fewShotService;

    public FewShotController(FewShotService fewShotService) {
        this.fewShotService = fewShotService;
    }

    /** 样例列表（datasourceId 可选）。 */
    @GetMapping
    @RequiresPermission("fewshot:example:read")
    public R<List<FewShotExample>> list(@RequestParam(required = false) Long datasourceId) {
        return R.ok(fewShotService.list(datasourceId));
    }

    /** 新建样例。 */
    @PostMapping
    @RequiresPermission("fewshot:example:write")
    public R<FewShotExample> create(@RequestBody FewShotExample form) {
        if (form.getQuestion() == null || form.getQuestion().isBlank()
                || form.getSqlText() == null || form.getSqlText().isBlank()) {
            return R.fail(ErrorCode.PARAM_ERROR, "问题与 SQL 不能为空");
        }
        return R.ok(fewShotService.create(form));
    }

    /** 编辑样例。 */
    @PutMapping
    @RequiresPermission("fewshot:example:write")
    public R<FewShotExample> update(@RequestBody FewShotExample form) {
        if (form.getId() == null) {
            return R.fail(ErrorCode.PARAM_ERROR, "缺少样例 id");
        }
        FewShotExample updated = fewShotService.update(form);
        return updated == null ? R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "样例不存在或无权访问") : R.ok(updated);
    }

    /** 删除样例。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("fewshot:example:delete")
    public R<Void> delete(@PathVariable Long id) {
        return fewShotService.delete(id) ? R.ok() : R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "样例不存在或无权访问");
    }
}
