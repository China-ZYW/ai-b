package com.aibi.module.chat.insight;

import java.util.List;

/**
 * 结果样例行的紧凑文本化工具（问数准确率与图表能力升级 V3 - AI 分析层共用）。
 *
 * <p>把一行查询结果渲染为形如 {@code 列名=值, ...} 的紧凑文本，供 {@link InsightService} 与
 * {@link VerifyService} 组装 Prompt 时复用，避免各自重复实现、保持喂给 LLM 的数据格式一致。
 */
final class ResultSamples {

    private ResultSamples() {
    }

    /** 把一行渲染为紧凑文本；单元格文本按 {@code cellMax} 截断，null 值记为 "null"。 */
    static String rowToString(List<String> cols, List<Object> row, int cellMax) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cols.get(i)).append('=');
            Object v = (row != null && i < row.size()) ? row.get(i) : null;
            sb.append(v == null ? "null" : truncate(String.valueOf(v), cellMax));
        }
        return sb.append('}').toString();
    }

    /** 文本截断（超长补省略号），供单元格与 SQL 摘要复用。 */
    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
