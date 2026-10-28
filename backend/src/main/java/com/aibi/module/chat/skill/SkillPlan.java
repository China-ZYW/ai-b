package com.aibi.module.chat.skill;

import com.aibi.module.datasource.model.QueryResult;

import java.util.function.Supplier;

/**
 * 技能执行计划：一次解析的产物，把"能不能答（命中判定）"与"怎么答（执行动作）"合并成一个对象。
 *
 * <p><b>为什么要合并</b>：早期契约是 {@code match(ctx)} 判定命中 + {@code execute(ctx)} 真正干活两步。
 * 两步各自拿不到对方的中间结果，于是"先查一遍指标列表判定命中、命中后再查一遍列表取 SQL"成了必然——
 * 一次问数打两遍同样的表，指标/样例越多浪费越明显。改为 {@link AskSkill#plan} 一次解析后，
 * 查到的实体（{@code Metric} / {@code FewShotExample}）被 {@link #runner} 闭包直接捕获，
 * 后续执行零查询，既省一半 IO，也彻底消除"match 与 execute 之间数据被他人改掉"的窗口。
 *
 * <p><b>惰性</b>：{@code runner} 只是动作声明，不执行任何查询；真正的执行由编排层在同一个共通入口
 * （{@code ChatService} 并发护栏内）触发，技能自身无法绕过统一后置（脱敏 / 图表 / 解读 / 历史）。
 *
 * @param match  命中元数据（是否命中、确信度、理由）
 * @param runner 执行动作（未命中时为 null）
 */
public record SkillPlan(SkillMatch match, Supplier<QueryResult> runner) {

    /** 未命中常量：不携带任何执行动作，避免每次判定都构造对象。 */
    public static final SkillPlan NO = new SkillPlan(SkillMatch.NO, null);

    /**
     * 命中构造：把已经查到的资源闭包进 runner，使后续执行不再重复查询。
     *
     * @param score  确信度（0~100，越界自动裁剪）
     * @param reason 命中理由（日志与前端"为什么这样答"提示）
     * @param runner 已在闭包中持有查询结果的懒执行动作
     */
    public static SkillPlan of(int score, String reason, Supplier<QueryResult> runner) {
        return new SkillPlan(SkillMatch.of(score, reason), runner);
    }

    /** 是否可命中并执行（命中元数据与执行动作必须同时存在，缺一视为装配错误）。 */
    public boolean matched() {
        return match != null && match.matched() && runner != null;
    }

    /** 命中理由（落日志与前端提示）。 */
    public String reason() {
        return match == null ? null : match.reason();
    }

    /** 确信度（0~100）。 */
    public int score() {
        return match == null ? 0 : match.score();
    }

    /**
     * 执行计划：真正触发 SQL 执行。
     *
     * @throws IllegalStateException 计划未命中（编排层不应调用，属调用方逻辑错误）
     */
    public QueryResult run() {
        if (!matched()) {
            throw new IllegalStateException("技能未命中或缺少执行动作，不可执行；调用方需先校验 matched()");
        }
        return runner.get();
    }
}
