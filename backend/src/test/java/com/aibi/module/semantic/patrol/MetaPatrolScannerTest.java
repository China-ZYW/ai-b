package com.aibi.module.semantic.patrol;

import com.aibi.module.chat.distill.GovernanceIssue;
import com.aibi.module.chat.distill.GovernanceSignal;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.semantic.entity.SemanticMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据巡检扫描器（T11-4）纯逻辑单测：不起 Spring、不连库，直接喂内存构造的语义行/指标/schema 验证
 * 三类信号判定、normalizedKey 规范、按表聚簇与 cap 截断、解析失败跳过等边界（对应设计稿 §7.1）。
 */
class MetaPatrolScannerTest {

    // ------------------------------------------------------------------
    // 构造小工具
    // ------------------------------------------------------------------

    private static SemanticMeta col(Long ds, String table, String column, String alias, String desc) {
        return row(ds, "COLUMN", table, column, alias, desc, 1);
    }

    private static SemanticMeta tbl(Long ds, String table, String alias) {
        return row(ds, "TABLE", table, null, alias, null, 1);
    }

    private static SemanticMeta row(Long ds, String type, String table, String column,
                                   String alias, String desc, int enabled) {
        SemanticMeta m = new SemanticMeta();
        m.setDatasourceId(ds);
        m.setObjectType(type);
        m.setTableName(table);
        m.setColumnName(column);
        m.setAliasCn(alias);
        m.setDescription(desc);
        m.setEnabled(enabled);
        return m;
    }

    private static Metric metric(String name, String definition, String sqlText) {
        Metric mt = new Metric();
        mt.setName(name);
        mt.setDefinition(definition);
        mt.setSqlText(sqlText);
        mt.setDatasourceId(9L);
        return mt;
    }

