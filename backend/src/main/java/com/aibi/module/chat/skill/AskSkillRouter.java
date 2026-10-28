package com.aibi.module.chat.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 问数技能路由（Skill 模式的中枢）：把"问题"分派给最合适的技能，并在无命中时落到兜底技能。
 *
 * <p><b>路由规则</b>（短路判定，先到先得）：
 * <ol>
 *   <li>请求显式指定 {@code skill} 且该技能存在、可用且能处理 → 直接用（便于调试与"强制走大模型"等运营动作）；</li>
 *   <li>否则按 {@link AskSkill#order()} 升序逐个 {@link AskSkill#plan}，取<b>首个命中</b>；</li>
 *   <li>全部未命中 → 兜底技能（order 最大者，即 NL2SQL 全量生成）。</li>
 * </ol>
 *
 * <p><b>为什么"首个命中"而不是"最高分"</b>：确定性技能（直连 SQL / 指标 / 样例 / 数据字典）的判定是精确匹配，
 * 语义上互斥且代价极低；让它们按优先级短路，既避免全量打分，也杜绝"指标名与样例问法都像时"的模糊取舍。
 *
 * <p><b>为什么 plan 一次成型</b>：路由阶段产出的 {@link SkillPlan} 已闭包了解析结果，执行阶段不再二次判定。
 * 这既省掉重复查库，也消除"路由时命中、执行时资源已被删除"的不一致窗口。
 *
 * <p><b>容错</b>：单个技能 {@code plan} 抛异常时视作未命中（仅记 debug），绝不拖垮整条问数链路；
 * 兜底技能不命中属装配错误，直接抛 {@link IllegalStateException} 以便启动期/首个请求即暴露。
 */
@Component
public class AskSkillRouter {

    private static final Logger log = LoggerFactory.getLogger(AskSkillRouter.class);

    /** 按优先级升序的技能列表（不可变）。 */
    private final List<AskSkill> ordered;
    private final Map<String, AskSkill> byCode;
    private final AskSkill fallback;

    public AskSkillRouter(List<AskSkill> skills) {
        this.ordered = skills.stream()
                .filter(s -> s != null)
                .sorted(Comparator.comparingInt(AskSkill::order))
                .toList();
        this.byCode = ordered.stream().collect(Collectors.toMap(AskSkill::code, Function.identity(), (a, b) -> a));
        this.fallback = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);
        log.info("问数技能已注册 {} 个: {}", ordered.size(),
                ordered.stream().map(s -> s.code() + "(" + s.order() + ")").toList());
    }

    /**
     * 路由决策结果。
     *
     * @param skill  最终接手的技能（决定 llmFree / rewritable 等编排策略）
     * @param plan   本次的执行计划（已闭包解析结果，可直接 {@link SkillPlan#run()}）
     * @param forced 是否由请求显式指定
     */
    public record SkillDecision(AskSkill skill, SkillPlan plan, boolean forced) {
    }

    /**
     * 为本次问数选定技能。
     *
     * @param ctx        问数上下文
     * @param forcedCode 请求显式指定的技能编码（可空/空白表示自动路由）
     */
    public SkillDecision route(AskContext ctx, String forcedCode) {
        if (forcedCode != null && !forcedCode.isBlank()) {
            AskSkill forced = byCode.get(forcedCode.trim().toLowerCase(Locale.ROOT));
            if (forced != null && forced.enabled()) {
                SkillPlan plan = safePlan(forced, ctx);
                if (plan.matched()) {
                    return new SkillDecision(forced, plan, true);
                }
                log.debug("指定的技能[{}] 无法处理本次提问，回落自动路由", forcedCode);
            } else {
                log.debug("指定的技能[{}] 不存在或已停用，回落自动路由", forcedCode);
            }
        }
        for (AskSkill skill : ordered) {
            if (!skill.enabled() || skill == fallback) {
                continue;
            }
            SkillPlan plan = safePlan(skill, ctx);
            if (plan.matched()) {
                return new SkillDecision(skill, plan, false);
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("未注册任何问数技能，无法处理问数请求");
        }
        SkillPlan fallbackPlan = safePlan(fallback, ctx);
        if (!fallbackPlan.matched()) {
            // 兜底技能契约上必须恒命中（如 NL2SQL 无条件生成）。不命中只可能是装配或实现错误，
            // 与其悄悄走到 null 引发后续 NPE，不如立刻炸掉并留下明确线索。
            throw new IllegalStateException("兜底技能[" + fallback.code() + "] 必须恒命中，但未产生可执行计划");
        }
        return new SkillDecision(fallback, fallbackPlan, false);
    }

    /**
     * 安全地生成计划：技能自身的任何运行时异常都降级为"未命中"，由兜底技能接管。
     * 这条边界保证了"某个技能依赖的表被删了/配置坏了"不会让用户彻底问不了数。
     */
    private SkillPlan safePlan(AskSkill skill, AskContext ctx) {
        try {
            SkillPlan plan = skill.plan(ctx);
            return plan == null ? SkillPlan.NO : plan;
        } catch (RuntimeException e) {
            log.debug("技能[{}] 解析异常，视为未命中：{}", skill.code(), e.getMessage());
            return SkillPlan.NO;
        }
    }

    /** 技能清单（供 {@code GET /api/chat/skills} 透出能力）。 */
    public List<SkillVO> descriptors() {
        return ordered.stream()
                .map(s -> SkillVO.of(s, s == fallback))
                .toList();
    }

    /** 按编码取技能（不存在返回 null）。 */
    public AskSkill byCode(String code) {
        return code == null ? null : byCode.get(code.trim().toLowerCase(Locale.ROOT));
    }

    /** 兜底技能（恒命中的 NL2SQL）。 */
    public AskSkill fallback() {
        return fallback;
    }
}
