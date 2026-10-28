package com.aibi.module.dashboard.dto;

import com.aibi.module.datasource.model.QueryResult;

/**
 * 驾驶舱 widget 渲染数据：布局坐标 + 绑定指标的实时结果。用于大屏 {@code GET /api/dashboard/{id}/data}。
 *
 * <p>把 {@link WidgetLayout} 的位置信息与 {@link MetricDataVO} 的数据扁平合并，前端一次拿到即可定位并渲染。
 * {@code metricId} 为空或 {@code result} 为空表示该槽未绑定指标或刷新失败（前端展示占位）。
 *
 * @param chartSpec  图表规格 JSON 对象（透传）
 * @param chartStyle 本 widget 的图表标注覆盖（透传，来自 {@link WidgetLayout#chartStyle()}）；
 *                   为空表示沿用 chartSpec 自带的样式。前端渲染时 chartStyle 优先于 chartSpec.style。
 * @param style      本 widget 的视觉样式覆盖（透传，来自 {@link WidgetLayout#style()}；逐组件卡片/标题/强调色）。
 * @param kind       组件类型（chart / infographic），来自 {@link WidgetLayout#kind()}；前端据此决定渲染路径。
 * @param infographic 信息图配置（透传，来自 {@link WidgetLayout#infographic()}；仅 kind=infographic 时渲染）。
 * @param fx         自由画布模式坐标（百分比，可空）；非自由画布为 null。
 * @param fy         自由画布模式坐标（百分比，可空）。
 * @param fw         自由画布模式尺寸（百分比，可空）。
 * @param fh         自由画布模式尺寸（百分比，可空）。
 *                   <p>这四个字段须与 {@link WidgetLayout} 同步新增并由 {@code getData} 透传——否则大屏会读不到
 *                   （表现为"编辑器可见、大屏空白"）。前端 {@code WidgetDataVO} 必须同步加同名字段。
 * @param image      图片/图标组件配置（透传，来自 {@link WidgetLayout#image()}；仅 kind=image 时渲染）。
 * @param list       列表组件配置（透传，来自 {@link WidgetLayout#list()}；仅 kind=list 时渲染）。
 *                   {@code getData} 在透传该配置的同时仍保留 {@code metricId} 以便取数。
 */
public record WidgetDataVO(
        String i,
        Long metricId,
        String title,
        int x,
        int y,
        int w,
        int h,
        Object chartSpec,
        Object chartStyle,
        Object style,
        String kind,
        Object infographic,
        Double fx,
        Double fy,
        Double fw,
        Double fh,
        QueryResult result,
        Object image,
        Object list) {
}