    /** 小写表名 → 小写列集。 */
    private static Map<String, Set<String>> schema(Object... tableThenColsPairs) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < tableThenColsPairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) tableThenColsPairs[i + 1];
            map.put(((String) tableThenColsPairs[i]).toLowerCase(), new LinkedHashSet<>(cols));
        }
        return map;
    }

    private static GovernanceIssue byKey(List<GovernanceIssue> issues, String keyPrefix) {
        return issues.stream().filter(i -> i.normalizedKey().startsWith(keyPrefix)).findFirst().orElse(null);
    }

    private static long count(List<GovernanceIssue> issues, GovernanceSignal signal) {
        return issues.stream().filter(i -> i.signal() == signal).count();
    }

    // ------------------------------------------------------------------
    // S-M1 血缘断裂
    // ------------------------------------------------------------------

    @Test
    @DisplayName("幽灵表行：enabled 行引用的表不在 schema → BROKEN，同表多行只出一条")
    void ghostTable() {
        Map<String, Set<String>> sc = schema("orders", List.of("id", "amount"));
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(
                tbl(9L, "ghost_tbl", "幽灵表"),
                col(9L, "ghost_tbl", "a", "甲", "说明"),   // 同表第二行，不应重复出
                col(9L, "orders", "id", "订单号", null)), null, sc);

        GovernanceIssue g = byKey(out, "BROKEN|9|ghost_tbl");
        assertNotNull(g, "幽灵表应命中");
        assertEquals(GovernanceSignal.META_LINEAGE_BROKEN, g.signal());
        assertEquals(1, count(out, GovernanceSignal.META_LINEAGE_BROKEN), "同表两行只出一条");
        assertEquals("meta_patrol", g.skill());
        assertTrue(g.question().contains("ghost_tbl"));
    }

    @Test
    @DisplayName("幽灵列：表在但列不在 → BROKEN|ds|table.column；列集为空只判表存在性防误杀")
    void ghostColumn() {
        Map<String, Set<String>> sc = schema("orders", List.of("id"), "empty_tbl", List.of());
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(
                col(9L, "orders", "gone_col", "消失列", "有注释"),     // 列缺失→命中
                col(9L, "orders", "id", "订单号", null),               // 列在→放行
                col(9L, "empty_tbl", "whatever", "任意列", "说明")),   // 列集空→不判列，放行
                null, sc);

        assertNotNull(byKey(out, "BROKEN|9|orders.gone_col"), "缺失列应命中");
        assertNull(byKey(out, "BROKEN|9|orders.id"));
        assertNull(byKey(out, "BROKEN|9|empty_tbl.whatever"), "拿不到列信息不应误杀");
        assertEquals(1, count(out, GovernanceSignal.META_LINEAGE_BROKEN));
    }

    @Test
    @DisplayName("disabled 行不参与血缘判定")
    void disabledRowIgnored() {
        Map<String, Set<String>> sc = schema("orders", List.of("id"));
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L,
                List.of(row(9L, "COLUMN", "ghost", "x", "甲", "说明", 0)), null, sc);
        assertTrue(out.isEmpty(), "未启用行不巡检");
    }

    @Test
    @DisplayName("指标断链：sqlText 引用表不在 schema → BROKEN；正常 SQL 放行")
    void metricBrokenLineage() {
        Map<String, Set<String>> sc = schema("orders", List.of("id", "amount"));
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(), List.of(
                metric("销售额", "SUM(amount)", "SELECT SUM(amount) FROM orders"),           // 正常
                metric("流失率", "COUNT(*)", "SELECT COUNT(*) FROM churn WHERE dt='2026'")),  // 断链
                sc);

        GovernanceIssue g = byKey(out, "BROKEN|9|churn");
        assertNotNull(g, "指标引用缺失表应命中");
        assertTrue(g.question().contains("流失率"));
        assertNull(byKey(out, "BROKEN|9|orders"));
    }

    @Test
    @DisplayName("指标 sqlText 为空/解析不出表 → 该条跳过不报错（Glossary 纯文本等）")
    void metricSqlUnparseableSkipped() {
        Map<String, Set<String>> sc = schema("orders", List.of("id"));
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(), List.of(
                metric("无SQL指标", "SUM(x)", null),
                metric("怪SQL", null, "SELCT ??? FROM !@@ WHERE")), sc);
        // 兜底正则可能提出奇表名也可能什么都不提出——只断言不抛异常且 orders 不被误报
        assertNull(byKey(out, "BROKEN|9|orders"), "存在的表绝不因解析失败被误报");
    }

    // ------------------------------------------------------------------
    // S-M2 同名不同口径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("别名冲突：同 aliasCn 映射两个不同物理列 → DIFFDEF|ds|ALIAS|名；单列/非中文别名放行")
    void aliasConflict() {
        Map<String, Set<String>> sc = schema("orders", List.of("amount", "id"), "users", List.of("id"));
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(
                col(9L, "orders", "amount", "销售额", "订单表金额"),
                col(9L, "users", "id", "销售额", "用户表id"),        // 同名不同列→冲突
                col(9L, "orders", "id", "唯一叫法", "说明"),          // 单映射→放行
                col(9L, "users", "id2", "raw_id", null)),           // 非中文别名→不参与
                List.of(), sc);

        GovernanceIssue g = byKey(out, "DIFFDEF|9|ALIAS|销售额");
        assertNotNull(g, "同别名双映射应命中");
        assertEquals(GovernanceSignal.META_SAME_NAME_DIFF_DEF, g.signal());
        assertEquals(2, g.occurrences(), "冲突映射数即 occurrences");
        assertTrue(g.suggestedAction().contains("orders.amount") && g.suggestedAction().contains("users.id"),
                "建议里应列出冲突明细");
        assertNull(byKey(out, "DIFFDEF|9|ALIAS|唯一叫法"));
    }

    @Test
    @DisplayName("指标同名异口径 → DIFFDEF|ds|METRIC|名；口径完全相同=重复资产不判")
    void metricSameNameDiffDef() {
        Map<String, Set<String>> sc = schema("orders", List.of("amount"));
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(), List.of(
                metric("活跃用户数", "COUNT(DISTINCT uid)", "SELECT 1 FROM orders"),
                metric("活跃用户数", "SUM(login)", "SELECT 2 FROM orders"),        // 异口径→冲突
                metric("下单量", "COUNT(*)", "SELECT 3 FROM orders"),
                metric("下单量", "COUNT(*)", "SELECT 3 FROM orders")), sc);       // 同口径=重复，不判

        GovernanceIssue g = byKey(out, "DIFFDEF|9|METRIC|活跃用户数");
        assertNotNull(g, "同名异口径指标应命中");
        assertNull(byKey(out, "DIFFDEF|9|METRIC|下单量"), "口径相同的重复资产不属本信号");
    }

    // ------------------------------------------------------------------
    // S-M3 注释缺失
    // ------------------------------------------------------------------

    @Test
    @DisplayName("裸列按表聚簇：一表一条、occurrences=裸列数；有中文别名或有注释均放行")
    void noCommentClusterPerTable() {
        Map<String, Set<String>> sc = schema("orders", List.of("a", "b", "c", "d"));
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(
                col(9L, "orders", "a", null, null),          // 裸列
                col(9L, "orders", "b", "", "  "),            // 空别名+空注释=裸列
                col(9L, "orders", "c", "金额", null),         // 有中文别名→放行
                col(9L, "orders", "d", "raw", "订单明细号"),   // 无中文别名但有注释→放行
                col(9L, "orders", "a", "raw2", null)),       // 同列重复行→再一裸列
                List.of(), sc);

        GovernanceIssue g = byKey(out, "NOCOM|9|orders");
        assertNotNull(g, "裸列表应命中");
        assertEquals(GovernanceSignal.META_NO_COMMENT, g.signal());
        assertEquals(3, g.occurrences(), "a、b、a 重复行共 3 个裸列");
        assertTrue(g.question().contains("3/5"), "占比=裸列/该表巡检列总数，实际：" + g.question());
        assertEquals(1, count(out, GovernanceSignal.META_NO_COMMENT), "一表只出一条");
    }

    @Test
    @DisplayName("cap 截断：21 张裸列表只报裸列数最多的前 20 张")
    void noCommentCap() {
        List<SemanticMeta> metas = new ArrayList<>();
        Map<String, Set<String>> sc = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) {
            String t = "t" + String.format("%02d", i);
            sc.put(t, new LinkedHashSet<>(List.of("c1", "c2", "c3")));
            // t00 四列全裸（最多），其余三列裸——排序后 t00 必在，t20 应被挤出还是看裸列数=3 并列按名称升序 reversed
            for (int c = 0; c < (i == 0 ? 4 : 3); c++) {
                metas.add(col(9L, t, "c" + c, null, null));
            }
        }
        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, metas, List.of(), sc);
        assertEquals(MetaPatrolScanner.NO_COMMENT_CAP, count(out, GovernanceSignal.META_NO_COMMENT),
                "NO_COMMENT 应按 cap 20 截断");
        assertNotNull(byKey(out, "NOCOM|9|t00"), "裸列最多的表必在报出之列");
    }

    // ------------------------------------------------------------------
    // 通用口径与排序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("巡检行统一口径：skill=meta_patrol、无 SQL 样本、status=OPEN、未转写；空输入空输出")
    void issueCommonShapeAndEmptyInput() {
        List<GovernanceIssue> empty = MetaPatrolScanner.scan(9L, List.of(), List.of(),
                schema("orders", List.of("id")));
        assertTrue(empty.isEmpty(), "无缺陷返回空表");
        assertTrue(MetaPatrolScanner.scan(1L, null, null, null).isEmpty(), "null 输入容错");

        List<GovernanceIssue> out = MetaPatrolScanner.scan(9L, List.of(col(9L, "gone", "x", "甲", "说明")),
                List.of(), Map.of());
        GovernanceIssue g = out.get(0);
        assertEquals("meta_patrol", g.skill());
        assertNull(g.sampleSql());
        assertTrue(!g.hasSuccessSql());
        assertEquals("OPEN", g.status());
        assertNull(g.ledgerId());
        assertEquals(9L, g.jumpAnchor().get("datasourceId"));
    }

    @Test
    @DisplayName("T11-4 三信号追加枚举尾部：整体优先级排既有 5 值之后，内部 BROKEN>DIFFDEF>NO_COMMENT")
    void signalOrdering() {
        GovernanceSignal[] vals = GovernanceSignal.values();
        assertEquals(12, vals.length, "5 问数 + 3 巡检 + 1 结构变更（T12-3）+ 3 成本巡检（T12-4）");
        assertEquals(GovernanceSignal.META_LINEAGE_BROKEN, vals[5]);
        assertEquals(GovernanceSignal.META_SAME_NAME_DIFF_DEF, vals[6]);
        assertEquals(GovernanceSignal.META_NO_COMMENT, vals[7]);
        assertEquals(GovernanceSignal.META_SCHEMA_CHANGED, vals[8]);
        // T12-4：成本建议排一切问题信号之后，内部声明序 MATERIALIZE > SLOW > DUP
        assertEquals(GovernanceSignal.COST_PATROL_MATERIALIZE, vals[9]);
        assertEquals(GovernanceSignal.COST_PATROL_SLOW_SQL, vals[10]);
        assertEquals(GovernanceSignal.COST_PATROL_DUP_QUERY, vals[11]);
        assertTrue(GovernanceSignal.META_SCHEMA_CHANGED.priority() < GovernanceSignal.COST_PATROL_MATERIALIZE.priority(),
                "成本巡检信号整体排 META_* 之后");
        assertTrue(GovernanceSignal.AMBIGUOUS.priority() < GovernanceSignal.META_LINEAGE_BROKEN.priority(),
                "巡检信号整体排问数信号之后");
        assertEquals("血缘断裂", GovernanceSignal.META_LINEAGE_BROKEN.label());
        assertEquals("同名不同口径", GovernanceSignal.META_SAME_NAME_DIFF_DEF.label());
        assertEquals("注释缺失", GovernanceSignal.META_NO_COMMENT.label());
    }
}
