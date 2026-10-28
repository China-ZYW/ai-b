package com.aibi.module.chat.skill.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DrillDownPlanner} 纯逻辑单测（T12-2）：零 LLM、零库，输入只有问句/上轮 SQL/候选列。
 */
class DrillDownPlannerTest {

    /** 英文列 + 中文语义层标签（典型真实库形态：列叫 province，别名登记"省份"）。 */
    private static final List<DrillDownPlanner.Dim> DIMS = List.of(
            new DrillDownPlanner.Dim("id", "主键"),
            new DrillDownPlanner.Dim("city", "城市"),
            new DrillDownPlanner.Dim("province", "省份"),
            new DrillDownPlanner.Dim("channel", "渠道"),
            new DrillDownPlanner.Dim("category", "品类"),
            new DrillDownPlanner.Dim("amount", "销售额"),
            new DrillDownPlanner.Dim("create_time", "创建时间"));

    private static final List<String> COLS =
            List.of("id", "city", "province", "channel", "category", "amount", "create_time");

    private static final String TOP10_SQL =
            "SELECT city, SUM(amount) AS total FROM orders GROUP BY city ORDER BY total DESC LIMIT 10";

    @Test
    void typicalDrillDownAppendsDimensionToBothClauses() {
        Optional<DrillDownPlanner.Plan> plan = DrillDownPlanner.planWithDims("那按省份呢", TOP10_SQL, DIMS);
        assertTrue(plan.isPresent());
        assertEquals("province", plan.get().dimension());
        String sql = plan.get().sql();
        assertTrue(sql.contains("AS total, province FROM"), sql);
        assertTrue(sql.contains("GROUP BY city, province ORDER BY"), sql);
        assertTrue(sql.startsWith("SELECT city, SUM(amount)"));
        assertTrue(sql.endsWith("LIMIT 10"));
    }

    @Test
    void keywordVariantsAndChineseParticlesExtractCleanDimension() {
        assertTrue(DrillDownPlanner.planWithDims("换成品类看一下？", TOP10_SQL, DIMS).isPresent());
        DrillDownPlanner.Plan p = DrillDownPlanner.planWithDims("那按渠道维度呢", TOP10_SQL, DIMS).orElseThrow();
        assertEquals("channel", p.dimension());
        assertTrue(DrillDownPlanner.planWithDims("再细分到品类", TOP10_SQL, DIMS).isPresent());
    }

    @Test
    void columnNamesWithoutAliasesStillMatchLiterally() {
        // 便捷入口：无语义层别名时列名直匹配（英文问法或中文列库）
        assertEquals("province", DrillDownPlanner.plan("那按province呢", TOP10_SQL, COLS)
                .orElseThrow().dimension());
        assertTrue(DrillDownPlanner.plan("那按省份呢", TOP10_SQL, COLS).isEmpty());
    }

    @Test
    void groupByWithFunctionCallAndHavingBoundaryIsSafe() {
        // 函数闭括号不得被误判为段尾；HAVING 是合法边界
        String sql = "SELECT DATE(create_time), COUNT(*) AS cnt FROM orders GROUP BY DATE(create_time) HAVING COUNT(*) > 3";
        DrillDownPlanner.Plan p = DrillDownPlanner.planWithDims("那按渠道呢", sql, DIMS).orElseThrow();
        assertTrue(p.sql().contains("GROUP BY DATE(create_time), channel HAVING"), p.sql());
        assertTrue(p.sql().contains("COUNT(*) AS cnt, channel FROM"), p.sql());
    }

    @Test
    void stringLiteralsNeverAffectClauseDetection() {
        // 字符串里的 "from/group by" 已掩码，不参与深度 0 关键字判定；手术不碰字面量内容
        String sql = "SELECT city, SUM(amount) AS total FROM orders WHERE note = '来自 from 表 group by' GROUP BY city";
        DrillDownPlanner.Plan p = DrillDownPlanner.planWithDims("那按省份呢", sql, DIMS).orElseThrow();
        assertTrue(p.sql().contains("WHERE note = '来自 from 表 group by'"), p.sql());
        assertTrue(p.sql().contains("GROUP BY city, province"), p.sql());
    }

