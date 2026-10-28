package com.aibi.module.chat.nl2sql;

/**
 * 问数意图（§3.6 IntentClassifier 的产物）。
 *
 * <p>不同意图对应不同的 SQL 形态，引擎据此在 Prompt 中给出针对性指引，提升首次命中率。
 * 每个枚举携带一段 {@link #hint()}，由 {@code PromptBuilder} 注入提示词。
 */
public enum Intent {

    /** 明细查询：筛选并列出记录，通常不聚合。 */
    DETAIL("明细查询：按条件筛选并列出记录，一般不做聚合，必要时用 ORDER BY 排序"),

    /** 聚合统计：求和/计数/平均等，必须正确 GROUP BY。 */
    AGGREGATION("聚合统计：使用 SUM/COUNT/AVG/MAX/MIN 等，非聚合列必须出现在 GROUP BY 中"),

    /** 趋势分析：按时间维度分组并排序，体现时间序列。 */
    TREND("趋势分析：按时间维度（日/周/月/年）分组并按时间升序排列，体现序列变化"),

    /** 对比分析：对多个分组/维度做横向对比。 */
    COMPARISON("对比分析：对多个维度或分组做横向对比，可用分组聚合或多列 CASE WHEN"),

    /** 排名 / TopN：按度量排序并取前 N。 */
    RANKING("排名/TopN：按度量排序并取前 N 条（MySQL/PG/ClickHouse 用 LIMIT，SQL Server 用 TOP，Oracle 用 FETCH FIRST）"),

    /** 无法明确归类：按最合理的方式生成只读查询。 */
    UNKNOWN("意图不明确：按最能回答问题的方式生成一条只读查询");

    private final String hint;

    Intent(String hint) {
        this.hint = hint;
    }

    /** 注入 Prompt 的意图指引文本。 */
    public String hint() {
        return hint;
    }
}
