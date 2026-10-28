package com.aibi.module.chat.service;

import com.aibi.module.quota.service.QuotaService;
import org.springframework.stereotype.Component;

/**
 * 问数配额计费协作类（T8.3 自 ChatService 抽出，对应编排第③步的扣额判定）。
 *
 * <p>计费策略原文语义：免 LLM 的确定性技能（直连 SQL / 指标直达 / 样例复用）
 * <b>不消耗问数配额</b>——既省成本，也把"把问法沉淀为指标/样例"变成对用户有即时回报的行为。
 * 判定输入是技能路由决策的 {@code llmFree} 标记，扣额动作与异常语义全部由
 * {@link QuotaService#checkAndConsume} 原样承担（超限抛出即中断主链路）。
 */
@Component
public class QuotaCharger {

    private final QuotaService quotaService;

    public QuotaCharger(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 仅当选中技能需要大模型时才检查并消耗租户配额。 */
    public void consumeIfNeeded(Long tenantId, boolean llmFree) {
        if (!llmFree) {
            quotaService.checkAndConsume(tenantId);
        }
    }
}
