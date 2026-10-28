package com.aibi.module.chat.skill;

/**
 * 技能匹配结果：路由据此决定"本次问数由哪个技能接手"。
 *
 * @param matched 是否命中（false 表示本技能不适用）
 * @param score   匹配分（0~100，越高越确信；仅同一次路由内比较）
 * @param reason  命中理由（落日志与前端提示，便于解释"为什么这样答"）
 */
public record SkillMatch(boolean matched, int score, String reason) {

    /** 未命中常量（避免每次构造对象）。 */
    public static final SkillMatch NO = new SkillMatch(false, 0, null);

    /** 命中构造：score 越界时裁剪到 0~100，保证路由比较稳定。 */
    public static SkillMatch of(int score, String reason) {
        int s = Math.max(0, Math.min(100, score));
        return new SkillMatch(true, s, reason);
    }
}
