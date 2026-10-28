package com.aibi.common.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlTableExtractor} 纯逻辑单测（T10-3 K1）。
 *
 * <p>该工具自 {@code DataMaskGate} 原样下沉，同时服务表级授权拦截（安全链路）与溯源回传（展示链路），
 * 本测试即其"行为逐字节等价"护栏：样例覆盖 JSqlParser 主路径、中文表名正则兜底、
 * 字符串字面量剥离与裸名归一化各形态。
 */
class SqlTableExtractorTest {

    @Test
    @DisplayName("① 单表：FROM 后表名抽取")
    void singleTable() {
        Set<String> t = SqlTableExtractor.extractTables("SELECT id, amt FROM orders WHERE amt > 100");
        assertTrue(t.contains("orders"), t.toString());
    }

    @Test
    @DisplayName("② 多表 JOIN：主表与所有被连接表都在集合里")
    void multiJoin() {
        Set<String> t = SqlTableExtractor.extractTables(
                "SELECT a.id, b.name FROM sales a LEFT JOIN dim_city b ON a.city_id = b.id "
                        + "JOIN dim_region c ON b.region_id = c.id");
        assertTrue(t.contains("sales"), t.toString());
        assertTrue(t.contains("dim_city"), t.toString());
        assertTrue(t.contains("dim_region"), t.toString());
    }

    @Test
    @DisplayName("③ 子查询：内外层表均抽取（JSqlParser 主路径）")
    void subQuery() {
        Set<String> t = SqlTableExtractor.extractTables(
                "SELECT x.s FROM (SELECT SUM(amt) s FROM t1) x JOIN t2 ON 1 = 1");
        assertTrue(t.contains("t1"), t.toString());
        assertTrue(t.contains("t2"), t.toString());
    }

    @Test
    @DisplayName("④ 中文未引号表名：JSqlParser 解析失败 → 正则兜底命中")
    void chineseTableNameFallsBackToRegex() {
        Set<String> t = SqlTableExtractor.extractTables("SELECT 地区, SUM(销售额) FROM 销售明细 WHERE 月份 = '2026-09'");
        assertTrue(t.contains("销售明细"), t.toString());
    }

    @Test
    @DisplayName("⑤ 字符串字面量与注释内的 FROM/JOIN 不得误报为表")
    void literalsAndCommentsExcluded() {
        Set<String> t = SqlTableExtractor.extractTables(
                "SELECT note FROM real_tbl WHERE note = 'FROM ghost' /* JOIN phantom */ -- JOIN comment_tbl\n");
        assertTrue(t.contains("real_tbl"), t.toString());
        assertFalse(t.contains("ghost"), t.toString());
        assertFalse(t.contains("phantom"), t.toString());
        assertFalse(t.contains("comment_tbl"), t.toString());
    }

    @Test
    @DisplayName("⑥ 裸名归一：去反引号/双引号/方括号、取末段、转小写")
    void bareNameNormalizes() {
        assertEquals("orders", SqlTableExtractor.bareName("`db`.`Orders`"));
        assertEquals("t", SqlTableExtractor.bareName("[dbo].[t]"));
        assertEquals("sales", SqlTableExtractor.bareName("db1.schema.sales"));
        assertEquals("销售明细", SqlTableExtractor.bareName("\"销售明细\""));
        assertEquals("", SqlTableExtractor.bareName(null));
    }

    @Test
    @DisplayName("⑦ 带库前缀全名与裸名经 bareName 后相等（富化匹配两侧一致）")
    void prefixedAndBareMatchSameKey() {
        Set<String> t = SqlTableExtractor.extractTables("SELECT 1 FROM db1.sales LIMIT 10");
        // 抽取集合里无论含 db1.sales 还是 sales，归一化后都须落到同一裸名
        assertTrue(t.stream().allMatch(x -> "sales".equals(SqlTableExtractor.bareName(x))), t.toString());
    }
}
