package com.aibi.module.lineage.dto;

import com.aibi.module.lineage.dto.LineageVO.RefItem;

import java.util.List;

/**
 * 字段被引用情况（需求 §3.3 语义管理「被引用指标」列的数据源）。
 *
 * @param fieldId   语义字段 id（semantic_meta.id）
 * @param table     物理表名
 * @param column    物理列名
 * @param label     展示名（语义层业务别名，无别名回落列名）
 * @param metrics   引用该字段的指标清单
 * @param count     引用指标数（列表列直接展示）
 */
public record FieldReferenceVO(
        Long fieldId,
        String table,
        String column,
        String label,
        List<RefItem> metrics,
        int count
) {
}
