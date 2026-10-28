package com.aibi.module.chat.chart;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 图表展示样式：坐标轴名、度量单位、数值格式、图例位置等<b>纯呈现</b>配置。
 *
 * <p>与 {@link ChartSpec} 的字段绑定（xField/yFields/seriesField）分离——绑定决定"画什么"，
 * 本记录决定"怎么标注"。随 {@code chart_spec} JSON 一并透传落库（{@code metric.chart_spec}、
 * {@code share_link.chart_spec}、{@code query_history.chart_json}、驾驶舱 {@code layout} 里的
 * {@code chartStyle}），故指标预览、驾驶舱 widget、大屏与公开分享页表现一致。
 *
 * <p>取值优先级（渲染时逐字段合并）：调用方覆盖（驾驶舱 widget 级 {@code chartStyle} / 会话级设置）
 * &gt; {@code chart_spec.style}（用户手工设置，或本类自动推断后写入）&gt; 租户级默认
 * （{@code sys_config} CHART 分类）&gt; 代码默认。其中 {@code chart_spec.style} 内部又按
 * 用户已设 &gt; 语义层列单位（{@code semantic_meta.unit}）&gt; 列名关键词推断 逐层回退。
 * 全部字段可空，空值由前端按内置默认渲染，保证老数据（无 style）行为不变。
 *
 * @param xAxisName     X 轴名称（空=前端回落维度列展示名）
 * @param yAxisName     Y 轴名称（空=前端回落首个度量列展示名；多度量时留空由图例表达）
 * @param valueUnit     度量单位（元 / 件 / % / 次 …），拼接到轴名与 tooltip 数值之后
 * @param valueFormat   数值格式：{@link #FORMAT_COMPACT} 万亿紧凑 / {@link #FORMAT_THOUSAND} 千分位 /
 *                      {@link #FORMAT_PLAIN} 原值（空=前端默认紧凑）
 * @param valueDecimals 小数位数（空=自动：整数原样、小数至多 2 位）
 * @param legendPosition 图例位置：{@code top}/{@code bottom}/{@code left}/{@code right}/{@code hidden}（空=前端默认）
 * @param xLabelRotate  X 轴标签旋转角度（0~90，空=前端按类别数自适应）
 */
public record ChartStyle(
        String xAxisName,
        String yAxisName,
        String valueUnit,
        String valueFormat,
        Integer valueDecimals,
        String legendPosition,
        Integer xLabelRotate
) {

    /** 万亿紧凑（1,500,000 → 150万）：窄 widget 下避免长数字拥挤，前端历史默认行为。 */
    public static final String FORMAT_COMPACT = "COMPACT";
    /** 千分位（1500000 → 1,500,000）：财务报表习惯。 */
    public static final String FORMAT_THOUSAND = "THOUSAND";
    /** 原值不加修饰：需要精确读数或值本身较短时。 */
    public static final String FORMAT_PLAIN = "PLAIN";

    /** 图例隐藏（靠 tooltip 与轴标签表达，极小 widget 用）。 */
    public static final String LEGEND_HIDDEN = "hidden";

    /**
     * 是否没有任何有效配置（全空时不必写入 JSON，省 {@code chart_spec} 列宽）。
     *
     * <p>{@code @JsonIgnore} 必需：方法名符合 {@code isXxx} bean 规范，不标注会被 Jackson 当成
     * {@code empty} 属性写进 {@code chart_spec}，白吃列宽并向前端泄露内部判定字段。
     */
    @JsonIgnore
    public boolean isEmpty() {
        return isBlank(xAxisName) && isBlank(yAxisName) && isBlank(valueUnit)
                && isBlank(valueFormat) && valueDecimals == null
                && isBlank(legendPosition) && xLabelRotate == null;
    }

    /** 全空则返回 null，交由调用方决定是否写入（避免下发空对象）。 */
    public static ChartStyle orNull(ChartStyle style) {
        return style == null || style.isEmpty() ? null : style;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
