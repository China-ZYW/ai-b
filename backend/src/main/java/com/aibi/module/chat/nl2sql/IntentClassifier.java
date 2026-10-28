package com.aibi.module.chat.nl2sql;

import org.springframework.stereotype.Component;

/**
 * 意图分类器（§3.6）：规则优先、轻量零依赖，为 Prompt 选择针对性指引。
 *
 * <p>关键词按"越具体越优先"的顺序匹配：趋势 → 对比 → 排名 → 明细 → 聚合。
 * 骨架版为纯规则；生产版可叠加一次轻量 LLM 分类或对规则置信度不足时降级 LLM（见设计文档演进路线）。
 */
@Component
public class IntentClassifier {

    /** 根据问题文本分类意图。 */
    public Intent classify(String question) {
        if (question == null || question.isBlank()) {
            return Intent.UNKNOWN;
        }
        String q = question.toLowerCase();
        if (containsAny(q, "趋势", "走势", "变化", "环比", "同比", "每月", "每周", "每天", "每日",
                "按月", "按周", "按天", "按年", "月度", "季度", "年度", "近几个月", "逐月", "逐年")) {
            return Intent.TREND;
        }
        if (containsAny(q, "对比", "比较", "相比", " versus", "vs", "差异", "分别", "各个", "各省份", "各城市")) {
            return Intent.COMPARISON;
        }
        if (containsAny(q, "排名", "排行", "top", "名次", "前几", "最高的", "最低的", "最多的", "最少的", "冠军")) {
            return Intent.RANKING;
        }
        if (containsAny(q, "明细", "列表", "清单", "详细", "哪些", "列出", "有哪些", "逐个")) {
            return Intent.DETAIL;
        }
        if (containsAny(q, "总", "合计", "求和", "汇总", "平均", "均值", "数量", "计数", "多少",
                "占比", "比例", "统计", "总额", "总量", "最大", "最小", "最高", "最低")) {
            return Intent.AGGREGATION;
        }
        return Intent.UNKNOWN;
    }

    private boolean containsAny(String q, String... keywords) {
        for (String k : keywords) {
            if (q.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
