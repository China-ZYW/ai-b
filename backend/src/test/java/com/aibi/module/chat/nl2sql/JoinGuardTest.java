package com.aibi.module.chat.nl2sql;

import com.aibi.module.datasource.model.TableMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JoinGuard} 单测（T3 基线，纯静态逻辑、不依赖 Spring/MySQL）。
 *
 * <p>核心是文档承诺的「<b>永不抛出、宁缺毋滥</b>」契约：六类无法确凿判定的情形
 * （解析失败 / UNION / 非 ASCII / FROM 子查询 / 图谱无这两张表的键 / 单表）必须返回 {@code null} 放行；
 * 同时三条确凿规则（笛卡尔积、键与图谱矛盾、双主键 id 互连）必须报出修正意见。
 */
class JoinGuardTest {

    /** 两张英文表：orders(id, user_id, amount) / users(id, balance)，主键均为 id。 */
    private static final List<TableMeta> T2 = List.of(
            new TableMeta("orders", null, List.of(), List.of("id"), List.of()),
            new TableMeta("users", null, List.of(), List.of("id"), List.of()));

    /** 图谱：orders.user_id → users.id（外键来源）。 */
    private static final List<TableRelation> GRAPH = List.of(
            new TableRelation("orders", "user_id", "users", "id", TableRelation.Source.FOREIGN_KEY));

    // ==================== 放行：图谱正确键与 WHERE 老式连接 ====================

    @Test
    void graphKnownKeyPasses() {
        assertNull(JoinGuard.inspect(
                "SELECT * FROM orders o JOIN users u ON o.user_id = u.id", T2, GRAPH));
    }

    @Test
    void commaJoinLinkedByWherePasses() {
        // 老式逗号连接但 WHERE 里有跨表等值 → 不算笛卡尔积
        assertNull(JoinGuard.inspect(
                "SELECT * FROM orders, users WHERE orders.user_id = users.id", T2, GRAPH));
    }

    @Test
    void orConditionNotMisjudgedAsCartesian() {
        // ON (a.x=b.y OR a.m=b.n)：OR 里的等值也算"已连接"，且图谱无这对键 → 放行
        assertNull(JoinGuard.inspect(
                "SELECT * FROM orders JOIN users ON orders.x = users.y OR orders.a = users.b",
                T2, List.of()));
    }

    // ==================== 放行：六类"无法判定"契约 ====================

    @Test
    void unparsableSqlNeverThrows() {
        assertDoesNotThrow(() -> assertNull(JoinGuard.inspect(
                "SELECT * FROM orders JOIN ((( nonsense", T2, GRAPH)));
    }

    @Test
    void unionPasses() {
        assertNull(JoinGuard.inspect(
                "SELECT id FROM orders UNION SELECT id FROM users", T2, GRAPH));
    }

    @Test
    void nonAsciiSqlPasses() {
        // 中文表名折叠会撞车（订单/用户都变 nn）→ 必须直接跳过而非误报
        assertNull(JoinGuard.inspect(
                "SELECT * FROM 订单 JOIN 用户 ON 订单.用户id = 用户.id", T2, List.of()));
    }

    @Test
    void fromSubqueryPasses() {
        assertNull(JoinGuard.inspect(
                "SELECT * FROM (SELECT id FROM orders) o JOIN users ON o.id = users.id", T2, GRAPH));
    }

    @Test
    void relationAbsentInGraphPasses() {
        // 图谱里根本没有这两张表的键（图谱可能不全）→ 用的键再奇怪也放行
        assertNull(JoinGuard.inspect(
                "SELECT * FROM orders JOIN users ON orders.uuid = users.uuid", T2, List.of()));
    }

    @Test
    void singleTablePasses() {
        assertNull(JoinGuard.inspect("SELECT * FROM orders",
                List.of(T2.get(0)), GRAPH));
    }

    // ==================== 拦截：三条确凿规则 ====================

    @Test
    void commaWithoutAnyLinkReportsCartesian() {
        String hint = JoinGuard.inspect("SELECT * FROM orders, users", T2, GRAPH);
        assertTrue(hint != null && hint.contains("笛卡尔积"), "逗号连接无任何等值应报笛卡尔积");
    }

    @Test
    void crossJoinReportsCartesian() {
        String hint = JoinGuard.inspect("SELECT * FROM orders CROSS JOIN users", T2, GRAPH);
        assertTrue(hint != null && hint.contains("笛卡尔积"));
    }

    @Test
    void keyContradictingGraphReports() {
        String hint = JoinGuard.inspect(
                "SELECT * FROM orders JOIN users ON orders.amount = users.balance", T2, GRAPH);
        assertTrue(hint != null && hint.contains("不是这两张表之间的可用连接键")
                && hint.contains("orders.user_id = users.id"),
                "应指出用错了键并给出图谱里的正确键，实际: " + hint);
    }

    @Test
    void pkToPkJoinReportedEvenWithoutGraph() {
        // 规则③与图谱无关：两表用各自主键 id 互连是硬错误
        String hint = JoinGuard.inspect(
                "SELECT * FROM orders JOIN users ON orders.id = users.id", T2, List.of());
        assertTrue(hint != null && hint.contains("主键互连"), "实际: " + hint);
    }

    @Test
    void sameBadKeyReportedOnlyOnce() {
        String hint = JoinGuard.inspect(
                "SELECT * FROM orders JOIN users ON orders.amount = users.balance"
                        + " WHERE orders.amount = users.balance", T2, GRAPH);
        assertTrue(hint != null);
        assertTrue(hint.indexOf("不是这两张表之间的可用连接键")
                        == hint.lastIndexOf("不是这两张表之间的可用连接键"),
                "同一对键在 ON 与 WHERE 重复出现只应报一次，实际: " + hint);
    }
}
