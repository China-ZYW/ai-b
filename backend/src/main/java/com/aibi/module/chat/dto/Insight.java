package com.aibi.module.chat.dto;

import java.util.List;

/**
 * AI 结果解读（问数准确率与图表能力升级 V3 - 数据完整度）：LLM 基于问题 + SQL + 结果样例行
 * 生成的自然语言洞察，帮助用户“看懂数据”。由 {@code InsightService} 产出，失败时为 {@code null}。
 *
 * @param summary   一句话结论
 * @param keyPoints 关键要点（趋势 / 对比 / Top 项等）
 * @param anomalies 异常提示（离群值 / 空值 / 口径存疑等，可空）
 */
public record Insight(
        String summary,
        List<String> keyPoints,
        List<String> anomalies
) {
}
