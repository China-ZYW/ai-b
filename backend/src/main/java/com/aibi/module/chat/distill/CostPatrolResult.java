package com.aibi.module.chat.distill;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成本巡检一次结果（T12-4，形状对齐 {@code MetaPatrolResult}）。
 *
 * @param scannedRows     窗口内取回的历史行数（聚簇前的行集大小）
 * @param windowDays      本次生效的时间窗（天）
 * @param since           窗口起点（供前端展示统计起点）
 * @param materialize     物化候选命中簇数
 * @param slow            慢查询命中簇数
 * @param dup             重复问法命中簇数
 * @param note            截断/降级说明（可空：如"窗口内历史达取数上限，尾部未计入"）
 * @param issues          本次命中投影（含建议文案，按优先级排序截断 topN；已同步 upsert 进台账）
 */
public record CostPatrolResult(int scannedRows,
                               int windowDays,
                               LocalDateTime since,
                               int materialize,
                               int slow,
                               int dup,
                               String note,
                               List<GovernanceIssue> issues) {

    /** 空结果（无租户上下文等退化路径）。 */
    public static CostPatrolResult empty(int windowDays) {
        return new CostPatrolResult(0, windowDays, null, 0, 0, 0, null, List.of());
    }
}
