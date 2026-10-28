package com.aibi.module.datasource.cost;

import com.aibi.module.datasource.model.DataSourceConfig;

/**
 * 表行数估算源（T10-1 代价预估的统计输入）。按库类型查系统视图取近似行数，供 {@link CostGuard}
 * 估算扫描量。<b>近似即可</b>：本值只用于粗粒度熔断阈值判断，容忍数量级误差（MySQL {@code TABLE_ROWS}
 * 本就是 InnoDB 估算值）。
 *
 * <p><b>取不到即 {@link CostVerdict#UNKNOWN_ROWS}</b>（-1）：不支持的库、无权限、统计未刷新、查询异常，
 * 均返回未知，由 {@link CostGuard} 保守降级（未知 ≠ 危险，不因行数为未知而拒绝）。
 */
public interface CostStatsProvider {

    /**
     * 估算指定表的行数。
     *
     * @param config 目标数据源
     * @param table  表名（可为限定名 {@code db.table}，实现取末段短名匹配系统视图）
     * @return 估算行数；无法获取返回 {@link CostVerdict#UNKNOWN_ROWS}（-1）
     */
    long estimateRowCount(DataSourceConfig config, String table);
}
