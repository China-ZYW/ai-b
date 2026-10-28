package com.aibi.module.query.controller;

import com.aibi.common.response.R;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.query.chart.ChartTypeRegistry;
import com.aibi.module.query.completion.CompletionAggregator;
import com.aibi.module.query.completion.CompletionItem;
import com.aibi.module.query.completion.CompletionRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 自动补全与图型清单接口（P2 / SQL 编辑器右侧面板）。
 *
 * <p><b>补全走 SPI 聚合</b>：{@code /api/completion/schema} 只是入口，具体候选由所有
 * {@link com.aibi.module.query.completion.CompletionProvider} 按优先级聚合（当前内置"表结构 + 语义中文别名"）。
 * 日后想加"历史高频片段"或"关联表推荐"，加一个 Bean 即可，本控制器不用改。
 *
 * <p><b>图型清单</b>由 {@link ChartTypeRegistry} 统一供给，前端下拉不再硬编码图型列表——
 * 将来渲染层加了新图型，后端声明一处，下拉自动多一项。
 */
@RestController
@RequestMapping("/api/completion")
public class CompletionController {

    private final CompletionAggregator aggregator;
    private final ChartTypeRegistry chartTypeRegistry;

    public CompletionController(CompletionAggregator aggregator, ChartTypeRegistry chartTypeRegistry) {
        this.aggregator = aggregator;
        this.chartTypeRegistry = chartTypeRegistry;
    }

    /**
     * SQL 编辑器补全。
     *
     * @param datasourceId 数据源 id（必填）
     * @param prefix       光标前的片段
     * @param tableName    当前所在表（编辑器解析 FROM 后回填，可选）
     * @param limit        单源条数上限（默认 50）
     */
    @GetMapping("/schema")
    @RequiresPermission("datasource:schema:read")
    public R<List<CompletionItem>> complete(@RequestParam Long datasourceId,
                                            @RequestParam(required = false) String prefix,
                                            @RequestParam(required = false) String tableName,
                                            @RequestParam(required = false) Integer limit) {
        return R.ok(aggregator.complete(new CompletionRequest(datasourceId, prefix, tableName, limit)));
    }

    /** 当前平台支持的图型清单（供前端图表选择器下拉）。 */
    @GetMapping("/chart-types")
    @RequiresPermission("history:query:read")
    public R<List<ChartTypeRegistry.ChartTypeDef>> chartTypes() {
        return R.ok(List.copyOf(chartTypeRegistry.registeredTypes()));
    }

    /** 某个图型需要的字段角色（前端把字段拖到 X / 系列 / Y 时提示）。 */
    @GetMapping("/chart-types/{code}/field-roles")
    @RequiresPermission("history:query:read")
    public R<List<ChartTypeRegistry.FieldRole>> fieldRoles(@PathVariable String code) {
        return R.ok(chartTypeRegistry.fieldRoles(new ChartTypeRegistry.ChartTypeDef(code, code)));
    }

    /** 已注册的补全来源（排错用：候选为空时先确认这一项不是空的）。 */
    @GetMapping("/providers")
    @RequiresPermission("history:query:read")
    public R<List<String>> providers() {
        return R.ok(aggregator.registeredNames());
    }
}
