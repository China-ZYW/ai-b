package com.aibi.module.chat.chart;

import java.util.List;

/**
 * 图表推荐规格（M3）：告诉前端"用哪种图、哪个字段做 X 轴 / 度量 / 分组"，并附推荐理由以增强可解释性。
 *
 * <p>{@link ChartType#TABLE} 时 xField/yFields/seriesField 可为空，前端直接渲染明细表。
 *
 * <p>{@code style} 为展示样式（轴名/单位/数值格式/图例位置），可空；空时前端按内置默认渲染，
 * 因此历史数据与不关心样式的调用方行为不变。保留 6 参重载构造器，使既有构造点无需感知该字段。
 *
 * @param type        推荐图表类型
 * @param title       图表标题（可空，前端可用问题文本兜底）
 * @param xField      维度字段名（时间轴 / 类别轴），TABLE 时为空
 * @param yFields     度量字段名列表（支持多度量分组柱状 / 多线折线）
 * @param seriesField 分组（系列）字段名，用于多维度分组柱状图；无则为空
 * @param reason      推荐理由（面向用户，解释为何选此图）
 * @param style       展示样式（可空，见 {@link ChartStyle}）
 */
public record ChartSpec(
        ChartType type,
        String title,
        String xField,
        List<String> yFields,
        String seriesField,
        String reason,
        ChartStyle style
) {

    /** 兼容构造：不带展示样式（推荐引擎内部构造点与既有调用方沿用此形态）。 */
    public ChartSpec(ChartType type, String title, String xField, List<String> yFields,
                     String seriesField, String reason) {
        this(type, title, xField, yFields, seriesField, reason, null);
    }

    /** 明细表兜底规格。 */
    public static ChartSpec table(String reason) {
        return new ChartSpec(ChartType.TABLE, null, null, List.of(), null, reason);
    }

    /** 派生：换图型但保留其余配置（用户显式指定图型 / 结构校验改写时用，样式不丢）。 */
    public ChartSpec withType(ChartType newType) {
        return new ChartSpec(newType, title, xField, yFields, seriesField, reason, style);
    }

    /** 派生：装配展示样式（{@link ChartStyle#orNull} 语义——全空则置 null，不写空对象）。 */
    public ChartSpec withStyle(ChartStyle newStyle) {
        return new ChartSpec(type, title, xField, yFields, seriesField, reason, ChartStyle.orNull(newStyle));
    }
}
