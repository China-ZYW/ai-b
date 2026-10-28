package com.aibi.module.chat.dto;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.datasource.model.QueryResult;

import java.util.List;

/**
 * 问数响应（M3 起）：在查询结果之上附加图表推荐，并为多轮对话/历史（M4）预留标识字段。
 *
 * <p>{@code conversationId} 与 {@code historyId} 在接入会话历史（M4）前为 {@code null}，
 * 借助 Jackson 的 {@code non_null} 策略不会出现在早期响应里。
 *
 * <p>V3 数据完整度：附加 {@code meta}（截断/修复/尝试次数等元信息）、{@code insight}（AI 解读）
 * 与 {@code verify}（AI 质量校验），三者均可空——未启用或生成失败时为 {@code null}，老消费方无感。
 *
 * @param conversationId 会话 id（多轮对话上下文），未启用会话时为空
 * @param historyId      本次问答落库后的历史记录 id，未落库时为空
 * @param result         查询执行结果（SQL + 列 + 数据行 + 行数 + 耗时）
 * @param chart          图表推荐规格（前端据此渲染可视化）
 * @param meta           结果完整度元信息（可空）
 * @param insight        AI 结果解读（可空）
 * @param verify         AI 质量校验（可空）
 * @param skill          本次答问所用技能（V4 Skill 模式，可空：老链路/异常回落时无）
 * @param parts          多意图拆分子答清单（T10-4，可空）：单意图路径为 {@code null}（前端无感）；
 *                       多意图路径下 {@code result}/{@code chart} 等顶层字段为空、各子问结果在此逐段承载
 * @param clarify        口径歧义澄清提案（T10-2，可空）：命中歧义需反问时非空，此时 {@code result} 等执行字段均为空
 *                       （未执行、未扣配额）；前端据此渲染澄清候选卡，普通直出/多意图路径下为 {@code null}（老消费方无感）
 */
public record ChatQueryResponse(
        String conversationId,
        Long historyId,
        QueryResult result,
        ChartSpec chart,
        ResultMeta meta,
        Insight insight,
        Verify verify,
        SkillInfo skill,
        List<SubAnswer> parts,
        ClarifyProposal clarify
) {

    /** 兼容构造器：无完整度元信息 / AI 解读 / 质量校验 / 技能信息 / 多段拆分 / 澄清（老调用方、大屏/指标/分享复用场景）。 */
    public ChatQueryResponse(String conversationId, Long historyId, QueryResult result, ChartSpec chart) {
        this(conversationId, historyId, result, chart, null, null, null, null, null, null);
    }

    /** 兼容构造器：带完整度元信息与 AI 结论但无技能信息（历史回放等复用场景）。 */
    public ChatQueryResponse(String conversationId, Long historyId, QueryResult result, ChartSpec chart,
                             ResultMeta meta, Insight insight, Verify verify) {
        this(conversationId, historyId, result, chart, meta, insight, verify, null, null, null);
    }

    /** 兼容构造器：单意图完整结果（技能信息齐全、无多段拆分与澄清）——绝大多数现有单意图调用点走此重载。 */
    public ChatQueryResponse(String conversationId, Long historyId, QueryResult result, ChartSpec chart,
                             ResultMeta meta, Insight insight, Verify verify, SkillInfo skill) {
        this(conversationId, historyId, result, chart, meta, insight, verify, skill, null, null);
    }

    /** 兼容构造器：多意图聚合结果（带子答清单、无顶层执行结果与澄清）——T10-4 {@code askMulti} 走此重载。 */
    public ChatQueryResponse(String conversationId, Long historyId, QueryResult result, ChartSpec chart,
                             ResultMeta meta, Insight insight, Verify verify, SkillInfo skill, List<SubAnswer> parts) {
        this(conversationId, historyId, result, chart, meta, insight, verify, skill, parts, null);
    }

    /** 澄清短路构造器（T10-2）：仅携带会话与澄清提案，其余执行字段全空（命中歧义时不执行、不扣配额）。 */
    public static ChatQueryResponse clarify(String conversationId, ClarifyProposal proposal) {
        return new ChatQueryResponse(conversationId, null, null, null, null, null, null, null, null, proposal);
    }
}
