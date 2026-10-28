package com.aibi.module.attribution.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 归因执行请求。
 *
 * <p><b>为什么以指标为中心而非以 SQL 为中心</b>：需求 §3.1 要求"趋势 / 量价因子 / 维度归因共用同一套口径"。
 * 若每次传入自由 SQL，三处口径必然漂移（一处按净额、一处按总额），归因结论会自相矛盾。
 * 因此请求只认 {@code metricId}，口径一律取自指标沉淀的 canonical 表达式
 * （{@code metric.definition}），从源头杜绝漂移。
 *
 * @param metricId      指标 id（必填；口径的唯一来源）
 * @param dimension     归因维度列名（必填，单维）；必须是语义层标记为维度的字段，且非敏感列
 * @param baseline      对比基准：MOM=环比上期、YOY=同比去年同期、PEAK=对比历史高点
 * @param period        本期期间（如 {@code 2026-08} 或 {@code 2026-08-01}），为空时取数据最新一期
 * @param granularity   时间粒度：month=按月、day=按日；影响基准期的推算
 * @param volumeExpr    量字段表达式（量价拆解用，如 {@code qty}）；为空时尝试从语义层推断，推断不到则跳过量价拆解
 * @param topN          明细表返回的最大成员数（按贡献绝对值降序），默认 10
 * @param clarified     是否已完成单轮澄清（需求 §3.2：最多 1 轮，禁止无限多轮对话）。
 *                      为 true 时后端不再返回 CLARIFY 门控，直接产出结果并附风险提示。
 */
public record AttributionReq(
        @NotNull(message = "指标不能为空") Long metricId,
        @NotNull(message = "归因维度不能为空") String dimension,
        String baseline,
        String period,
        String granularity,
        String volumeExpr,
        /**
         * 发起归因的问数历史 id（可空）。非空时归因结果会回写到该 {@code query_history} 行的
         * {@code attribution_json}，使历史回放能还原归因面板（与 insight / verify 同一套"问数当次产物"口径）。
         * 指标库等无历史上下文的场景留空即可——那时结果本就无处可挂。
         */
        Long historyId,
        Integer topN,
        Boolean clarified
) {

    /** 默认对比基准：环比上期（企业经营分析最常用的口径）。 */
    public static final String BASELINE_MOM = "MOM";
    public static final String BASELINE_YOY = "YOY";
    public static final String BASELINE_PEAK = "PEAK";

    public static final String GRANULARITY_MONTH = "month";
    public static final String GRANULARITY_DAY = "day";

    /** 归一化的对比基准（空/非法回落 MOM）。 */
    public String baselineOrDefault() {
        if (baseline == null || baseline.isBlank()) {
            return BASELINE_MOM;
        }
        String b = baseline.trim().toUpperCase();
        return (b.equals(BASELINE_YOY) || b.equals(BASELINE_PEAK)) ? b : BASELINE_MOM;
    }

    /** 归一化的时间粒度（空/非法回落 month）。 */
    public String granularityOrDefault() {
        if (granularity == null || granularity.isBlank()) {
            return GRANULARITY_MONTH;
        }
        return granularity.trim().toLowerCase().equals(GRANULARITY_DAY) ? GRANULARITY_DAY : GRANULARITY_MONTH;
    }

    /** 是否已完成单轮澄清（null 视为 false）。 */
    public boolean clarifiedOrDefault() {
        return clarified != null && clarified;
    }

    /**
     * 用户是否显式指定了对比基准。
     *
     * <p>未显式指定时系统按 MOM 兜底执行，但"对比基准不明确"本身是歧义信号（需求 §3.4），
     * 会作为置信度扣分项并可能在中等置信区间触发单轮澄清。
     */
    public boolean baselineExplicit() {
        return baseline != null && !baseline.isBlank();
    }

    /** 明细返回条数（非法回落 10，上限 50 防止大维度拖垮渲染）。 */
    public int topNorDefault() {
        if (topN == null || topN <= 0) {
            return 10;
        }
        return Math.min(topN, 50);
    }
}
