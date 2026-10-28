package com.aibi.module.attribution.quality;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 维度稀疏度规则（需求 §3.4）：归因维度成员样本量过少时，单成员拉动贡献会被个别极端值放大、结论不可信。
 *
 * <p>判定：本期维度成员行数 {@code rowCount} 低于阈值（默认 3）则产出 WARN。阈值后续可升为配置项
 * （{@code ATTRIBUTION / sparsity-min-rows}）。纯数值规则，零 LLM、零 SQL。
 */
@Component
public class DimensionSparsityRule implements AttributionQualityRule {

    /** 维度成员最少行数；低于此值视为样本稀疏。 */
    private static final int MIN_ROWS = 3;

    @Override
    public String code() {
        return "DIMENSION_SPARSITY";
    }

    @Override
    public List<RuleIssue> check(AttributionContext ctx) {
        if (ctx.rowCount() >= MIN_ROWS) {
            return List.of();
        }
        return List.of(RuleIssue.warn(code(),
                "维度成员样本量不足（" + ctx.rowCount() + " 行，建议 ≥ " + MIN_ROWS
                        + "），结论仅供参考，可能存在稀疏偏差。"));
    }
}
