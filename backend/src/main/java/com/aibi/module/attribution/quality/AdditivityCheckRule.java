package com.aibi.module.attribution.quality;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 可加性校验规则（需求 Q7 前置护栏）：维度归因建立在「度量可加」假设上——
 * 对可加度量（金额、订单数）有 {@code GROUP BY 维度 后各成员 Δ 之和 = 总 Δ}；但比率/复合度量
 * （毛利率、客单价、均价）朴素求和 ≠ 总 Δ，直接归因会产出错误结论。
 *
 * <p>判定优先级：
 * <ol>
 *   <li>若指标已显式声明口径可加性（{@code additivityKnown=true}）：非可加则产出 ERROR（硬阻断，必须先走量价因子拆解）；</li>
 *   <li>否则启发式扫描口径表达式：含除法 {@code /} 或比率词（率/占比/毛利率/客单价/均价/比值）则产出 WARN（疑似非可加，建议确认）。</li>
 * </ol>
 * 纯文本规则，零 LLM、零 SQL，永不阻断正常可加度量。
 */
@Component
public class AdditivityCheckRule implements AttributionQualityRule {

    private static final List<String> RATIO_WORDS = List.of(
            "率", "占比", "毛利率", "客单价", "均价", "比值", "比率", "百分");

    @Override
    public String code() {
        return "ADDITIVITY";
    }

    @Override
    public List<RuleIssue> check(AttributionContext ctx) {
        if (ctx.additivityKnown()) {
            if (!ctx.additive()) {
                return List.of(RuleIssue.error(code(),
                        "指标口径为非可加度量（比率/复合），朴素维度归因求和 ≠ 总变化，请先走量价因子拆解。"));
            }
            return List.of();
        }
        String def = ctx.definition() == null ? "" : ctx.definition();
        boolean looksRatio = def.contains("/") || RATIO_WORDS.stream().anyMatch(def::contains);
        if (looksRatio) {
            return List.of(RuleIssue.warn(code(),
                    "口径表达式疑似含除法/比率，可能为非可加度量，归因前请确认口径可加性或先走量价因子拆解。"));
        }
        return List.of();
    }
}