    @Test
    void noGroupBySkeletonDeclines() {
        // 明细查询（无聚合分组）没有可"追加维度"的口径骨架：弃权
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢", "SELECT id, amount FROM orders", DIMS).isEmpty());
    }

    @Test
    void dimensionAlreadyGroupedIsRepeatNotDrillDown() {
        assertTrue(DrillDownPlanner.planWithDims("那按城市呢", TOP10_SQL, DIMS).isEmpty());
    }

    @Test
    void ambiguousOrUnknownDimensionDeclines() {
        // 等值标签唯一命中优先于包含歧义："省份"=province 的标签，province_code 仅包含式命中
        List<DrillDownPlanner.Dim> labeled = List.of(
                new DrillDownPlanner.Dim("province", "省份"),
                new DrillDownPlanner.Dim("province_code", "省份编码"),
                new DrillDownPlanner.Dim("city", "城市"));
        assertEquals("province", DrillDownPlanner.planWithDims("那按省份呢", TOP10_SQL, labeled)
                .orElseThrow().dimension());
        // 无等值时包含式多命中不同列：歧义不猜
        List<DrillDownPlanner.Dim> containsAmbiguous = List.of(
                new DrillDownPlanner.Dim("province", "省份区域"),
                new DrillDownPlanner.Dim("province_code", "省份编码")) ;
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢", TOP10_SQL, containsAmbiguous).isEmpty());
        // 同标签落在两个不同列：等值也歧义
        List<DrillDownPlanner.Dim> sameLabel = List.of(
                new DrillDownPlanner.Dim("province", "省份"),
                new DrillDownPlanner.Dim("province_code", "省份"));
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢", TOP10_SQL, sameLabel).isEmpty());
        // 未命中
        assertTrue(DrillDownPlanner.planWithDims("那按性别呢", TOP10_SQL, DIMS).isEmpty());
    }

    @Test
    void complexShapesDecline() {
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢",
                "SELECT city FROM a GROUP BY city UNION ALL SELECT city FROM b GROUP BY city", DIMS).isEmpty());
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢",
                "SELECT * FROM (SELECT city, SUM(amount) s FROM orders GROUP BY city) t", DIMS).isEmpty());
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢",
                "WITH t AS (SELECT 1) SELECT city FROM t GROUP BY city", DIMS).isEmpty());
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢",
                "SELECT o.city, SUM(o.amount) FROM orders o JOIN users u ON o.uid=u.id GROUP BY o.city", DIMS).isEmpty());
        // 派生表：外层追加的维度列不在子查询输出里，造出来必错 SQL → 必须弃权
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢",
                "SELECT city FROM (SELECT city, SUM(amount) s FROM orders GROUP BY city) g GROUP BY city", DIMS).isEmpty());
        // 隐式逗号多表：维度列归属歧义 → 弃权
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢",
                "SELECT u.city, SUM(o.amount) FROM orders o, users u WHERE o.uid=u.id GROUP BY u.city", DIMS).isEmpty());
    }

    @Test
    void degenerateInputsDeclineSilently() {
        assertTrue(DrillDownPlanner.planWithDims(null, TOP10_SQL, DIMS).isEmpty());
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢", null, DIMS).isEmpty());
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢", TOP10_SQL, List.of()).isEmpty());
        // 无"按W"表达的完整问句不走下钻
        assertTrue(DrillDownPlanner.planWithDims("各省份的销售额是多少", TOP10_SQL, DIMS).isEmpty());
        // 多处维度诉求：意图不唯一，弃权
        assertTrue(DrillDownPlanner.planWithDims("那按省份呢，再按渠道看下", TOP10_SQL, DIMS).isEmpty());
        // 单字维度词（剥助词后不足 2 字）歧义面大：放弃
        assertFalse(DrillDownPlanner.planWithDims("那按区呢", TOP10_SQL, DIMS).isPresent());
    }
}
