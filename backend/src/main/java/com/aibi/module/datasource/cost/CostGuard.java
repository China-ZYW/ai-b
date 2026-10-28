package com.aibi.module.datasource.cost;

import com.aibi.module.datasource.model.DataSourceConfig;

/**
 * 执行前代价预估门面（T10-1）。所有技能的 SQL 出口——{@code JdbcDataSourceAdapter.execute}——在
 * {@code applyLimit} 之前调用本门面，对「大概率扫全表 / 笛卡尔爆炸 / 预估扫描行超预算」的查询主动拒绝，
 * 与现有的运行时兜底（{@code setMaxRows} / 方言 {@code LIMIT} / {@code setQueryTimeout}）互补：
 * 后者是「跑起来后限量/限时」，本门面是「跑之前就拦」。
 *
 * <p><b>实现契约</b>：零 LLM、纯规则；对自身故障 fail-open（解析不了、行数取不到一律放行，绝不误伤合法 SQL），
 * 仅对确凿超预算 fail-closed。总开关关闭时 {@link #assess} 必须恒返回 {@link CostVerdict#pass()}，
 * 使 adapter 行为与未接入本特性时逐字节等价。
 */
public interface CostGuard {

    /**
     * 静态评估一条待执行 SQL 的扫描代价。
     *
     * @param config 目标数据源（提供租户与库类型，用于查表行数统计与读门控配置）
     * @param sql    原始 SQL（未追加 LIMIT 前），实现据其结构 + 表行数估算给出结论
     * @return 代价结论；任何异常/无法判定均降级为 {@link CostVerdict#pass()}
     */
    CostVerdict assess(DataSourceConfig config, String sql);
}
