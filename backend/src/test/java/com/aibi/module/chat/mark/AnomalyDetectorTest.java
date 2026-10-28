package com.aibi.module.chat.mark;

import com.aibi.common.analysis.ColumnRoleClassifier;
import com.aibi.common.analysis.ColumnRoleClassifier.ColumnRole;
import com.aibi.module.chat.dto.AnomalyMark;
import com.aibi.module.chat.dto.AnomalyMarks;
import com.aibi.module.datasource.model.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 环比/异常标记检测器（T10-4b K4）纯逻辑单测：不起 Spring、不连库——列角色以 {@code ColumnRoleClassifier}
 * 桩返回，专注钉死检测形态门槛（行数/时间列数/类别列排除）、相邻周期计算（除零跳过、阈值边界、方向）、
 * 乱序时间排序、同时间点合并与全程 fail-soft。
 */
class AnomalyDetectorTest {

    /** 桩：按给定角色序列返回。 */
    private AnomalyDetector detectorWith(ColumnRole... roles) {
        ColumnRoleClassifier classifier = mock(ColumnRoleClassifier.class);
        when(classifier.classifyAll(any(QueryResult.class), any()))
                .thenReturn(List.of(roles));
        return new AnomalyDetector(classifier);
    }

    private static QueryResult result(List<String> cols, List<List<Object>> rows) {
        return QueryResult.of("SELECT 1", cols, rows, rows.size(), 1L);
    }

    private static final List<String> MONTH_AMOUNT = List.of("stat_month", "amount");

