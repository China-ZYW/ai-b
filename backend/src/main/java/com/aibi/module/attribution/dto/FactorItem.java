package com.aibi.module.attribution.dto;

import java.math.BigDecimal;

/**
 * 量价因子拆解的**一个因子项**（瀑布图的一段）。
 *
 * <p><b>两因素分解（含交叉项）</b>：设基期量 {@code V0}、价 {@code P0}，本期 {@code V1}、{@code P1}，
 * 度量 {@code M = V × P}，则
 * <pre>
 *   ΔM = V1·P1 − V0·P0
 *      = (V1−V0)·P0   ← 量效应（按基期价评估"多卖了/少卖了多少"）
 *      + (P1−P0)·V0   ← 价效应（按基期量评估"贵了/便宜了多少"）
 *      + (V1−V0)·(P1−P0) ← 交叉效应（量与价同时变动的联合影响，不可单方面归给量或价）
 * </pre>
 * 三项之和恒等于 ΔM，故瀑布图从基期值出发、经三段后精确落到本期值，不产生"配不平"的残差。
 *
 * <p><b>价从何来</b>：语义层只需标记"量"字段（如 qty），单价由 {@code 总额 / 总量} 推导，
 * 不要求业务库单独存单价列——这既降低了语义维护成本，也保证了"价"与"额"口径同源。
 *
 * @param name  因子名（量效应 / 价效应 / 交叉效应 / 基期值 / 本期值）
 * @param value 该段的数值贡献（基期值/本期值为绝对水位，其余为增量）
 * @param kind  段类型：BASE=起点水位、VOLUME=量、PRICE=价、CROSS=交叉、TOTAL=终点水位
 * @param desc  业务化简述（供 AI 解读与 tooltip 使用）
 */
public record FactorItem(
        String name,
        BigDecimal value,
        String kind,
        String desc
) {

    /** 瀑布段类型。用 String 承载而非枚举，避免跨模块 JSON 反序列化时枚举变更引发兼容问题。 */
    public static final String KIND_BASE = "BASE";
    public static final String KIND_VOLUME = "VOLUME";
    public static final String KIND_PRICE = "PRICE";
    public static final String KIND_CROSS = "CROSS";
    public static final String KIND_TOTAL = "TOTAL";
}
