package com.aibi.module.semantic.patrol;

import com.aibi.module.chat.distill.GovernanceIssue;

import java.util.List;

/**
 * 元数据巡检一次结果（T11-4，设计稿 §5.1）。
 *
 * @param scannedDatasources 本次成功扫描的数据源数
 * @param failedDatasources  schema 拉取失败被跳过的数据源数（静默降级，不中断整体）
 * @param note               截断/降级说明（可空：如"租户启用数据源 9 个，本次仅扫前 5 个"）
 * @param broken             S-M1 血缘断裂命中数
 * @param diffDef            S-M2 同名不同口径命中数
 * @param noComment          S-M3 注释缺失命中数（按表聚簇后）
 * @param issues             本次命中投影（按信号优先级排序，cap {@value MetaPatrolService#ISSUE_CAP}）
 */
public record MetaPatrolResult(int scannedDatasources,
                               int failedDatasources,
                               String note,
                               int broken,
                               int diffDef,
                               int noComment,
                               List<GovernanceIssue> issues) {
}
