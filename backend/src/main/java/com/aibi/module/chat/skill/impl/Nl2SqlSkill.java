package com.aibi.module.chat.skill.impl;

import com.aibi.module.chat.nl2sql.Nl2SqlEngine;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkill;
import com.aibi.module.chat.skill.SkillPlan;
import org.springframework.stereotype.Component;

/**
 * NL2SQL 兜底技能：所有确定性技能都未命中时的通用能力（意图 → 选表 → 少样本 → Prompt →
 * 大模型生成 → 安全校验 → 只读执行 → 报错/空结果自修复）。
 *
 * <p>作为 {@link com.aibi.module.chat.skill.AskSkillRouter#fallback()} 恒命中（order 最大），
 * 保证任何问题都有出口；真正的生成逻辑仍在 {@link Nl2SqlEngine}，本类只是把它接入技能契约。
 */
@Component
public class Nl2SqlSkill implements AskSkill {

    public static final String CODE = "nl2sql";

    private final Nl2SqlEngine engine;

    public Nl2SqlSkill(Nl2SqlEngine engine) {
        this.engine = engine;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return "智能生成";
    }

    @Override
    public String description() {
        return "通用自然语言转 SQL：理解意图、裁剪相关表、大模型生成并自动校验修复";
    }

    @Override
    public int order() {
        return 1000;
    }

    /** 兜底技能不允许关闭：关闭等于让问数链路失去出口。 */
    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean llmFree() {
        return false;
    }

    /**
     * 兜底技能恒命中，保证链路一定有出口；生成动作包进计划延迟到编排层触发。
     *
     * <p>本技能这也是唯一参与"质量校验驱动重试"的技能（其余确定性技能 {@link #rewritable()} 为 false），
     * 因为只有大模型生成的 SQL 才可能因理解偏差而答非所问，也才有可能被改写得更好。
     */
    @Override
    public SkillPlan plan(AskContext ctx) {
        return SkillPlan.of(1, "通用生成", () -> engine.generate(ctx.config(), ctx.query()));
    }
}
