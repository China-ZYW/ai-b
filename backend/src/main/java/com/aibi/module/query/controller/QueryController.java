package com.aibi.module.query.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.query.dto.QueryDtos;
import com.aibi.module.query.service.QueryBuilderService;
import com.aibi.module.query.service.QueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 高级查询 / SQL 编辑器接口（P1）。
 *
 * <p>端点一览：
 * <ul>
 *   <li>{@code POST /api/query/run} — 跑一条 SQL（只读校验 → 表级 ACL 拦截 → 执行 → 脱敏 → 图型）；</li>
 *   <li>{@code GET  /api/query/templates} — 我保存的查询模板；</li>
 *   <li>{@code POST /api/query/templates} — 保存模板（参数名自动从 {@code :param} 解析）；</li>
 *   <li>{@code PUT  /api/query/templates/{id}} — 覆盖式更新模板；</li>
 *   <li>{@code POST /api/query/templates/{id}/run} — 跑模板（参数走 PreparedStatement 绑定）；</li>
 *   <li>{@code DELETE /api/query/templates/{id}} — 删除我的模板；</li>
 *   <li>{@code GET  /api/query/history} — 我最近的执行历史。</li>
 * </ul>
 *
 * <p><b>权限复用的取舍</b>：本模块<b>不新申请权限点</b>——执行类动作复用 {@code chat:query:execute}
 * （"发起问数"），查看类复用 {@code history:query:read}。新增权限点会抬 {@code perm_version} 逼全员重登，
 * 而这几个语义已被既有角色覆盖，没有额外管控价值。
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService queryService;
    private final QueryBuilderService queryBuilderService;

    public QueryController(QueryService queryService, QueryBuilderService queryBuilderService) {
        this.queryService = queryService;
        this.queryBuilderService = queryBuilderService;
    }

    /** 执行一条 SQL。 */
    @PostMapping("/run")
    @RequiresPermission("chat:query:execute")
    public R<QueryDtos.QueryRunVO> run(@RequestBody @Valid QueryDtos.SqlRunRequest request) {
        return R.ok(queryService.runSql(
                request.datasourceId(), request.sql(), request.maxRows(), request.chartType(), null));
    }

    /** 我的模板列表。 */
    @GetMapping("/templates")
    @RequiresPermission("history:query:read")
    public R<List<QueryDtos.SavedQueryVO>> templates(
            @RequestParam(required = false) Long datasourceId) {
        return R.ok(queryService.listTemplates(currentUid(), datasourceId));
    }

    /** 保存模板。 */
    @PostMapping("/templates")
    @RequiresPermission("chat:query:execute")
    public R<Long> saveTemplate(@RequestBody @Valid QueryDtos.SavedQueryCreateCmd cmd) {
        return R.ok(queryService.saveTemplate(cmd, currentTenantId(), currentUid()));
    }

    /** 覆盖式更新模板（改 SQL / 改名 / 换数据源 / 改说明）。 */
    @PutMapping("/templates/{id}")
    @RequiresPermission("chat:query:execute")
    public R<Void> updateTemplate(@PathVariable Long id,
                                  @RequestBody @Valid QueryDtos.SavedQueryUpdateCmd cmd) {
        queryService.updateTemplate(id, cmd, currentTenantId(), currentUid());
        return R.ok();
    }

    /** 运行模板。 */
    @PostMapping("/templates/{id}/run")
    @RequiresPermission("chat:query:execute")
    public R<QueryDtos.QueryRunVO> runTemplate(@PathVariable Long id,
                                               @RequestBody(required = false)
                                               QueryDtos.TemplateRunRequest request) {
        return R.ok(queryService.runTemplate(id, request == null ? null : request.params()));
    }

    /** 删除模板。 */
    @DeleteMapping("/templates/{id}")
    @RequiresPermission("chat:query:execute")
    public R<Void> deleteTemplate(@PathVariable Long id) {
        queryService.deleteTemplate(id, currentTenantId(), currentUid());
        return R.ok();
    }

    /**
     * 切换模板可见性（组织共享查询模板）：仅属主本人且为租户管理员（OWNER/ADMIN）可操作；
     * 共享后同租户成员可查/可跑/可设定时报表，执行仍按使用者自身数据权限收敛（不升权）。
     */
    @PutMapping("/templates/{id}/visibility")
    @RequiresPermission("chat:query:execute")
    public R<Void> setTemplateVisibility(@PathVariable Long id,
                                         @RequestBody QueryDtos.SavedQueryVisibilityCmd cmd) {
        queryService.setTemplateVisibility(id, cmd == null ? null : cmd.visibility(),
                currentTenantId(), currentUid());
        return R.ok();
    }

    // ==================== 可视化查询构造器（P1 收尾） ====================

    /**
     * 构造器：某张表的可用关联（供"添加维表"下拉）。
     *
     * <p>清单由服务端按同一口径算出（人工登记 &gt; 库中外键 &gt; 命名推断），<b>连接列与方向都在服务端定</b>，
     * 前端只能选不能改——这样"选出来的 JOIN"不会因为前端拼错列名而跑出一个静默错数的结果。
     */
    @GetMapping("/builder/relations")
    @RequiresPermission("history:query:read")
    public R<List<QueryDtos.BuilderRelationVO>> builderRelations(
            @RequestParam Long datasourceId,
            @RequestParam String table) {
        return R.ok(queryBuilderService.relationsOf(datasourceId, table));
    }

    /** 构造器：只生成 SQL 不执行（预览）。 */
    @PostMapping("/builder/preview")
    @RequiresPermission("history:query:read")
    public R<QueryDtos.BuilderSqlVO> builderPreview(@RequestBody @Valid QueryDtos.BuilderRunRequest request) {
        return R.ok(queryBuilderService.preview(request.datasourceId(), request.spec()));
    }

    /** 构造器：生成 SQL 并执行（与编辑器同一条安全内核）。 */
    @PostMapping("/builder/run")
    @RequiresPermission("chat:query:execute")
    public R<QueryDtos.QueryRunVO> builderRun(@RequestBody @Valid QueryDtos.BuilderRunRequest request) {
        return R.ok(queryBuilderService.run(request.datasourceId(), request.spec(),
                request.maxRows(), request.chartType()));
    }

    /** 我的执行历史。 */
    @GetMapping("/history")
    @RequiresPermission("history:query:read")
    public R<List<QueryDtos.QueryRecordVO>> history(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) Integer limit) {
        return R.ok(queryService.recentHistory(currentUid(), limit, datasourceId));
    }

    private Long currentUid() {
        return AccessContextHolder.require().uid();
    }

    private Long currentTenantId() {
        return AccessContextHolder.require().tenantId();
    }
}
