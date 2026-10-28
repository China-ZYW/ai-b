package com.aibi.module.embed.service;

import com.aibi.module.chat.dto.ChatQueryResponse;
import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.dto.SubAnswer;
import com.aibi.module.datasource.model.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 嵌入消费端多意图降级（T12-5 验收补网）纯函数单测：{@code ChatEmbedService.botSummary}
 * 对 parts 应答逐段拼「序号. 子问：摘要/行数/未作答」，同时守住单意图旧行为回归
 * （insight 优先、退化行数、null 兜底）。不起 Spring、不连库。
 */
class EmbedBotSummaryTest {

    /** 组装多意图聚合应答（顶层单结果字段全空、仅 parts 有值——与 askMulti 出口形状一致）。 */
    private static ChatQueryResponse multi(List<SubAnswer> parts) {
        return new ChatQueryResponse("conv", null, null, null, null, null, null, null, parts, null);
    }

    @Test
    @DisplayName("parts 混合段：摘要段用 insight、无洞察段退化成行数、未答段标注追问")
    void partsJoinedPerSegment() {
        QueryResult r = QueryResult.of("SELECT 1", List.of("c"), List.of(List.of(1)), 42, 5);
        String s = ChatEmbedService.botSummary(multi(List.of(
                new SubAnswer("上月销售额", r, null, new Insight("华东最高", null, null), null, null, true),
                new SubAnswer("各区域占比", r, null, null, null, null, true),
                SubAnswer.unanswered("环比如何"))));
        assertEquals("1. 上月销售额：华东最高；2. 各区域占比：已返回 42 行；3. 环比如何：未作答（请单独追问）", s);
    }

    @Test
    @DisplayName("parts 存在时优先于顶层 insight（复合问的顶层 insight 为空壳不采信）")
    void partsWinsOverTopInsight() {
        String s = ChatEmbedService.botSummary(new ChatQueryResponse("conv", null, null, null,
                null, new Insight("顶层空壳", null, null), null, null,
                List.of(new SubAnswer("问A", null, null, null, null, null, true)), null));
        assertTrue(s.startsWith("1. 问A："), s);
    }

    @Test
    @DisplayName("单意图旧行为回归：insight 结论优先，无 insight 退化行数，空结果兜底文案")
    void singleIntentLegacyPaths() {
        ChatQueryResponse withInsight = new ChatQueryResponse("conv", 1L, null, null,
                null, new Insight("一句话结论", null, null), null, null, null, null);
        assertEquals("一句话结论", ChatEmbedService.botSummary(withInsight));
        QueryResult r = QueryResult.of("SELECT 1", List.of("c"), List.of(), 7, 5);
        ChatQueryResponse rowsOnly = new ChatQueryResponse("conv", 1L, r, null, null, null, null, null, null, null);
        assertEquals("已返回 7 行结果", ChatEmbedService.botSummary(rowsOnly));
        assertEquals("已完成查询", ChatEmbedService.botSummary(null));
    }
}
