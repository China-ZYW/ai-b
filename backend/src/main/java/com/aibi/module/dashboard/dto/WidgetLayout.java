package com.aibi.module.dashboard.dto;

/**
 * 驾驶舱网格布局单元（12 列网格，坐标单位=列/行）。序列化为 {@code dashboard.layout} JSON 数组的元素。
 *
 * <p>与前端 {@code grid-layout-plus} 的 {@code {i,x,y,w,h}} 约定对齐；{@code metricId} 为空表示未绑定指标的占位槽。
 *
 * @param i          单元唯一标识（前端 grid key，通常等于 metricId 或槽位序号）
 * @param metricId   绑定的指标 id（模板占位槽可空）
 * @param x          列起点（0-based）
 * @param y          行起点（0-based）
 * @param w          宽度（列数）
 * @param h          高度（行数）
 * @param title      widget 标题（可空，前端用指标名兜底）
 * @param chartStyle 本 widget 的图表标注覆盖（透传 JSON 对象，形如 {@code {"yAxisName":"销售额","valueUnit":"元"}}）。
 *                   为空表示沿用指标自身的 chart_spec；同一指标被多个 widget 引用时可各自标注不同。
 * @param style      本 widget 的视觉样式覆盖（透传 JSON 对象，形如 {@code {"cardBg":"#0b1b3a","titleColor":"#fff","radius":8}}）。
 *                   为空表示跟随大屏主题；用于逐组件自定义卡片/标题/强调色。
 * @param kind       组件类型：chart=数据图表（默认，绑定指标取数）；infographic=信息图（内容驱动，不取数）。缺省 chart，向后兼容。
 * @param infographic 信息图配置 JSON 对象（仅 kind=infographic 时生效；透传）。
 * @param fx         自由画布模式（dashboard.styleConfig.free=true）下组件左上角横坐标，单位=画布宽度的百分比（0–100，可空）。
 * @param fy         自由画布模式下的左上角纵坐标，单位=画布高度的百分比（0–100，可空）。
 * @param fw         自由画布模式下的宽度，单位=画布宽度的百分比（0–100，可空）。
 * @param fh         自由画布模式下的高度，单位=画布高度的百分比（0–100，可空）。
 *                   <p>非自由画布（网格模式）下这四个字段为 null，渲染落点改用 {@code x/y/w/h} 整数网格坐标。
 *                   字段可空保证老数据（无 fx/fy/fw/fh）反序列化不报错，向后兼容。
 * @param image      图片/图标组件配置（仅 kind=image 时生效）：{@code {"url":"...","fit":"contain|cover|fill","round":false,"alt":"..."}}。
 *                   为自由放置的 logo/图标/装饰图片，不绑定指标、不取数；透传 JSON 对象。
 * @param list       列表组件配置（仅 kind=list 时生效）：{@code {"primaryField":"...","secondaryField":"...","valueField":"...","autoScroll":true,"scrollSpeed":30}}。
 *                   绑定指标、取数后把结果渲染为可滚动榜单；不绑定指标、不取数；透传 JSON 对象。
 */
public record WidgetLayout(
        String i,
        Long metricId,
        int x,
        int y,
        int w,
        int h,
        String title,
        Object chartStyle,
        Object style,
        String kind,
        Object infographic,
        Double fx,
        Double fy,
        Double fw,
        Double fh,
        Object image,
        Object list) {
}
