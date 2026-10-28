package com.aibi.module.lineage.dto;

import com.aibi.module.lineage.LineageService;

import java.util.List;

/**
 * 语义字段变更的影响面（P1「血缘变更影响提示」）。
 *
 * <p>在语义管理里修改字段别名、口径表达式或列角色前调用，
 * 告知管理员「本次改动会影响哪些指标、进而影响哪些看板」，确认后方可保存。
 *
 * @param fieldId     语义字段 id（未登记时为 null，此时影响面按物理列名兜底为空）
 * @param tableName   物理表名
 * @param columnName  物理列名
 * @param fieldLabel  字段展示名（语义层中文别名，无则列名）
 * @param metrics     受影响的指标（直接或间接引用了该字段的口径）
 * @param dashboards  受影响的看板（引用了上述指标）
 * @param total       影响条目总数（指标 + 看板），为 0 表示可安全保存
 */
public record FieldImpactVO(
        Long fieldId,
        String tableName,
        String columnName,
        String fieldLabel,
        List<LineageVO.RefItem> metrics,
        List<LineageVO.RefItem> dashboards,
        int total) {
}
