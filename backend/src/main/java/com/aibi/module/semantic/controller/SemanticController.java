package com.aibi.module.semantic.controller;

import com.aibi.common.exception.ErrorCode;
import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.entity.TableRelationDef;
import com.aibi.module.semantic.service.AliasCompletionService;
import com.aibi.module.semantic.service.MetricAutoAnalysisService;
import com.aibi.module.semantic.service.SemanticService;
import com.aibi.module.semantic.service.TableRelationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Map;

/**
 * 语义层管理接口（配置化升级 V2）：列表 / 骨架初始化 / 编辑 / 删除 / 枚举采样 / 表关联登记。
 * 租户隔离由拦截器保证（semantic_meta、table_relation 均为租户隔离表）。
 */
@RestController
@RequestMapping("/api/semantic")
public class SemanticController {

    private static final Logger log = LoggerFactory.getLogger(SemanticController.class);

    private final SemanticService semanticService;
    private final MetricAutoAnalysisService metricAutoAnalysisService;
    private final AliasCompletionService aliasCompletionService;
    private final TableRelationService tableRelationService;
    private final AibiConfig aibiConfig;

    public SemanticController(SemanticService semanticService,
                              MetricAutoAnalysisService metricAutoAnalysisService,
                              AliasCompletionService aliasCompletionService,
                              TableRelationService tableRelationService,
                              AibiConfig aibiConfig) {
        this.semanticService = semanticService;
        this.metricAutoAnalysisService = metricAutoAnalysisService;
        this.aliasCompletionService = aliasCompletionService;
        this.tableRelationService = tableRelationService;
        this.aibiConfig = aibiConfig;
    }

    /** 某数据源的全部语义行。 */
    @GetMapping
    @RequiresPermission("semantic:meta:read")
    public R<List<SemanticMeta>> list(@RequestParam Long datasourceId) {
        return R.ok(semanticService.list(datasourceId));
    }

    /**
     * 骨架初始化：为全部表/列补语义骨架行（alias=注释/原名、role=AUTO），返回新增行数。
     *
     * <p>当 METRIC / auto-analysis 开关开启时，初始化成功后自动串联「指标后台自动分析」
     * （扫描新骨架、建指标、打 AI 标识）。该串联以 try/catch 保护——自动分析失败只告警，
     * 不影响骨架初始化本身的返回，符合"初始化是主路径、自动分析是增强"的语义。
     */
    @PostMapping("/init")
    @RequiresPermission("semantic:meta:init")
    public R<Integer> init(@RequestParam Long datasourceId) {
        int added = semanticService.initSkeleton(datasourceId);
        if (aibiConfig.metricAutoAnalysisEnabled()) {
            // 异步串联：提交即返回，扫描在后台线程跑（上下文由 TenantContext.wrap 传播），不阻塞骨架初始化响应
            metricAutoAnalysisService.analyzeAsync(datasourceId);
            log.info("骨架初始化后已异步串联指标分析 ds={}", datasourceId);
        }
        return R.ok(added);
    }

    /**
     * 指标后台自动分析 Skill：扫描库表结构，自动识别指标 / 维度、生成多级目录并打上 AI 标识，同时自动建指标。
     *
     * <p>受 {@code semantic:meta:auto-analysis} 门禁；开关（METRIC / auto-analysis）关闭时语义管理页隐藏入口。
     */
    @PostMapping("/auto-analysis")
    @RequiresPermission("semantic:meta:auto-analysis")
    public R<MetricAutoAnalysisService.AiAnalysisResult> autoAnalysis(@RequestParam Long datasourceId) {
        // 后台异步：立即返回"已提交"，实际扫描在 analysisExecutor 线程跑，避免大库阻塞请求线程
        return R.ok(metricAutoAnalysisService.analyzeAsync(datasourceId));
    }

    /**
     * AI 全库别名补全 Skill：扫描该数据源全部表/列，用大模型推断中文业务别名并写回语义层
     * （{@code semantic_meta.alias_cn}，{@code source=AI}）。
     *
     * <p>只补"为空或不含汉字"的别名——已有人工维护的中文别名一律保留，故可反复执行、天然幂等。
     * 受 {@code semantic:meta:auto-analysis} 门禁（与「指标自动分析」同属语义层 AI 自动分析能力）。
     */
    @PostMapping("/alias-completion")
    @RequiresPermission("semantic:meta:auto-analysis")
    public R<AliasCompletionService.AliasResult> aliasCompletion(@RequestParam Long datasourceId) {
        // 后台异步：立即返回"已提交"，实际全库推理在 analysisExecutor 线程跑（上下文由 TenantContext.wrap 传播）
        return R.ok(aliasCompletionService.completeAsync(datasourceId));
    }

