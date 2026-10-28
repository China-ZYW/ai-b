package com.aibi.module.attribution.quality;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 缺基准识别规则（需求 §3.4）：用户提问波动类问题但缺少对比基准时，因果归因结论会严重失真。
 *
 * <p>判定：若上下文已显式声明 {@code baseline}（MoM/YoY/peak）则直接通过；否则扫描问题文本是否含对比基准词
 * （环比/同比/上月/去年/前期/高点/历史/对比），无则产出 WARN。纯文本规则，零 LLM、零 SQL。
 */
@Component
public class BaselineMissingRule implements AttributionQualityRule {

    private static final List<String> BASELINE_WORDS = List.of(
            "环比", "同比", "上月", "去年", "前月", "前期", "高点", "历史", "对比", "较");

    @Override
    public String code() {
        return "BASELINE_MISSING";
    }

    @Override
    public List<RuleIssue> check(AttributionContext ctx) {
        if (ctx.baseline() != null && !ctx.baseline().isBlank()) {
            return List.of();
        }
        String q = ctx.question() == null ? "" : ctx.question();
        boolean hasWord = BASELINE_WORDS.stream().anyMatch(q::contains);
        if (hasWord) {
            return List.of();
        }
        return List.of(RuleIssue.warn(code(),
                "提问缺少对比基准（环比/同比/对比高点），归因结论可能失真，建议明确对比基准后再执行。"));
    }
}
