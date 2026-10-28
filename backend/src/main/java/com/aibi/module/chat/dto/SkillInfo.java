package com.aibi.module.chat.dto;

/**
 * 本次问数所用技能的信息（V4 Skill 模式）：让结果与"它是怎么来的"一起返回，
 * 前端据此标注"由指标直达回答 / 免大模型"，用户看到的不再是一个黑盒答案。
 *
 * @param code    技能编码（{@code direct_sql} / {@code metric} / {@code fewshot} / {@code nl2sql}）
 * @param name    技能中文名
 * @param llmFree 是否免大模型（免 LLM 的技能不消耗问数配额）
 * @param reason  命中理由（如"命中指标「月度GMV」"），用于结果面板的可解释性提示
 */
public record SkillInfo(
        String code,
        String name,
        boolean llmFree,
        String reason
) {
}
