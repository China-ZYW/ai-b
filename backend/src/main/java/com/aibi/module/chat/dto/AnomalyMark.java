package com.aibi.module.chat.dto;

/**
 * 单处环比波动标记（T10-4b K3）：时间序列结果中某一度量列在相邻周期间的变化超阈值。
 *
 * <p>定位口径以 <b>{@code timePoint}（时间值字符串）+ {@code colName}（度量物理列名）</b> 为准——
 * 检测按时间自然序排序后计算，行序与返回行序可能不同，故 {@code rowIdx} 仅作"按时间序的展示序号"
 * 提示；前端明细表按"时间列单元格显示值 === timePoint 且列名 === colName"匹配高亮。
 *
 * @param timePoint 本期时间点（时间值 {@code String.valueOf} 后的展示键，如 "2026-03"）
 * @param rowIdx    按时间排序后的本期行序（0 起，仅提示用）
 * @param colIdx    度量列下标（相对 {@code columns}）
 * @param colName   度量列名（物理名，与 {@code result.columns} 一致；display 只改标签不改列名）
 * @param value     本期值（原样数值，不做格式化）
 * @param prevValue 上期值
 * @param pctChange 变化百分比（带符号，如 37.8 / -52.0，已四舍五入到 1 位小数）
 * @param increase  是否上升（方向标志，前端据此选升红/降绿配色）
 */
public record AnomalyMark(
        String timePoint,
        int rowIdx,
        int colIdx,
        String colName,
        Object value,
        Object prevValue,
        double pctChange,
        boolean increase
) {
}
