package com.aibi.module.chat.distill;

/**
 * 问题沉淀聚合参数（T11-1）。由 {@code AibiConfig}（{@code METRIC/*}）装配后传入
 * {@link IssueDistiller}；纯内核只认本对象，便于脱库单测直接构造。
 *
 * @param tenantId          租户（严格隔离，由控制器/上下文传入）
 * @param datasourceId      数据源；为空表示该租户全数据源聚合
 * @param windowDays        聚合时间窗（天），早于窗口的历史不计入
 * @param lowScoreThreshold S2 低质量分阈值（0~100，对齐 {@code VERIFY_RETRY_SCORE}）
 * @param minOccurrences    成簇最少出现次数（降噪；点踩/熔断类不受此限，逐条即出）
 * @param topN              清单条数上限
 */
public record DistillQuery(Long tenantId,
                           Long datasourceId,
                           int windowDays,
                           int lowScoreThreshold,
                           int minOccurrences,
                           int topN) {
}
