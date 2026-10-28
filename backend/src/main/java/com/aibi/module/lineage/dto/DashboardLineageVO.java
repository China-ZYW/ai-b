package com.aibi.module.lineage.dto;

import java.util.List;

/**
 * 看板血缘卡（P1）：一张看板「用了哪些指标 → 这些指标又来自哪些表和字段」。
 *
 * <p>与 {@link LineageVO} 方向相反：指标血缘回答「这个指标影响谁」（自下而上），
 * 看板血缘回答「这个看板的数据是哪来的」（自上而下）。审计场景下后者更常用——
 * 要判断一张对外发布的报表是否引用了敏感字段、或是否依赖了口径已变更的指标，
 * 必须从看板往下展开，而不是逐个指标反查。
 *
 * <p><b>指标从哪来</b>：看板布局是自由 JSON，故按 {@code "metricId": <id>} 词法解析引用关系，
 * 与 {@code LineageService.dashboardsReferencing} 同一口径，保证"指标→看板"与"看板→指标"互为逆运算。
 * 指标再经血缘边表 {@code metric → field} 追溯来源字段，最后按表聚合。
 *
 * @param dashboardId   看板 id
 * @param dashboardName 看板名
 * @param isScreen      是否大屏（1=大屏）
 * @param metrics       该看板引用的指标
 * @param sources       这些指标追溯到的来源表与字段（按表聚合，跨指标去重）
 * @param metricCount   引用指标数（列表页可直接展示，无需展开）
 */
public record DashboardLineageVO(
        Long dashboardId,
        String dashboardName,
        Integer isScreen,
        List<LineageVO.RefItem> metrics,
        List<LineageVO.SourceItem> sources,
        int metricCount) {
}
