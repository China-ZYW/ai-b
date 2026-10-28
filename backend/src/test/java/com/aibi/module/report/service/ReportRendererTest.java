package com.aibi.module.report.service;

import com.aibi.module.datasource.model.QueryResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportRenderer} 纯逻辑单测（T12-1 K1 + 可读性补票）：空结果/别名表头回落/行数截断/
 * 单元格截断/null 单元格/上游 maxRows 截断标注/数值列千分位右对齐。
 */
class ReportRendererTest {

    private static final LocalDateTime RUN_AT = LocalDateTime.of(2026, 10, 28, 9, 0);

    private static QueryResult result(List<String> columns, List<String> labels,
                                      List<List<Object>> rows, int rowCount) {
        return new QueryResult("SELECT 1", columns, labels, rows, rowCount, 42, null);
    }

    @Test
    void nullOrEmptyResultRendersPlaceholder() {
        String empty = ReportRenderer.render("日报", "模板A", RUN_AT,
                result(List.of("a"), null, List.of(), 0));
        assertTrue(empty.contains("（查询结果为空）"));
        assertTrue(empty.contains("【定时报表】日报"));
        assertTrue(empty.contains("耗时 42 ms"));
        // result 为 null 时不渲染耗时行，也不抛
        String nullRes = ReportRenderer.render("日报", null, null, null);
        assertTrue(nullRes.contains("（查询结果为空）"));
        assertFalse(nullRes.contains("耗时"));
    }

    @Test
    void headerPrefersLabelsAndFallsBackPerColumn() {
        // List.of 禁 null，含 null 元素的别名列表用 Arrays.asList（真实 columnLabels 允许元素级 null）
        QueryResult r = result(List.of("amt", "cnt"), Arrays.asList("销售额", null),
                List.of(List.of(100, 2)), 1);
        String out = ReportRenderer.render("日报", "模板A", RUN_AT, r);
        assertTrue(out.contains("销售额"), "别名列应出展示名");
        assertTrue(out.contains("cnt"), "别名为空的列回落物理列名");
        assertTrue(out.contains("来源模板：模板A"));
        assertTrue(out.contains("执行时间：2026-10-28 09:00"));
    }

    @Test
    void moreThanTopRowsTruncatedWithAnnotation() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            rows.add(List.of(i));
        }
        String out = ReportRenderer.render("日报", null, RUN_AT, result(List.of("n"), null, rows, 25));
        // 前 20 行在，第 21 行不在（整型列右对齐，个位数带前导空格）
        assertTrue(out.contains("\n 1\n"), "首行存在（右对齐补位）");
        assertFalse(out.contains("\n21"), "第 21 行不应展示");
        assertTrue(out.contains("共 25 行，仅展示前 20 行"));
        long dataLines = out.lines().filter(l -> l.matches("^\\s*\\d+\\s*$")).count();
        assertEquals(ReportRenderer.TOP_ROWS, dataLines, "展示数据行数恰为 20");
    }

    @Test
    void upstreamTruncatedRowCountAnnotated() {
        // rows 恰 20 行但 rowCount=200：上游 maxRows 截断，需注明获取行数
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(List.of(i));
        }
        String out = ReportRenderer.render("日报", null, RUN_AT, result(List.of("n"), null, rows, 200));
        assertTrue(out.contains("结果共 200 行"));
        assertFalse(out.contains("仅展示前 20 行"), "所得行已全部展示，不该出前 20 截断语");
    }

    @Test
    void longCellTruncatedToMaxWidth() {
        String longText = "阿".repeat(ReportRenderer.CELL_MAX + 10);
        String out = ReportRenderer.render("日报", null, RUN_AT,
                result(List.of("t"), null, List.of(List.of((Object) longText)), 1));
        assertTrue(out.contains("…"), "超长单元格应带省略号");
        assertFalse(out.contains(longText), "原文不得整段出现在正文");
    }

    @Test
    void nullCellRendersDash() {
        String out = ReportRenderer.render("日报", null, RUN_AT,
                result(List.of("a", "b"), null, List.of(nullCellRow()), 1));
        // 分隔线恒含 '-'，故断言到具体单元格拼接形态：null 列渲染为 "- | ok"
        assertTrue(out.contains("- | ok"), "null 单元格应渲染为 - 且与次列同行");
    }

    @Test
    void numericColumnThousandsRightAligned() {
        // 金额千分位 2 位小数、比率四舍五入 2 位、整型千分位无小数；数值列右对齐
        QueryResult r = result(List.of("channel", "amt", "rate", "cnt"), null,
                List.of(
                        List.of("O2O到家", 133505.88, 0.223324, 100),
                        List.of("直播带货", 99219.94, 0.5, 12345)),
                2);
        String out = ReportRenderer.render("利润排行", "模板A", RUN_AT, r);
        assertTrue(out.contains("133,505.88"), "浮点千分位 2 位小数");
        assertTrue(out.contains("0.22"), "比率四舍五入 2 位");
        assertTrue(out.contains("0.50"), "不足 2 位补零");
        assertTrue(out.contains("12,345"), "整型千分位无小数");
        // 右对齐：短值前导空格补齐到列宽（amt 列宽 10，99,219.94 前补 1 空格）
        assertTrue(out.contains(" 99,219.94"), "数值列右对齐补位");
        // 字符串列保持左对齐：行首紧跟列值无前导空格（列宽补齐的空格在值右侧）
        assertTrue(out.contains("\nO2O到家"), "字符串列左对齐");
    }

    /** 含 null 单元格的单行（List.of 不允许 null，借 ArrayList 构造）。 */
    private static List<Object> nullCellRow() {
        List<Object> row = new ArrayList<>();
        row.add(null);
        row.add("ok");
        return row;
    }
}
