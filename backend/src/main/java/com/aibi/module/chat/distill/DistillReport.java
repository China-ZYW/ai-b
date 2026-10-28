package com.aibi.module.chat.distill;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问题沉淀聚合报告（T11-1）：一次「立即分析」的产出。
 *
 * @param issues 待治理问题清单（已按信号优先级 + 出现次数排序，截断至 topN）
 * @param stats  各信号簇数概览（供前端顶部计数）
 * @param since  本次聚合的时间窗起点（{@code 现在 - windowDays}）
 */
public record DistillReport(List<GovernanceIssue> issues, Stats stats, LocalDateTime since) {

    /**
     * @param scanned          窗口内扫描的历史行数
     * @param failedClusters   S1 高频失败簇数
     * @param lowScoreClusters S2 低质量分簇数
     * @param thumbsDown       S3 点踩问法数
     * @param ambiguous        S4 歧义簇数
     * @param costBlocked      S5 代价熔断问法数
     * @param handled          已处置簇数（CONVERTED+DISMISSED，二期 B 台账回填）
     */
    public record Stats(int scanned, int failedClusters, int lowScoreClusters,
                        int thumbsDown, int ambiguous, int costBlocked, int handled) {
    }

    /** 空报告（开关关闭或无数据）。 */
    public static DistillReport empty(LocalDateTime since) {
        return new DistillReport(List.of(), new Stats(0, 0, 0, 0, 0, 0, 0), since);
    }
}