    @Test
    @DisplayName("① 标准时间序列：3 个月环比 +150% 命中一条，事实结论含数字不含归因")
    void basicSequenceHit() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        AnomalyMarks m = d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-01", 100L),
                List.<Object>of("2026-02", 120L),
                List.<Object>of("2026-03", 300L))), 30);
        assertNotNull(m);
        assertEquals(1, m.items().size());
        AnomalyMark mark = m.items().get(0);
        assertEquals("2026-03", mark.timePoint());
        assertEquals("amount", mark.colName());
        assertEquals(150.0, mark.pctChange(), 0.001);
        assertTrue(mark.increase());
        assertTrue(m.summary().contains("1 处波动超过 30%"));
        assertTrue(m.summary().contains("120 → 300"));
    }

    @Test
    @DisplayName("② 行数 <3 / 无时间列 / 时间列重复：形态门槛一律 null")
    void shapeGuards() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        assertNull(d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-01", 100L),
                List.<Object>of("2026-02", 300L))), 30));          // 仅 2 行
        AnomalyDetector noTime = detectorWith(ColumnRole.CATEGORY, ColumnRole.MEASURE);
        assertNull(noTime.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("a", 1L), List.<Object>of("b", 2L), List.<Object>of("c", 3L))), 30));
        AnomalyDetector twoTimes = detectorWith(ColumnRole.TEMPORAL, ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        assertNull(twoTimes.detect(1L, result(List.of("d1", "d2", "v"), List.of(
                List.<Object>of("2026-01", "2026-01", 1L),
                List.<Object>of("2026-02", "2026-02", 2L),
                List.<Object>of("2026-03", "2026-03", 9L))), 30));
    }

    @Test
    @DisplayName("③ 存在类别列：分组行不是时间序列，伪环比不做")
    void categoryColumnExcluded() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.CATEGORY, ColumnRole.MEASURE);
        assertNull(d.detect(1L, result(List.of("stat_month", "city", "amount"), List.of(
                List.<Object>of("2026-01", "上海", 100L),
                List.<Object>of("2026-02", "上海", 120L),
                List.<Object>of("2026-03", "上海", 900L))), 30));
    }

    @Test
    @DisplayName("④ 阈值边界：恰等于 30% 命中（达阈值即标），29.9% 不命中；全不命中返回 null")
    void thresholdBoundary() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        // 110 → 143：恰 +30% 命中（100→110 仅 +10% 不命中，隔离被测区间）
        AnomalyMarks hit = d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-01", 100L),
                List.<Object>of("2026-02", 110L),
                List.<Object>of("2026-03", 143L))), 30);
        assertNotNull(hit);
        assertEquals(1, hit.items().size());
        // 110 → 142.9：+29.9% 无命中 → 整体 null
        assertNull(d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-01", 100L),
                List.<Object>of("2026-02", 110L),
                List.<Object>of("2026-03", 142.9))), 30));
    }

    @Test
    @DisplayName("⑤ 上期值为 0：除零无环比意义，跳过不标")
    void zeroPrevSkipped() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        AnomalyMarks m = d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-01", 0L),
                List.<Object>of("2026-02", 500L),   // 0→500 跳过
                List.<Object>of("2026-03", 100L))), 30);  // 500→100 = -80% 命中
        assertNotNull(m);
        assertEquals(1, m.items().size());
        assertEquals("2026-03", m.items().get(0).timePoint());
        assertFalse(m.items().get(0).increase());
        assertEquals(-80.0, m.items().get(0).pctChange(), 0.001);
    }

    @Test
    @DisplayName("⑥ 乱序时间（字符串键）：按时间自然序排序后再算环比，rowIdx 为排序后序号")
    void unorderedTimeSorted() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        AnomalyMarks m = d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-03", 300L),
                List.<Object>of("2026-01", 100L),
                List.<Object>of("2026-02", 120L))), 30);
        assertNotNull(m);
        assertEquals(1, m.items().size());
        assertEquals("2026-03", m.items().get(0).timePoint());
        assertEquals(2, m.items().get(0).rowIdx());   // 排序后 01/02/03 → 03 在 index 2
    }

    @Test
    @DisplayName("⑦ 同时间点重复行：合并取和后再环比，防脏双计")
    void duplicateTimeMerged() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        AnomalyMarks m = d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-01", 50L),
                List.<Object>of("2026-01", 50L),   // 合并为 100
                List.<Object>of("2026-02", 100L),  // 100→100 无波动
                List.<Object>of("2026-03", 900L))), 30);  // 100→900 = +800% 命中
        assertNotNull(m);
        assertEquals(1, m.items().size());
        assertEquals("2026-03", m.items().get(0).timePoint());
        assertEquals(2, m.items().get(0).rowIdx());  // 合并后序列 01/02/03 → 03 在 index 2
    }

    @Test
    @DisplayName("⑧ 多度量列：各度量独立成标")
    void multiMeasure() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE, ColumnRole.MEASURE);
        AnomalyMarks m = d.detect(1L, result(List.of("stat_month", "amount", "qty"), List.of(
                List.<Object>of("2026-01", 100L, 10L),
                List.<Object>of("2026-02", 110L, 10L),
                List.<Object>of("2026-03", 300L, 2L))), 30);
        assertNotNull(m);
        assertEquals(2, m.items().size());   // amount +172.7% 与 qty -80%
        assertEquals("amount", m.items().get(0).colName());
        assertEquals("qty", m.items().get(1).colName());
        assertFalse(m.items().get(1).increase());
    }

    @Test
    @DisplayName("⑨ fail-soft：角色列表与列数不匹配（越界）等异常一律降级 null，不抛出")
    void failSoft() {
        ColumnRoleClassifier broken = mock(ColumnRoleClassifier.class);
        when(broken.classifyAll(any(QueryResult.class), any())).thenReturn(List.of());
        AnomalyDetector d = new AnomalyDetector(broken);
        assertNull(d.detect(1L, result(MONTH_AMOUNT, List.of(
                List.<Object>of("2026-01", 1L),
                List.<Object>of("2026-02", 2L),
                List.<Object>of("2026-03", 9L))), 30));   // 无时间列命中 → null
        assertNull(d.detect(1L, null, 30));                 // 空结果
        assertNull(new AnomalyDetector(mock(ColumnRoleClassifier.class))
                .detect(null, result(MONTH_AMOUNT, List.of(
                        List.<Object>of("2026-01", 1L),
                        List.<Object>of("2026-02", 2L),
                        List.<Object>of("2026-03", 9L))), 30)); // 未桩 classifyAll 返回 null → NPE 被吞 → null
    }

    @Test
    @DisplayName("⑩ 数值时间键（year=2024 型）：按数值自然序，Date 不参与（桩为 TEMPORAL）")
    void numericTimeKey() {
        AnomalyDetector d = detectorWith(ColumnRole.TEMPORAL, ColumnRole.MEASURE);
        AnomalyMarks m = d.detect(1L, result(List.of("year", "amount"), List.of(
                List.<Object>of(2026, 500L),
                List.<Object>of(2024, 100L),
                List.<Object>of(2025, 120L))), 30);
        assertNotNull(m);
        assertEquals("2026", m.items().get(0).timePoint()); // 排序 2024/2025/2026：120→500 +316%
        assertEquals(2, m.items().get(0).rowIdx());
    }
}
