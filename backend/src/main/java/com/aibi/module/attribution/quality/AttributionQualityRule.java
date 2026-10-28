package com.aibi.module.attribution.quality;

import java.util.List;

/**
 * 归因质量校验规则（配置式规则引擎的单一插槽，阶段 0 地基，阶段 1 由 {@code AttributionService} 注入 {@code List} 调用）。
 *
 * <p>与现有 {@code VerifyService}（LLM 通用评审）解耦：本接口走纯静态/确定性判定，零 LLM 调用、零成本、
 * 永不抛错；用于落地需求 §3.4 的「缺基准识别」「维度稀疏度」与「非可加度量拦截」三条规则。新增规则只需
 * 实现本接口并标 {@code @Component}，自动进入归因校验链，无需改动主链路。
 */
public interface AttributionQualityRule {

    /** 规则编码（对应 {@link RuleIssue#code()}）。 */
    String code();

    /**
     * 检查归因上下文，返回问题清单（空列表=通过）。
     *
     * @param ctx 不可变归因上下文
     * @return 命中的 {@link RuleIssue}（按严重级别 WARN/ERROR 排序由调用方决定）
     */
    List<RuleIssue> check(AttributionContext ctx);
}
