package com.aibi.module.datasource.cost;

import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.model.DataSourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代价预估熔断（T10-1）纯逻辑单测：不起 Spring、不连库——{@code AibiConfig} 以 Mockito 桩默认阈值，
 * {@code CostStatsProvider} 以内存 Map 桩注入表行数，直接喂 JSqlParser 能解析的真实 SQL 验证判定表边界
 * （对应设计稿 §10 用例 1~8）。
 */
class CostGuardTest {

    // 与 AibiConfig 默认值一致的阈值桩
    private static final long SCAN = 50_000_000L;   // REJECT
    private static final long WARN = 10_000_000L;   // WARN 下限
    private static final long BIG = 5_000_000L;     // 大表界定

    /** 行数桩：表名（小写）→ 估算行数；缺省返回 UNKNOWN。 */
    private static CostStatsProvider stats(Map<String, Long> rowsByTable) {
        return (config, table) -> {
            String t = table == null ? "" : table.toLowerCase();
            int dot = t.lastIndexOf('.');
            if (dot >= 0) {
                t = t.substring(dot + 1);
            }
            return rowsByTable.getOrDefault(t, CostVerdict.UNKNOWN_ROWS);
        };
    }

    /** 装配一个用默认阈值的 guard。 */
    private static HeuristicCostGuard guard(boolean costGuard, boolean costBlock, Map<String, Long> rows) {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.costGuardEnabled()).thenReturn(costGuard);
        when(cfg.costBlockEnabled()).thenReturn(costBlock);
        when(cfg.costScanRowsThreshold()).thenReturn((int) SCAN);
        when(cfg.costWarnRowsThreshold()).thenReturn((int) WARN);
        when(cfg.costBigTableRows()).thenReturn((int) BIG);
        when(cfg.costSelectivity()).thenReturn(10);
        return new HeuristicCostGuard(cfg, stats(rows));
    }

    private static DataSourceConfig ds() {
        return new DataSourceConfig(1L, 1L, "test", DataSourceType.MYSQL,
                "localhost", 3306, "db", "u", "p", null, null, null, false, null);
    }

    private static Map<String, Long> map(Object... kv) {
        Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    @Test
    @DisplayName("①单表无 WHERE + 大表(1e8) → REJECT 全表扫描")
    void fullScanBigTableRejected() {
        HeuristicCostGuard g = guard(true, true, map("orders", 100_000_000L));
        CostVerdict v = g.assess(ds(), "SELECT * FROM orders");
        assertTrue(v.blocked(), "无过滤扫 1e8 行大表应拒绝");
        assertEquals(CostVerdict.Level.REJECT, v.level());
        assertTrue(v.advice().contains("全表扫描"), "建议文案应点明全表扫描，实际：" + v.advice());
    }

    @Test
    @DisplayName("②单表有 WHERE + 中小表(1e6)→ 折扣后 1e5 低于 warn → PASS")
    void filteredSmallTablePass() {
        HeuristicCostGuard g = guard(true, true, map("orders", 1_000_000L));
        CostVerdict v = g.assess(ds(), "SELECT * FROM orders WHERE status = 'paid'");
        assertEquals(CostVerdict.Level.PASS, v.level());
        assertFalse(v.blocked());
    }

    @Test
    @DisplayName("③多表等值 JOIN(有 ON) 各表百万级 → 取 max 近似 → PASS")
    void equiJoinUnderThreshold() {
        HeuristicCostGuard g = guard(true, true, map("orders", 1_000_000L, "users", 500_000L));
        CostVerdict v = g.assess(ds(),
                "SELECT * FROM orders o JOIN users u ON o.user_id = u.id WHERE o.amount > 100");
        assertFalse(v.blocked(), "有连接键的等值 JOIN 不应判笛卡尔");
        assertEquals(CostVerdict.Level.PASS, v.level());
    }

    @Test
    @DisplayName("④逗号连接/CROSS/无 ON 两表 → REJECT 笛卡尔积")
    void cartesianRejected() {
        HeuristicCostGuard g = guard(true, true, map("a", 10_000L, "b", 10_000L));
        CostVerdict v = g.assess(ds(), "SELECT * FROM a, b");
        assertTrue(v.blocked(), "两表无连接键应判笛卡尔");
        assertTrue(v.advice().contains("笛卡尔"), "建议文案应含笛卡尔积，实际：" + v.advice());
    }

    @Test
    @DisplayName("④′ 老式逗号连接但 WHERE 有等值连接 → 不误判笛卡尔")
    void commaJoinLinkedInWhereNotCartesian() {
        HeuristicCostGuard g = guard(true, true, map("orders", 1_000_000L, "users", 1_000_000L));
        CostVerdict v = g.assess(ds(),
                "SELECT * FROM orders, users WHERE orders.user_id = users.id");
        assertFalse(v.blocked(), "WHERE 里已建立跨表等值连接，不应判笛卡尔");
    }

    @Test
    @DisplayName("⑤预估落在 (warn, reject] 告警带 → WARN 不阻断")
    void warnBand() {
        // 单表 2e8，有 WHERE → est=2e8*0.1=2e7，落在 (1e7,5e7]
        HeuristicCostGuard g = guard(true, true, map("orders", 200_000_000L));
        CostVerdict v = g.assess(ds(), "SELECT * FROM orders WHERE year = 2026");
        assertEquals(CostVerdict.Level.WARN, v.level(), "应落在告警带");
        assertFalse(v.blocked());
    }

    @Test
    @DisplayName("⑥行数 UNKNOWN 且无 WHERE → 保守 WARN（不误伤为 REJECT）")
    void unknownRowsNoFilterWarnNotReject() {
        HeuristicCostGuard g = guard(true, true, map());   // orders 不在桩里 → UNKNOWN
        CostVerdict v = g.assess(ds(), "SELECT * FROM orders");
        assertEquals(CostVerdict.Level.WARN, v.level(), "行数未知不拒绝，最多告警");
        assertFalse(v.blocked());
    }

    @Test
    @DisplayName("⑥′ 行数 UNKNOWN 但有 WHERE → PASS")
    void unknownRowsWithFilterPass() {
        HeuristicCostGuard g = guard(true, true, map());
        CostVerdict v = g.assess(ds(), "SELECT * FROM orders WHERE id = 1");
        assertEquals(CostVerdict.Level.PASS, v.level());
    }

    @Test
    @DisplayName("⑦解析失败/非 PlainSelect(UNION) → PASS（fail-open）")
    void unionFailOpen() {
        HeuristicCostGuard g = guard(true, true, map("t1", 100_000_000L, "t2", 100_000_000L));
        CostVerdict v = g.assess(ds(), "SELECT id FROM t1 UNION SELECT id FROM t2");
        assertEquals(CostVerdict.Level.PASS, v.level(), "UNION 非 PlainSelect，保守放行");
    }

    @Test
    @DisplayName("⑦′ 中文标识符 → PASS（JSqlParser 4.6 解析不了，绝不臆断）")
    void nonAsciiFailOpen() {
        HeuristicCostGuard g = guard(true, true, map("订单", 100_000_000L));
        CostVerdict v = g.assess(ds(), "SELECT * FROM 订单");
        assertEquals(CostVerdict.Level.PASS, v.level(), "含非 ASCII 标识符直接放行");
    }

    @Test
    @DisplayName("⑦″ FROM 是子查询（无基表可估）→ PASS")
    void subqueryFromPass() {
        HeuristicCostGuard g = guard(true, true, map());
        CostVerdict v = g.assess(ds(), "SELECT x FROM (SELECT 1 AS x) t");
        assertEquals(CostVerdict.Level.PASS, v.level(), "FROM 派生表无法估行数，放行");
    }

    @Test
    @DisplayName("⑧总开关 cost-guard=false → 恒 PASS；cost-block=false → REJECT 降为 WARN")
    void switches() {
        // 总开关关闭：本会 REJECT 的大表全扫描也放行
        CostVerdict off = guard(false, true, map("orders", 100_000_000L))
                .assess(ds(), "SELECT * FROM orders");
        assertEquals(CostVerdict.Level.PASS, off.level(), "总开关关闭必须逐字节等价放行");

        // 阻断开关关闭：该 REJECT 的降为 WARN，不阻断
        CostVerdict noBlock = guard(true, false, map("orders", 100_000_000L))
                .assess(ds(), "SELECT * FROM orders");
        assertEquals(CostVerdict.Level.WARN, noBlock.level(), "cost-block=false 时高危降告警");
        assertFalse(noBlock.blocked());
    }
}
