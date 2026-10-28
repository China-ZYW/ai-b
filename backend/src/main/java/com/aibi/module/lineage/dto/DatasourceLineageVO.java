package com.aibi.module.lineage.dto;

import com.aibi.module.lineage.dto.LineageVO.RefItem;

import java.util.List;

/**
 * 数据源级血缘总览（问数调优「数据血缘」tab）。
 *
 * <p>一次查询聚合「该数据源下所有 COLUMN 字段 → 各自被哪些指标引用 → 这些指标又进了哪些看板」，
 * 避免前端对字段逐个调 {@code /field/{id}/references} 形成 N+1。三层级联关系：
 * 字段是血缘起点（词边界匹配产出），指标是中间节点，看板是最终落点。
 *
 * @param datasourceId 数据源 id
 * @param fieldCount   参与血缘的 COLUMN 字段数（即 fields 长度）
 * @param fields       字段级血缘清单（按语义层登记顺序）
 */
public record DatasourceLineageVO(
        Long datasourceId,
        int fieldCount,
        List<FieldLineageItem> fields
) {

    /** 单个字段的血缘：被哪些指标引用。 */
    public record FieldLineageItem(
            Long fieldId,
            String table,
            String column,
            String label,
            String role,
            Integer sensitive,
            List<MetricRefItem> metrics
    ) {
    }

    /** 引用该字段的指标，及其被哪些看板引用（指标血缘的「影响谁」下钻）。 */
    public record MetricRefItem(
            Long metricId,
            String metricName,
            List<RefItem> dashboards
    ) {
    }
}
