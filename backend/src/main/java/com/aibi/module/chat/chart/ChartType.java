package com.aibi.module.chat.chart;

/**
 * 图表类型（M3 图表推荐产物）。
 *
 * <p>与前端可视化组件一一对应；推荐引擎据"结果结构 + 问数意图"选择最合适的一种，
 * 无法可视化时回退 {@link #TABLE}（明细表）。
 */
public enum ChartType {

    /** 折线图：时间序列趋势。 */
    LINE("折线图"),

    /** 柱状图：分类对比 / 排名 / 多维度分组对比。 */
    BAR("柱状图"),

    /** 饼图：占比 / 构成（部分与整体）。 */
    PIE("饼图"),

    /** 面积图：趋势 + 量感（折线 + 填充）。 */
    AREA("面积图"),

    /** 横向条形图：类别名较长 / 排名场景的横向对比。 */
    HBAR("条形图"),

    /** 热力图：两个维度交叉的矩阵强度（V3）。 */
    HEATMAP("热力图"),

    /** 地图：含地理维度（省/市/区县）的区域分布（V3）。 */
    MAP("地图"),

    /** 雷达图：单对象在多指标上的画像（V3）。 */
    RADAR("雷达图"),

    /** 漏斗图：有序阶段的转化（V3）。 */
    FUNNEL("漏斗图"),

    /** 仪表盘：单一指标相对目标/上限的达成率（V3）。 */
    GAUGE("仪表盘"),

    /** 散点图：两个数值变量的相关性（V3）。 */
    SCATTER("散点图"),

    /** 明细表：无可视化维度或无需图表时的兜底。 */
    TABLE("明细表");

    private final String displayName;

    ChartType(String displayName) {
        this.displayName = displayName;
    }

    /** 面向用户的中文名称。 */
    public String displayName() {
        return displayName;
    }
}
