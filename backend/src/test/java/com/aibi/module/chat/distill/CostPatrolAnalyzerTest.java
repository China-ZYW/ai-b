package com.aibi.module.chat.distill;

import com.aibi.module.history.entity.QueryHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本巡检纯内核（T12-4）单测：不起 Spring、不连库——直接喂内存 QueryHistory 行集验证
 * 三分类边界、抑制规则（一簇一行）、行过滤与排序截断。
 */
class CostPatrolAnalyzerTest {

    private static final CostPatrolAnalyzer.Params P =
            new CostPatrolAnalyzer.Params(5000, 5, 3, 7, 20);

    private static QueryHistory ok(String question, long elapsedMs, String sql, String skill) {
        QueryHistory r = new QueryHistory();
        r.setQuestion(question);
        r.setStatus("OK");
        r.setElapsedMs(elapsedMs);
        r.setSqlText(sql);
        r.setSkill(skill);
        return r;
    }

    @Test
    @DisplayName("又慢又高频：升格物化候选且一簇只出一行（不重复产出慢/重复行）")
    void materializeEscalationSingleRow() {
        List<QueryHistory> rows = List.of(
                ok("近7天销售额", 6000, "SELECT 1", "nl2sql"),
                ok("近7天销售额", 8000, "SELECT 1", "nl2sql"),
                ok("近7天销售额", 7000, "SELECT 1", "nl2sql"));
        List<GovernanceIssue> out = CostPatrolAnalyzer.analyze(9L, rows, P);
        assertEquals(1, out.size());
        GovernanceIssue i = out.get(0);
        assertEquals(GovernanceSignal.COST_PATROL_MATERIALIZE, i.signal());
        assertEquals(3, i.occurrences());
        assertTrue(i.suggestedAction().contains("又慢又高频"));
        assertTrue(i.hasSuccessSql());
        assertEquals(9L, i.jumpAnchor().get("datasourceId"));
        // 均耗时 = (6000+8000+7000)/3 = 7000
        assertEquals(7000L, i.jumpAnchor().get("elapsedAvgMs"));
    }

    @Test
    @DisplayName("仅慢（次数不足物化门槛）：判慢查询")
    void slowOnly() {
        List<QueryHistory> rows = List.of(
                ok("各部门人数", 12000, "SELECT a", null),
                ok("各部门人数", 9000, "SELECT a", null));
        List<GovernanceIssue> out = CostPatrolAnalyzer.analyze(null, rows, P);
        assertEquals(1, out.size());
        assertEquals(GovernanceSignal.COST_PATROL_SLOW_SQL, out.get(0).signal());
        assertTrue(out.get(0).suggestedAction().contains("过滤"));
        // 无 ds 锚点时 jumpAnchor 只带耗时
        assertNull(out.get(0).jumpAnchor().get("datasourceId"));
    }

    @Test
    @DisplayName("仅高频（均耗时低于慢阈值）：判重复问法")
    void dupOnly() {
        List<QueryHistory> rows = List.of(
                ok("本月订单量", 300, "SELECT 1", "metric"),
                ok("本月订单量", 250, "SELECT 1", "metric"),
                ok("本月订单量", 280, "SELECT 1", "metric"),
                ok("本月订单量", 260, "SELECT 1", "metric"),
                ok("本月订单量", 270, "SELECT 1", "metric"));
        List<GovernanceIssue> out = CostPatrolAnalyzer.analyze(1L, rows, P);
        assertEquals(1, out.size());
        assertEquals(GovernanceSignal.COST_PATROL_DUP_QUERY, out.get(0).signal());
        assertEquals(5, out.get(0).occurrences());
    }

    @Test
    @DisplayName("既不快也不慢又没被反复问：不报（宁缺勿滥）")
    void belowAllThresholdsIgnored() {
        List<QueryHistory> rows = List.of(
                ok("今天销售额", 2000, "SELECT 1", "metric"),
                ok("今天销售额", 2200, "SELECT 1", "metric"));
        assertTrue(CostPatrolAnalyzer.analyze(1L, rows, P).isEmpty());
    }

