package com.aibi.module.chat.dto;

/**
 * 溯源来源表项（T10-3）：实际执行 SQL 涉及的一张物理表，附其在语义层登记的业务名与描述。
 *
 * <p>由 {@code LineageExtractor} 从 {@code result.sql()} 抽取表名、再按语义层缓存视图富化产出。
 * 语义层未登记该表时 {@link #aliasCn} / {@link #description} 为 {@code null}，前端只显示物理表名
 * ——出处真实性优先于文案美观，缺别名恰是治理侧（T11-1/T11-4）要补的注释线索。
 *
 * @param table       物理表名（抽取原文，可能带库/模式前缀或反引号；比较时按裸名归一）
 * @param aliasCn     语义层业务名（未登记为 {@code null}）
 * @param description 语义层表描述（未登记为 {@code null}）
 */
public record LineageTable(
        String table,
        String aliasCn,
        String description
) {
}
