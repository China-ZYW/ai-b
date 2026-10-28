package com.aibi.module.query.processor;

import com.aibi.module.datasource.model.QueryResult;

/**
 * 结果处理器 SPI（P3，可扩展点）：在<b>脱敏之后、返回前端之前</b>对查询结果做后处理。
 *
 * <p><b>为什么是 SPI 而不是在 Service 里写死</b>：问数链路的"结果形态"正在持续分化——枚举码值转中文、
 * 金额/百分比格式化、时间粒度归一、导出前的字段裁剪……这些都不是 SQL 能表达的，却也不该塞进
 * 执行内核。做成 SPI 后，<b>新增一种后处理 = 加一个 {@code @Component}</b>，不碰既有代码。
 *
 * <p><b>执行位置有讲究</b>：必须排在 {@code DataMaskGate} 之后——先脱敏再加工，
 * 否则某个处理器可能"顺手"把掩码字段的处理逻辑绕过。
 *
 * <p>实现约定：{@code supports} 返回 false 时必须原样透传，绝不允许因此丢数据；
 * 抛异常由 {@link ResultProcessorChain} 兜住（降级为原结果 + 日志），不阻断查询。
 */
public interface ResultProcessor {

    /** 执行优先级（升序；内置格式化类建议 100 起）。 */
    int order();

    /** 是否处理这份结果；false 时 {@link #process} 绝不会被调用。 */
    boolean supports(QueryResult result, RunContext ctx);

    /**
     * 后处理结果。
     *
     * @return 处理结果；返回 null 时调用方按"未处理"回落原结果
     */
    QueryResult process(QueryResult result, RunContext ctx);

    /** 供实现方读取本次运行上下文（数据源、问题文本、显式图型等）。 */
    record RunContext(Long datasourceId, String question, String chartType, Long templateId) {
    }
}