    @Test
    @DisplayName("行过滤：失败行、耗时缺失/为负、归一键为空的行不参与聚簇")
    void filtersBadRows() {
        QueryHistory failed = ok("慢问法", 99999, "SELECT x", "nl2sql");
        failed.setStatus("FAILED");
        QueryHistory noElapsed = ok("慢问法", 0, "SELECT x", "nl2sql");
        noElapsed.setElapsedMs(null);
        QueryHistory negative = ok("慢问法", -5, "SELECT x", "nl2sql");
        QueryHistory blankQ = ok("   ", 9999, "SELECT x", "nl2sql");
        List<QueryHistory> rows = List.of(failed, noElapsed, negative, blankQ,
                ok("慢问法", 6000, "SELECT x", "nl2sql"));
        List<GovernanceIssue> out = CostPatrolAnalyzer.analyze(1L, rows, P);
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).occurrences());
        // noElapsed 的 0 也合法（>=0），验证它没混进来：occ==1 已证；均耗时即单行 6000
        assertEquals(6000L, out.get(0).jumpAnchor().get("elapsedAvgMs"));
    }

    @Test
    @DisplayName("同簇代表选择：问句取最长、SQL 取最长 OK 行、技能取簇首（最新）行")
    void representativeSelection() {
        // 设计稿 §9 同款归一对：两条均归一键为"销售额"，必同簇
        List<QueryHistory> rows = List.of(
                ok("近7天销售额", 6000, "SELECT 1", "metric"),
                ok("最近一个月销售额", 6000, "SELECT a FROM t WHERE d >= x", "nl2sql"));
        List<GovernanceIssue> out = CostPatrolAnalyzer.analyze(1L, rows, P);
        assertEquals(1, out.size());
        GovernanceIssue i = out.get(0);
        assertEquals("最近一个月销售额", i.question());               // 簇内最长问句
        assertEquals("SELECT a FROM t WHERE d >= x", i.sampleSql()); // 簇内最长 SQL
        assertEquals("metric", i.skill());                            // 簇首行（created_at DESC 首行=最新）
        assertEquals(2, i.occurrences());
        assertEquals(GovernanceSignal.COST_PATROL_SLOW_SQL, i.signal()); // 2 次 < 物化门槛 3
    }

    @Test
    @DisplayName("排序与截断：信号优先级升（MATERIALIZE>SLOW>DUP），同信号次数降，topN 截断")
    void orderingAndCap() {
        List<QueryHistory> rows = List.of(
                // DUP 簇：6 次快问
                ok("重复问法一", 100, "S", "metric"), ok("重复问法一", 100, "S", "metric"),
                ok("重复问法一", 100, "S", "metric"), ok("重复问法一", 100, "S", "metric"),
                ok("重复问法一", 100, "S", "metric"), ok("重复问法一", 100, "S", "metric"),
                // SLOW 簇：2 次慢
                ok("慢问法二", 9000, "S", "nl2sql"), ok("慢问法二", 7000, "S", "nl2sql"),
                // MATERIALIZE 簇：3 次慢
                ok("又慢又频三", 6000, "S", "nl2sql"), ok("又慢又频三", 6000, "S", "nl2sql"),
                ok("又慢又频三", 6000, "S", "nl2sql"));
        List<GovernanceIssue> out = CostPatrolAnalyzer.analyze(1L, rows, P);
        assertEquals(3, out.size());
        assertEquals(GovernanceSignal.COST_PATROL_MATERIALIZE, out.get(0).signal());
        assertEquals(GovernanceSignal.COST_PATROL_SLOW_SQL, out.get(1).signal());
        assertEquals(GovernanceSignal.COST_PATROL_DUP_QUERY, out.get(2).signal());

        List<GovernanceIssue> capped = CostPatrolAnalyzer.analyze(1L, rows,
                new CostPatrolAnalyzer.Params(5000, 5, 3, 7, 2));
        assertEquals(2, capped.size());
        assertFalse(capped.stream().anyMatch(i -> i.signal() == GovernanceSignal.COST_PATROL_DUP_QUERY));
    }

    @Test
    @DisplayName("退化输入：rows 为 null/空、params 为 null 均返回空表")
    void degenerateInputs() {
        assertTrue(CostPatrolAnalyzer.analyze(1L, null, P).isEmpty());
        assertTrue(CostPatrolAnalyzer.analyze(1L, List.of(), P).isEmpty());
        assertTrue(CostPatrolAnalyzer.analyze(1L, List.of(ok("x", 1, "S", null)), null).isEmpty());
    }
}
