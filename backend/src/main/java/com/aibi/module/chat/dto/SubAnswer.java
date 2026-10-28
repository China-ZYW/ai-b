package com.aibi.module.chat.dto;

import com.aibi.module.chat.chart.ChartSpec;
import com.aibi.module.datasource.model.QueryResult;

/**
 * 多意图拆分子答（T10-4）：一份复合问法拆出的<b>单个原子子问</b>的完整作答结果。
 *
 * <p>聚合在 {@link ChatQueryResponse#parts()} 里承载多段答案；单意图路径下 {@code parts} 为空、本类不出现。
 * 各字段与 {@link ChatQueryResponse} 的单结果语义一致（脱敏 / 图表 / 解读 / 校验都已在单问管线里跑完）。
 *
 * @param subQuestion 本子问（拆分器给出的原子问句；未拆时即原始问句）
 * @param result      本子问的查询执行结果；未作答时为空
 * @param chart       本子问的图表推荐（可空）
 * @param insight     本子问的 AI 解读（可空）
 * @param verify      本子问的 AI 质量校验（可空）
 * @param skill       本子问所用技能（可空）
 * @param answered    本子问是否已作答；{@code false} 承载"批量超时未作答 / 代价超预算被拒"等，
 *                    前端显示"该子问未作答，请单独追问"
 */
public record SubAnswer(
        String subQuestion,
        QueryResult result,
        ChartSpec chart,
        Insight insight,
        Verify verify,
        SkillInfo skill,
        boolean answered
) {

    /** 未作答占位（批量超预算/被熔断拒绝时）：仅带子问文本与提示性 result，其余为空。 */
    public static SubAnswer unanswered(String subQuestion) {
        return new SubAnswer(subQuestion, null, null, null, null, null, false);
    }
}
