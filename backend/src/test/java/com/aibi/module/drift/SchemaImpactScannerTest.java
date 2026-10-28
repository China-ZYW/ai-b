package com.aibi.module.drift;

import com.aibi.module.drift.SchemaChangeDiff.Change;
import com.aibi.module.drift.SchemaChangeDiff.Kind;
import com.aibi.module.drift.SchemaImpactScanner.Asset;
import com.aibi.module.drift.SchemaImpactScanner.Impact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaImpactScanner} 纯函数单测（T12-3 K1）。
 */
class SchemaImpactScannerTest {

    private static final Change DROP_TABLE_ORDERS = new Change(Kind.TABLE_DROPPED, "orders", null);
    private static final Change DROP_COL_CITY = new Change(Kind.COLUMN_DROPPED, "orders", "city");

    private static Asset sq(String sql) {
        return new Asset("SAVED_QUERY", 1L, "模板", 7L, sql);
    }

    @Test
    @DisplayName("表级：SQL 引用被删表（含 JOIN/带库前缀/反引号）命中")
    void tableDropHits() {
        List<Impact> out = SchemaImpactScanner.scan(List.of(DROP_TABLE_ORDERS), List.of(
                sq("SELECT id FROM `mydb`.`orders` o JOIN users u ON u.id=o.uid"),
                sq("SELECT id FROM returns")));
        assertEquals(1, out.size());
        assertEquals(List.of("表 orders 已被删除"), out.get(0).brokenRefs());
    }

    @Test
    @DisplayName("列级：引用该表且 token 命中删除列；未引用该表则同名列不误报")
    void colDropScoping() {
        List<Impact> hit = SchemaImpactScanner.scan(List.of(DROP_COL_CITY), List.of(
                sq("SELECT city, SUM(amount) FROM orders GROUP BY city")));
        assertEquals(1, hit.size());
        assertEquals(List.of("表 orders 的列 city 已被删除"), hit.get(0).brokenRefs());

        // users 表也有 city 列被删的事件，但这条 SQL 只引用 orders → 不命中 users 名下
        List<Impact> miss = SchemaImpactScanner.scan(
                List.of(new Change(Kind.COLUMN_DROPPED, "users", "city")), List.of(
                        sq("SELECT city FROM orders")));
        assertTrue(miss.isEmpty(), "SQL 未引用 users，orders.city 不该被误判成 users.city");
    }

    @Test
    @DisplayName("列名 token 边界：前缀/后缀粘连不算命中（province_city、city_id）")
    void identifierBoundary() {
        List<Impact> out = SchemaImpactScanner.scan(List.of(DROP_COL_CITY), List.of(
                sq("SELECT province_city, city_id FROM orders")));
        assertTrue(out.isEmpty(), "子串命中不是标识符命中");
    }

    @Test
    @DisplayName("字符串字面量/注释里的同名不命中（掩码生效）")
    void literalsMasked() {
        List<Impact> out = SchemaImpactScanner.scan(List.of(DROP_TABLE_ORDERS, DROP_COL_CITY), List.of(
                sq("SELECT 1 FROM dual WHERE note = '从 orders 表取 city -- orders city'"),
                sq("-- 说明：orders 表 city 列已废弃\nSELECT 1 FROM dual")));
        assertTrue(out.isEmpty(), "掩码后字面量/注释不参与匹配");
    }

    @Test
    @DisplayName("整表删除时不重复报该表的删除列")
    void tableDropSuppressesColNoise() {
        List<Impact> out = SchemaImpactScanner.scan(
                List.of(DROP_TABLE_ORDERS, DROP_COL_CITY), List.of(
                        sq("SELECT city FROM orders")));
        assertEquals(1, out.size());
        assertEquals(List.of("表 orders 已被删除"), out.get(0).brokenRefs());
    }

    @Test
    @DisplayName("中文列名 token 匹配（前后中文不算粘连）")
    void hanIdentifier() {
        List<Impact> out = SchemaImpactScanner.scan(
                List.of(new Change(Kind.COLUMN_DROPPED, "订单", "销售额")), List.of(
                        sq("SELECT 省份, 销售额 FROM 订单")));
        assertEquals(1, out.size(), "JSqlParser 解析失败走正则兜底，中文表名可抽出");
        assertTrue(out.get(0).brokenRefs().get(0).contains("销售额"));
    }

    @Test
    @DisplayName("退化输入：空 drops/空资产/blank 文本/null 资产均不炸且零命中")
    void degenerateInputs() {
        assertTrue(SchemaImpactScanner.scan(List.of(), List.of(sq("SELECT 1 FROM orders"))).isEmpty());
        assertTrue(SchemaImpactScanner.scan(List.of(DROP_TABLE_ORDERS), List.of()).isEmpty());
        assertTrue(SchemaImpactScanner.scan(List.of(DROP_TABLE_ORDERS),
                Arrays.asList(sq("  "), null)).isEmpty());
        assertTrue(SchemaImpactScanner.scan(null, null).isEmpty());
    }

    @Test
    @DisplayName("多资产多明细：命中保序、明细聚合到一条")
    void multiAssetOrdering() {
        List<Impact> out = SchemaImpactScanner.scan(List.of(DROP_TABLE_ORDERS, DROP_COL_CITY), List.of(
                new Asset("METRIC", 9L, "销售额", 3L, "SELECT city FROM orders"),
                new Asset("SAVED_QUERY", 2L, "退货率", 8L, "SELECT id FROM orders"),
                new Asset("SAVED_QUERY", 3L, "无关", 8L, "SELECT 1 FROM users")));
        assertEquals(2, out.size());
        assertEquals("销售额", out.get(0).asset().name(), "按入参序");
        assertEquals(1, out.get(0).brokenRefs().size(), "整表已删时抑制列级明细，只报表级一条");
    }
}
