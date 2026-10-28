package com.aibi.module.dashboard.dto;

/**
 * AI 布局顾问的单个组件描述（由前端依据「图表规格 + 已加载的真实结果数据」提取后传入）。
 *
 * <p>只传**特征摘要**而非原始数据行：既省 token，也避免把业务明细数据外发给大模型
 * （本系统的红线：业务库明细只经平台结果层脱敏后展示，不进 Prompt）。
 *
 * @param i           组件 key（与 {@code dashboard.layout[].i} 一致，用于回填坐标）
 * @param name        组件标题 / 指标名（供模型理解语义并做业务分组）
 * @param chartType   图表类型（LINE / BAR / PIE / TABLE …）
 * @param roleHint    前端判定的视觉角色（KPI / TREND / RANKING …），作为模型的参考建议
 * @param rowCount    结果行数
 * @param colCount    结果列数
 * @param categories  类目数（X 轴去重取值个数）
 * @param series      系列数
 * @param maxLabel    最长类目标签字符数（长标签需要更宽的横向空间）
 * @param single      是否单值指标（1 行 1 度量 → 适合做成指标卡）
 */
public record AiLayoutItem(
        String i,
        String name,
        String chartType,
        String roleHint,
        Integer rowCount,
        Integer colCount,
        Integer categories,
        Integer series,
        Integer maxLabel,
        Boolean single) {
}
