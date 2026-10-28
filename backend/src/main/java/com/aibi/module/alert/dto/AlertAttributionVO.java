package com.aibi.module.alert.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预警触发时预跑的归因摘要（P1「预警触发预跑归因」）。
 *
 * <p><b>为什么随告警一起算</b>：告警只说"指标掉到阈值以下"，管理员还得自己再去查"为什么掉"。
 * 触发瞬间数据就在手边（刚取过当前值），此时顺手跑一次单维归因，把头部贡献者与解读
 * 一并写进记录，告警就从"通知"升级为"可行动的答案"。
 *
 * <p><b>口径与门控</b>：完全复用归因链路——维度取规则指定值、为空则回落指标沉淀的默认维度
 * （{@code metric.attribution_dimension}），基准取指标默认基准，期间取数据最新一期，
 * 并置 {@code clarified=true} 跳过单轮澄清（自动执行场景不允许反问人）。
 * 若指标无默认维度、或归因被门控为 BLOCK、或取值失败，则只返回 {@code reason}，
 * <b>绝不让归因失败反噬告警本身</b>——告警照常触发，只是没有归因结论。
 *
 * @param metricId       指标 id（指标键非 {@code metric:<id>} 时为 null）
 * @param metricName     指标名（别名优先）
 * @param dimension      实际使用的归因维度列名
 * @param dimensionLabel 维度展示名
 * @param baseline       对比基准 MOM/YOY/PEAK
 * @param currentPeriod  本期期间
 * @param basePeriod     基期期间
 * @param totalDelta     总变化 = 本期合计 − 基期合计
 * @param drivers        头部贡献成员（按 |delta| 降序，最多 3 个）
 * @param insight        归因解读文案
 * @param confidence     归因置信度（0~1）
 * @param gate           门控 DIRECT/CLARIFY/BLOCK
 * @param reason         未产生归因的原因（成功时为 null）
 */
public record AlertAttributionVO(
        Long metricId,
        String metricName,
        String dimension,
        String dimensionLabel,
        String baseline,
        String currentPeriod,
        String basePeriod,
        BigDecimal totalDelta,
        List<AlertDriverVO> drivers,
        String insight,
        Double confidence,
        String gate,
        String reason
) {

    /** 未产生归因时的构造（只带原因，其余为空）。 */
    public static AlertAttributionVO skipped(Long metricId, String reason) {
        return new AlertAttributionVO(metricId, null, null, null, null, null, null, null,
                List.of(), null, null, null, reason);
    }

    /** 是否有真正的归因结论（有则前端展示归因卡，无则展示 reason 提示）。 */
    public boolean hasResult() {
        return reason == null && drivers != null && !drivers.isEmpty();
    }
}