    /** 编辑语义行（别名/描述/同义词/角色/枚举/口径/启用态）。 */
    @PutMapping
    @RequiresPermission("semantic:meta:write")
    public R<SemanticMeta> update(@RequestBody SemanticMeta form) {
        if (form.getId() == null) {
            return R.fail(ErrorCode.PARAM_ERROR, "缺少语义行 id");
        }
        SemanticMeta updated = semanticService.update(form);
        return updated == null ? R.fail(ErrorCode.DATASOURCE_NOT_FOUND, "语义行不存在或无权访问") : R.ok(updated);
    }

    /** 删除语义行。 */
    @DeleteMapping("/{id}")
    @RequiresPermission("semantic:meta:delete")
    public R<Void> delete(@PathVariable Long id) {
        semanticService.delete(id);
        return R.ok();
    }

    /**
     * 枚举值采样回填（SELECT DISTINCT ... LIMIT 20）。
     *
     * <p>返回结构：{@code values} 采样到的取值清单、{@code numeric} 是否数值码列、{@code backfilled}
     * 是否真的回填了取值清单（数值码列且未配 {@code enum_labels} 字典时跳过，避免往 Prompt 塞裸数字）。
     * 前后端据此给出"请补录码值映射"的引导，而非让用户以为采样失效。
     */
    @PostMapping("/sample-enums")
    @RequiresPermission("semantic:meta:read")
    public R<Map<String, Object>> sampleEnums(@RequestParam Long datasourceId,
                                              @RequestParam String table,
                                              @RequestParam String column) {
        return R.ok(semanticService.sampleEnums(datasourceId, table, column));
    }

    /** 一键采样该数据源下全部维度类列的枚举值（跳过度量列），返回汇总。 */
    @PostMapping("/sample-all-enums")
    @RequiresPermission("semantic:meta:read")
    public R<Map<String, Object>> sampleAllEnums(@RequestParam Long datasourceId) {
        return R.ok(semanticService.sampleAllEnums(datasourceId));
    }

    // ==================== 表关联登记（关系语义） ====================

    /**
     * 某数据源的全部关联登记（含停用行）。
     *
     * <p>复用 {@code semantic:meta:read} 权限点而不新增：关联登记与别名维护同属"语义层治理"职责，
     * 数据管家/管理员能改别名就应能改连接键——新增权限点会抬升 perm_version 逼全员重登，收益不匹配。
     */
    @GetMapping("/relations")
    @RequiresPermission("semantic:meta:read")
    public R<List<TableRelationDef>> relations(@RequestParam Long datasourceId) {
        return R.ok(tableRelationService.list(datasourceId));
    }

    /**
     * 推荐清单：系统按库中外键与命名约定推出的连接键，标注来源与是否已登记。
     *
     * <p>治理入口——管理员一键采纳即把"推断（可能错）"升级为"人工登记（权威）"，
     * 同时给多对多这类推不出来的基数一个声明的地方。
     */
    @GetMapping("/relations/suggest")
    @RequiresPermission("semantic:meta:read")
    public R<List<TableRelationService.Suggestion>> relationSuggestions(@RequestParam Long datasourceId) {
        return R.ok(tableRelationService.suggest(datasourceId));
    }

    /** 新增或更新一条关联登记（{@code id} 为空即新增）。 */
    @PostMapping("/relations")
    @RequiresPermission("semantic:meta:write")
    public R<TableRelationDef> saveRelation(@RequestBody TableRelationDef form) {
        if (form.getDatasourceId() == null) {
            return R.fail(ErrorCode.PARAM_ERROR, "缺少数据源 id");
        }
        return R.ok(tableRelationService.save(form));
    }

    /**
     * 全部采纳：把该数据源「尚未登记」的推荐一键落库为人工登记（已登记的跳过）。
     *
     * <p>{@code defaultType} 可选，仅用于给推不出基数的推荐定档；不传则保留「待确认」交人工确认。
     */
    @PostMapping("/relations/adopt-all")
    @RequiresPermission("semantic:meta:write")
    public R<Map<String, Object>> adoptAll(@RequestParam Long datasourceId,
                                           @RequestParam(required = false) String defaultType) {
        return R.ok(tableRelationService.adoptAll(datasourceId, defaultType));
    }

    /** 删除一条关联登记。 */
    @DeleteMapping("/relations/{id}")
    @RequiresPermission("semantic:meta:delete")
    public R<Void> deleteRelation(@PathVariable Long id) {
        tableRelationService.delete(id);
        return R.ok();
    }
}
