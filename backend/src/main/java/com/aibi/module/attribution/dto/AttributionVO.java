package com.aibi.module.attribution.dto;

import java.math.BigDecimal;
import java.util.List;

import com.aibi.module.attribution.dto.AttributionTrendPoint;

/**
 * 归因分析结果（一次"为什么波动"的完整答案）。
 *
 * <p><b>三份产物、三套 SQL 分离</b>（需求 §3.1 审计要求）：趋势、量价因子、维度归因各自生成独立 SQL，
 * 在 VO 里分字段透出，前端按标签展示、支持单独查看与复制。绝不把三份结果混成一张表——
 * 那样既无法逐份验证口径，也让审计人员无法回答"这个数是怎么算出来的"。
 *
 * <p><b>数值一律由 SQL/Java 计算</b>：{@code items} 与 {@code factors} 中的每一个数字都来自确定的算术，
 * LLM 只被允许基于这些已算好的结构化结果生成 {@code insight} 文案（需求 §6 硬性约束），
 * 从根本上杜绝"大模型随手编一个 -38%"。
 *
 * @param metricId        指标 id
 * @param metricName      指标（别名优先）名称
 * @param dimension       归因维度列名
 * @param dimensionLabel  维度展示名
 * @param baseline        实际生效的对比基准（MOM/YOY/PEAK）
 * @param currentPeriod   本期期间
 * @param basePeriod      基期期间（PEAK 时为峰值期）
 * @param curTotal        本期度量合计
 * @param baseTotal       基期度量合计
 * @param totalDelta      总变化 = curTotal − baseTotal
 * @param items           维度成员贡献明细（按贡献绝对值降序，已截断到 topN）
 * @param factors         量价因子拆解瀑布段（基期→量→价→交叉→本期）；量字段缺失时为空列表
 * @param trend           趋势序列（按期聚合的度量值，ASC 排序），供前端画趋势折线；与三套审计 SQL 解耦
 * @param additive        度量是否可加（false 时归因结论不可信，前端应显著提示）
 * @param trendSql        趋势 SQL（可查看/复制）
 * @param factorSql       量价因子 SQL（可查看/复制）
 * @param attributionSql  维度归因 SQL（可查看/复制）
 * @param issues          质量校验命中项（缺基准 / 维度稀疏 / 不可加 等）
 * @param insight         归因解读文案：由结构化结果经 {@code buildInsight()} 确定性拼装生成，
 *                        <b>当前实现不调用大模型</b>（需求 §6 要求数值 100% 可审计，模板化可保证每个数字都对得上 items）；
 *                        若未来接入 LLM 润色，仍只能基于已算好的 {@code items}/{@code factors} 文字翻译，
 *                        绝不允许模型参与任何数值计算。前端展示文案应如实称为「归因解读」而非「AI 归因解读」。
 * @param costMs          本次归因总耗时（毫秒）
 * @param confidence      归因场景置信度（0~1）：由基准明确性、维度基数、结果稀疏度等可观测信号扣分得出
 * @param gate            置信度门控结果：DIRECT=直出、CLARIFY=需单轮澄清、BLOCK=禁止自动归因（需求 §3.2）
 * @param sqlVisible      当前身份是否允许查看原始 SQL（需求 §4）；false 时三个 SQL 字段恒为 null——
 *                        <b>服务端不下发</b>而非前端隐藏，前端据此展示"当前角色不可查看 SQL"提示
 */
public record AttributionVO(
        Long metricId,
        String metricName,
        String dimension,
        String dimensionLabel,
        String baseline,
        String currentPeriod,
        String basePeriod,
        BigDecimal curTotal,
        BigDecimal baseTotal,
        BigDecimal totalDelta,
        List<ContributionItem> items,
        List<FactorItem> factors,
        List<AttributionTrendPoint> trend,
        boolean additive,
        String trendSql,
        String factorSql,
        String attributionSql,
        List<String> issues,
        String insight,
        long costMs,
        Double confidence,
        String gate,
        boolean sqlVisible
) {

    /** 门控：置信度 ≥60%，一次性返回全部图表与解读，保持一键查询体验。 */
    public static final String GATE_DIRECT = "DIRECT";
    /** 门控：30% ≤ 置信度 ＜60%，触发单轮澄清（最多 1 轮）。 */
    public static final String GATE_CLARIFY = "CLARIFY";
    /** 门控：置信度 ＜30%，禁止自动执行归因，提示前往问数调优中心完善语义与口径。 */
    public static final String GATE_BLOCK = "BLOCK";
}
