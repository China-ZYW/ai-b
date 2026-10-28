package com.aibi.module.chat.dto;

import java.util.List;

/**
 * 环比/异常自动标记结果体（T10-4b K3）：随 {@code ResultMeta.marks} 下发，缺省 {@code null}。
 *
 * <p>由 {@code AnomalyDetector} 依实际执行结果<b>确定性</b>产出：仅时间序列形态
 * （恰 1 时间列、无类别列、行数 ≥ 3）才可能有值；开关关闭 / 形态不符 / 无命中 / 装配失败
 * 一律 {@code null}（fail-soft，前端整块不渲染，面板与从前一致）。
 *
 * @param thresholdPct 命中的阈值百分比（本次计算所用值，供前端文案回显）
 * @param timeCol      时间序列的时间列名（物理名）
 * @param summary      一句话<b>事实</b>结论：只报波动数量与示例数字，不做任何业务归因
 * @param items        命中的波动标记清单（按时间序、行优先；同度量相邻周期一条）
 */
public record AnomalyMarks(
        int thresholdPct,
        String timeCol,
        String summary,
        List<AnomalyMark> items
) {
}
