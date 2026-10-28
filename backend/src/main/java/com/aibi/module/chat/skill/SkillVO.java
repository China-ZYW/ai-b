package com.aibi.module.chat.skill;

/**
 * 技能清单项（{@code GET /api/chat/skills} 响应元素）：向前端暴露"系统有哪些答问能力"，
 * 使结果面板能解释本次由哪个技能回答、是否走了大模型。
 *
 * @param code        技能编码
 * @param name        技能名
 * @param description 能力说明
 * @param order       路由优先级（越小越先判定）
 * @param llmFree     是否免 LLM（免 LLM 的技能不消耗问数配额）
 * @param fallback    是否为兜底技能（恒命中，保证任何问题都有出口）
 */
public record SkillVO(
        String code,
        String name,
        String description,
        int order,
        boolean llmFree,
        boolean fallback
) {

    public static SkillVO of(AskSkill skill, boolean fallback) {
        return new SkillVO(skill.code(), skill.name(), skill.description(),
                skill.order(), skill.llmFree(), fallback);
    }
}
