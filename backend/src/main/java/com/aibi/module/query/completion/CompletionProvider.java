package com.aibi.module.query.completion;

import java.util.List;

/**
 * SQL 编辑器补全 SPI（P2，可扩展点）：给定光标前的片段，返回可补全项。
 *
 * <p><b>为什么需要 SPI</b>：补全源不止一种——库表结构是确定性的，业务库却常有
 * 语义层中文别名、人工登记的 JOIN 关联、历史高频查询片段等"非物理"来源。做成 SPI 后，
 * <b>新增一个补全源 = 加一个 {@code @Component}</b>（如 {@code HistoryCompletionProvider}、
 * {@code RelationCompletionProvider}），前端与编辑器零改动。
 *
 * <p>实现约定：{@code supports} 返回 false 时 {@code complete} 绝不会被调用；
 * 抛异常由 {@link CompletionAggregator} 兜住（跳过该来源，不阻断输入体验）。
 */
public interface CompletionProvider {

    /** 优先级（升序；内置结构补全身居 100）。 */
    int order();

    /** 本来源是否处理这次补全请求。 */
    boolean supports(CompletionRequest request);

    /** 返回候选（返回 null 时按空列表处理）。 */
    List<CompletionItem> complete(CompletionRequest request);

    /** 本来源的中文名（供调试/日志）。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
