package com.aibi.module.chat.nl2sql;

import com.aibi.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlValidator} 只读安全矩阵单测（T3 基线，纯逻辑、不依赖 Spring 上下文与 MySQL）。
 *
 * <p>覆盖五类判定：合法单条 SELECT 放行（含中文列名/子查询/多行 SQL）、
 * 写语句拒绝、多语句拒绝、文件导出拒绝、空/UNKNOWN 拒绝；
 * 并以 characterization 断言固化「分号在字符串字面量内也会被判多语句」的现有行为边界。
 */
class SqlValidatorTest {

    private final SqlValidator validator = new SqlValidator();

    // ---------- 放行 ----------

    @Test
    void plainSelectPasses() {
        assertEquals("SELECT * FROM orders", validator.validate("  SELECT * FROM orders;  "));
    }

    @Test
    void selectWithoutSemicolonKeptAsIs() {
        String sql = "SELECT id, amount FROM orders WHERE amount > 100";
        assertEquals(sql, validator.validate(sql));
    }

    @Test
    void multilineSelectPasses() {
        String sql = "SELECT region, SUM(amount) AS total\nFROM sales\nGROUP BY region";
        assertEquals(sql, validator.validate(sql));
    }

    @Test
    void subquerySelectPasses() {
        String sql = "SELECT * FROM (SELECT id FROM orders) t";
        assertEquals(sql, validator.validate(sql));
    }

    @Test
    void chineseIdentifiersPassAndReturnRawSql() {
        // 解析前非 ASCII 折叠为占位符，校验通过后必须返回未折叠原文（否则中文列名会被破坏）
        String sql = "SELECT 地区, SUM(销售额) AS 销售总额 FROM data";
        assertEquals(sql, validator.validate(sql));
    }

    // ---------- 拒绝：写语句 ----------

    @Test
    void dmlAndDdlRejected() {
        for (String sql : new String[]{
                "UPDATE orders SET amount = 0 WHERE id = 1",
                "DELETE FROM orders",
                "INSERT INTO orders VALUES (1, 2)",
                "DROP TABLE orders",
                "TRUNCATE TABLE orders",
                "ALTER TABLE orders ADD COLUMN x INT",
                "CREATE TABLE t (id INT)"}) {
            BizException ex = assertThrows(BizException.class, () -> validator.validate(sql),
                    () -> "应拒绝写/DDL 语句: " + sql);
            assertTrue(ex.getMessage().contains("仅允许 SELECT"),
                    () -> sql + " 的拒绝原因应为只读拦截，实际: " + ex.getMessage());
        }
    }

    // ---------- 拒绝：多语句 ----------

    @Test
    void multiStatementRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate("SELECT 1; DELETE FROM orders"));
        assertTrue(ex.getMessage().contains("多条"));
    }

    @Test
    void semicolonInsideStringLiteralCurrentlyRejected() {
        // characterization：现实现按整串扫描分号，字符串字面量内的 ';' 也会触发多语句判定。
        // 该误伤边界被 LLM 生成"过滤条件含分号"时可能命中，修复前应固化现状（见重构任务清单 T3 注记）。
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate("SELECT * FROM t WHERE remark = 'a;b'"));
        assertTrue(ex.getMessage().contains("多条"));
    }

    // ---------- 拒绝：文件导出 ----------

    @Test
    void intoOutfileOrDumpfileRejected() {
        assertThrows(BizException.class,
                () -> validator.validate("SELECT * FROM orders INTO OUTFILE '/tmp/a.csv'"));
        assertThrows(BizException.class,
                () -> validator.validate("SELECT * FROM orders INTO DUMPFILE '/tmp/a.bin'"));
    }

    // ---------- 拒绝：空 / UNKNOWN ----------

    @Test
    void blankOrUnknownRejected() {
        for (String sql : new String[]{null, "", "   ", "UNKNOWN", "unknown"}) {
            BizException ex = assertThrows(BizException.class, () -> validator.validate(sql),
                    () -> "应拒绝空/UNKNOWN: [" + sql + "]");
            assertTrue(ex.getMessage().contains("有效 SQL"));
        }
    }

    // ---------- 拒绝：解析失败 ----------

    @Test
    void unparsableSqlRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate("SELEKT from WHERE"));
        assertTrue(ex.getMessage().contains("解析失败"));
    }
}
