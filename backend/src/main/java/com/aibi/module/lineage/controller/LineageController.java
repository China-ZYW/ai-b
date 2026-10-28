package com.aibi.module.lineage.controller;

import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.module.lineage.LineageService;
import com.aibi.module.lineage.dto.DashboardLineageVO;
import com.aibi.module.lineage.dto.DatasourceLineageVO;
import com.aibi.module.lineage.dto.FieldImpactVO;
import com.aibi.module.lineage.dto.FieldReferenceVO;
import com.aibi.module.lineage.dto.LineageVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据血缘接口（需求 §3.3）。
 *
 * <p>血缘属元数据，但仍需权限把关："被哪些看板/预警引用"会暴露内部架构关系，
 * 不宜对全部角色开放，故三个端点均挂权限点由 RBAC 矩阵控制。
 *
 * <p>受 {@code lineage.enabled} 开关控制，关闭时服务端直接拒绝，前端无需另做判断。
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    /** 指标血缘卡：来源表/字段 + 口径表达式 + 被引用看板。 */
    @GetMapping("/metric/{id}")
    @RequiresPermission("lineage:metric:read")
    public LineageVO metric(@PathVariable("id") Long id) {
        return lineageService.metricLineage(id);
    }

    /**
     * 看板血缘卡（P1）：该看板用了哪些指标 → 这些指标又来自哪些表和字段。
     *
     * <p>与 {@code /metric/{id}} 方向相反：后者自下而上回答"这个指标影响谁"，
     * 本端点自上而下回答"这张看板的数据从哪来"，是审计对外报表时的主视角。
     */
    @GetMapping("/dashboard/{id}")
    @RequiresPermission("lineage:metric:read")
    public DashboardLineageVO dashboard(@PathVariable("id") Long id) {
        return lineageService.dashboardLineage(id);
    }

    /** 字段被哪些指标引用（语义管理「被引用指标」列与浮层）。 */
    @GetMapping("/field/{id}/references")
    @RequiresPermission("lineage:field:read")
    public FieldReferenceVO fieldReferences(@PathVariable("id") Long id) {
        return lineageService.fieldReferences(id);
    }

    /**
     * 字段变更影响面（P1「血缘变更影响提示」）：改别名/口径/列角色前调用，
     * 返回「会影响哪些指标 → 进而影响哪些看板」，total=0 表示可安全保存。
     */
    @GetMapping("/impact")
    @RequiresPermission("lineage:field:read")
    public FieldImpactVO impact(@RequestParam("datasourceId") Long datasourceId,
                                @RequestParam("tableName") String tableName,
                                @RequestParam("columnName") String columnName) {
        return lineageService.fieldImpact(datasourceId, tableName, columnName);
    }

    /** 重建某指标血缘边（口径变更后手工刷新；先删后插，幂等）。 */
    @PostMapping("/metric/{id}/rebuild")
    @RequiresPermission("lineage:metric:write")
    public Map<String, Object> rebuild(@PathVariable("id") Long id) {
        int count = lineageService.rebuildMetric(id);
        return Map.of("metricId", id, "edges", count);
    }

    /** 按数据源批量重建（语义层批量标记列角色后的一次性刷新）。 */
    @PostMapping("/datasource/{id}/rebuild")
    @RequiresPermission("lineage:metric:write")
    public Map<String, Object> rebuildByDatasource(@PathVariable("id") Long id) {
        int count = lineageService.rebuildByDatasource(id);
        return Map.of("datasourceId", id, "edges", count);
    }

    /**
     * 数据源级血缘总览（问数调优「数据血缘」tab）：该数据源下所有 COLUMN 字段 → 各自被哪些指标引用 →
     * 这些指标又进了哪些看板。一次聚合，避免前端 N+1。
     */
    @GetMapping("/datasource/{id}")
    @RequiresPermission("lineage:metric:read")
    public DatasourceLineageVO datasource(@PathVariable("id") Long id) {
        return lineageService.datasourceLineage(id);
    }
}
