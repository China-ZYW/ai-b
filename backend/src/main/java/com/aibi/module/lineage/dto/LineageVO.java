package com.aibi.module.lineage.dto;

import java.util.List;

/**
 * 指标血缘卡（需求 §3.3）。
 *
 * <p>一张卡回答审计/风控最关心的三个问题：<b>数据从哪来</b>（来源表与字段）、
 * <b>口径是什么</b>（canonical 表达式）、<b>影响谁</b>（被哪些看板/预警引用）。
 *
 * @param metricId        指标 id
 * @param metricName      指标名（别名优先）
 * @param definition      指标 canonical 口径表达式（趋势/因子/归因共用同一份，杜绝口径漂移）
 * @param sources         来源表及其字段（按表聚合，字段级血缘）
 * @param referencedBy    引用该指标的看板（含是否大屏）
 * @param dashboardCount  引用看板数量（列表页可直接展示，无需展开）
 */
public record LineageVO(
        Long metricId,
        String metricName,
        String definition,
        List<SourceItem> sources,
        List<RefItem> referencedBy,
        int dashboardCount
) {

    /** 来源表 + 该表被引用的字段清单（字段级血缘的最小展示单元）。 */
    public record SourceItem(String table, List<FieldItem> fields) {
    }

    /** 字段级血缘项。 */
    public record FieldItem(Long fieldId, String column, String label, String role, Integer sensitive) {
    }

    /** 引用方（看板/预警）摘要。 */
    public record RefItem(Long id, String name, String type, Integer isScreen) {
    }
}
