package com.aibi.module.mcp.service;

import com.aibi.module.chat.dto.Insight;
import com.aibi.module.chat.dto.SubAnswer;
import com.aibi.module.datasource.model.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 消费端多意图降级（T12-5 验收补网）纯函数单测：{@code McpServerService.partsDigest}
 * 逐段收敛子答为结构化摘要——已答段带 sql/rowCount/summary，未答段只带占位，
 * 保证外部大模型不再收到"answered=true 但无内容"的空壳。不起 Spring、不连库。
 */
class McpPartsDigestTest {

    @Test
    @DisplayName("已答段：subQuestion/answered/sql/rowCount/summary 齐备")
    void answeredPartCarriesDigest() {
        QueryResult r = QueryResult.of("SELECT 1", List.of("c"), List.of(List.of(1)), 1, 5);
        Insight ins = new Insight("结论一句话", List.of(), List.of());
        List<Map<String, Object>> out = McpServerService.partsDigest(List.of(
                new SubAnswer("上月销售额是多少", r, null, ins, null, null, true)));
        assertEquals(1, out.size());
        Map<String, Object> m = out.get(0);
        assertEquals("上月销售额是多少", m.get("subQuestion"));
        assertEquals(true, m.get("answered"));
        assertEquals("SELECT 1", m.get("sql"));
        assertEquals(1, m.get("rowCount"));
        assertEquals("结论一句话", m.get("summary"));
    }

    @Test
    @DisplayName("未答段：只有子问与 answered=false，无 sql/summary 键")
    void unansweredPartIsPlaceholder() {
        List<Map<String, Object>> out = McpServerService.partsDigest(
                List.of(SubAnswer.unanswered("销售额环比如何")));
        assertEquals(1, out.size());
        Map<String, Object> m = out.get(0);
        assertEquals(false, m.get("answered"));
        assertFalse(m.containsKey("sql"));
        assertFalse(m.containsKey("summary"));
    }

    @Test
    @DisplayName("混合多段保序：答/未答/无解读只有行数")
    void mixedPartsKeepOrder() {
        QueryResult r = QueryResult.of("SELECT 2", List.of("c"), List.of(), 0, 3);
        List<Map<String, Object>> out = McpServerService.partsDigest(List.of(
                new SubAnswer("问A", r, null, new Insight("A的结论", null, null), null, null, true),
                SubAnswer.unanswered("问B"),
                new SubAnswer("问C", r, null, null, null, null, true)));
        assertEquals(3, out.size());
        assertEquals("问A", out.get(0).get("subQuestion"));
        assertEquals("问B", out.get(1).get("subQuestion"));
        assertFalse(out.get(2).containsKey("summary"), "已答段无解读时不落 summary");
        assertEquals("A的结论", out.get(0).get("summary"));
    }

    @Test
    @DisplayName("anyMatch 语义锚：任一已答即真、全未答即假（summarize 分支直接用此式）")
    void answeredSemantics() {
        assertTrue(List.of(new SubAnswer("问", null, null, null, null, null, true))
                .stream().anyMatch(SubAnswer::answered));
        assertFalse(List.of(SubAnswer.unanswered("问"))
                .stream().anyMatch(SubAnswer::answered));
    }
}
