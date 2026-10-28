package com.aibi.module.chat.nl2sql;

import java.util.List;

/**
 * 单次问数的上下文载体（M4 引入，M5 扩展）：把"问题 + 会话 id + 多轮历史 + 进度监听"打包传递给
 * {@link Nl2SqlEngine}，避免引擎方法签名随功能增加而反复变动。
 *
 * <p>紧凑构造器对 {@code turns}/{@code listener}/{@code stats} 做空值归一化，调用方可安全传 {@code null}。
 * {@code stats} 为可变载体：引擎在执行/修复过程中回填，{@code ChatService} 据此组装结果完整度元信息。
 *
 * @param question       本轮自然语言问题
 * @param conversationId 会话 id（多轮追问共用；单轮为 {@code null}）
 * @param turns          同一会话此前的问答轮次（时间正序），用于多轮上下文
 * @param listener       进度监听器（同步接口用 {@link ProgressListener#NOOP}）
 * @param stats          执行统计回填载体（{@code null} 时自动新建）
 * @param retryHint      质量校验反馈（校验闭环重试时回喂；首次生成为 {@code null}）
 * @param requestedChart 用户显式要求的图表展示名（如"热力图"；未指定为 {@code null}），
 *                       供 Prompt 约束 SELECT 列必须满足该图型的数据需要
 */
public record QueryContext(
        String question,
        String conversationId,
        List<ConversationTurn> turns,
        ProgressListener listener,
        ExecStats stats,
        String retryHint,
        String requestedChart
) {

    public QueryContext {
        turns = (turns == null) ? List.of() : List.copyOf(turns);
        listener = (listener == null) ? ProgressListener.NOOP : listener;
        stats = (stats == null) ? new ExecStats() : stats;
    }

    /** 兼容构造器：内部新建 {@link ExecStats}（调用方无需读取执行元信息，如 {@link #of}/{@link #withTurns}）。 */
    public QueryContext(String question, String conversationId,
                        List<ConversationTurn> turns, ProgressListener listener) {
        this(question, conversationId, turns, listener, new ExecStats(), null, null);
    }

    /** 兼容构造器：外部传入 {@link ExecStats} 但不带校验反馈/图表要求（首次生成路径）。 */
    public QueryContext(String question, String conversationId,
                        List<ConversationTurn> turns, ProgressListener listener, ExecStats stats) {
        this(question, conversationId, turns, listener, stats, null, null);
    }

    /** 校验闭环重试上下文：复用同一 stats（执行次数累计），仅附加校验反馈。 */
    public QueryContext withRetryHint(String hint) {
        return new QueryContext(question, conversationId, turns, listener, stats, hint, requestedChart);
    }

    /**
     * 多意图拆分子问上下文（T10-4）：换成本子问问句，复用会话 id / 多轮历史 / 进度监听 / 图表要求，
     * 但配<b>全新的 ExecStats</b>（每个子问独立统计，不与前序子问混）且不带校验反馈。
     */
    public QueryContext withQuestion(String nextQuestion) {
        return new QueryContext(nextQuestion, conversationId, turns, listener, new ExecStats(), null, requestedChart);
    }

    /** 单轮、无历史、无进度监听的简易上下文。 */
    public static QueryContext of(String question) {
        return new QueryContext(question, null, List.of(), ProgressListener.NOOP);
    }

    /** 带多轮历史、无进度监听的上下文。 */
    public static QueryContext withTurns(String question, String conversationId, List<ConversationTurn> turns) {
        return new QueryContext(question, conversationId, turns, ProgressListener.NOOP);
    }
}
