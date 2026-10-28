package com.aibi.module.datasource.model;

import java.util.List;
import java.util.Map;

/**
 * 查询执行结果（跨模块共享的执行产物）。
 *
 * <p>由数据源适配器产出，问数模块直接作为 API 响应体返回。前端据 columns/rows 渲染表格与图表。
 *
 * <p><b>列身份与列展示名分离</b>：{@code columns} 是字段身份标识（图表规格 xField/yFields、历史快照、
 * AI 解读/校验一律以此为准），{@code columnLabels} 只是语义层维护的业务别名，仅供前端展示
 * （明细表表头、图表坐标轴/图例/系列名）。这样管理员日后调整别名，不会让已存的图表规格与快照失配。
 *
 * <p><b>码值与标签分离（2026-09）</b>：{@code enumDicts} 是「结果列名 → 码值→中文标签」的展示映射，
 * 供前端把 {@code status=1} 显示成 {@code 已支付}。它与 {@code columnLabels} 一样<b>只影响显示</b>：
 * 语义层配了「枚举字典」的码值列，在明细表与图表里出中文，但导出 Excel 与 AI 解读仍拿原始码值，
 * 保证结果可追溯、可比对。
 *
 * @param sql             实际执行的安全 SQL（已校验、已按方言注入上限）
 * @param columns         列名（含 SQL 别名），字段身份标识
 * @param columnLabels    列展示名（业务别名，与 columns 等长同序）；null 表示无可用别名，前端回落列名
 * @param rows            数据行
 * @param rowCount        返回行数
 * @param executionTimeMs 执行耗时（毫秒）
 * @param enumDicts       「结果列名 →（码值→中文标签）」展示映射；null 表示无列命中枚举字典
 */
public record QueryResult(
        String sql,
        List<String> columns,
        List<String> columnLabels,
        List<List<Object>> rows,
        int rowCount,
        long executionTimeMs,
        Map<String, Map<String, String>> enumDicts
) {

    /**
     * 只有物理列名、无展示别名的结果（适配器执行产物、历史快照还原、分享快照裁剪等场景）。
     *
     * <p>用静态工厂而非重载构造器，避免与 record 规范构造器在 JSON 反序列化时产生歧义。
     */
    public static QueryResult of(String sql, List<String> columns, List<List<Object>> rows,
                                 int rowCount, long executionTimeMs) {
        return new QueryResult(sql, columns, null, rows, rowCount, executionTimeMs, null);
    }

    /** 派生一份带展示名的结果（其余字段原样透传）；labels 为 null 时返回自身，不做无谓复制。 */
    public QueryResult withLabels(List<String> labels) {
        return labels == null ? this : new QueryResult(sql, columns, labels, rows, rowCount, executionTimeMs, enumDicts);
    }

    /**
     * 派生一份带枚举字典的结果；dicts 为 null 或空时返回自身（与 {@link #withLabels} 同一套
     * “不下发冗余字段”约定——前端据此不做任何字符串替换）。
     */
    public QueryResult withEnumDicts(Map<String, Map<String, String>> dicts) {
        if (dicts == null || dicts.isEmpty()) {
            return this;
        }
        return new QueryResult(sql, columns, columnLabels, rows, rowCount, executionTimeMs, dicts);
    }
}
