package com.aibi.module.chat.distill;

import java.util.Map;

/**
 * 一条待治理问题（T11-1）：一个「反复出问题的归一化问法」聚合出的治理建议。
 *
 * <p>只<b>建议</b>不<b>自动改</b>——守住"AI 无权改权威口径"边界，人工确认后一键跳治理页处理。
 *
 * @param signal         命中信号（决定优先级与建议方向）
 * @param question       代表问句（簇内最长/最新的一条，供人一眼看懂用户到底怎么问）
 * @param normalizedKey  归一化分组键（"近30天销售额""最近一个月销售额"归一到同键）
 * @param occurrences    窗口内出现次数
 * @param skill          该问法当时走的技能（direct_sql/metric/fewshot/nl2sql，可空）
 * @param errorMsgSample 失败样本错误信息（S1/S5 有值，助定位）
 * @param suggestedAction 建议治理动作（告诉管理员"该补什么"）
 * @param sampleSql      代表 SQL（簇内优先取同归一键 OK 行的 sql；无则取失败行草稿，可空，供人工修正后转写）
 * @param hasSuccessSql  该簇是否命中可信成功 SQL（true=可直接转写；false=草稿需人工校对）
 * @param jumpAnchor     一键跳转锚点（数据源/技能/归一键/historyId 等，供前端路由到对应治理页）
 * @param ledgerId       台账行 id（二期 B，纯内核产出时为 null，经 LedgerService.attachStatus 填充）
 * @param status         处置态 OPEN/CONVERTED/DISMISSED（二期 B，默认 OPEN）
 * @param refType        转写产出类型 METRIC/FEWSHOT（二期 B，null=未转写）
 * @param refId          转写产出资源 id（二期 B，null=未转写）
 * @param userSuggestedSql 用户在点踩时提交的期望正确 SQL（T11-2，簇内首个非空 expected_sql；无则 null）。作转写预填的高价值草稿，仍需人工复核
 * @param userNote         用户点踩时的口径说明/错因（T11-2，同簇首个非空 feedback_note；无则 null）
 */
public record GovernanceIssue(GovernanceSignal signal,
                              String question,
                              String normalizedKey,
                              int occurrences,
                              String skill,
                              String errorMsgSample,
                              String suggestedAction,
                              String sampleSql,
                              boolean hasSuccessSql,
                              Map<String, Object> jumpAnchor,
                              // 二期 B 台账字段（纯内核产出时均为 null/"OPEN"，由 LedgerService.attachStatus 填充）
                              Long ledgerId,
                              String status,
                              String refType,
                              Long refId,
                              // T11-2 反馈修正采集字段（无建议时为 null）
                              String userSuggestedSql,
                              String userNote) {

    /**
     * T11-2 前的 14 参兼容构造器：委派 {@code userSuggestedSql/userNote} 为 null。
     * 保证既有内联构造点（测试等）零改动，与 ResultMeta/二期 B 同范式。
     */
    public GovernanceIssue(GovernanceSignal signal, String question, String normalizedKey, int occurrences,
                           String skill, String errorMsgSample, String suggestedAction, String sampleSql,
                           boolean hasSuccessSql, Map<String, Object> jumpAnchor,
                           Long ledgerId, String status, String refType, Long refId) {
        this(signal, question, normalizedKey, occurrences, skill, errorMsgSample, suggestedAction,
                sampleSql, hasSuccessSql, jumpAnchor, ledgerId, status, refType, refId, null, null);
    }
}
