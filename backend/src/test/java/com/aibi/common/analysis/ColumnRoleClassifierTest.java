package com.aibi.common.analysis;

import com.aibi.common.analysis.ColumnRoleClassifier.ColumnRole;
import com.aibi.module.semantic.service.SemanticService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列角色判定公共组件（T10-4b K1）纯逻辑单测：不起 Spring、不连库——语义层以 {@code SemanticService.columnRole}
 * 桩返回。钉死自 ChartRecommender 下沉后的判定行为等价：人工角色优先、AUTO 回落启发式、词边界防误判、
 * 主键/编码归类别、日期样式字符串归时间维。
 */
class ColumnRoleClassifierTest {

    private static final Long DS = 100L;

    private ColumnRoleClassifier classifierNoSemantic() {
        SemanticService semantic = mock(SemanticService.class);
        when(semantic.columnRole(anyLong(), anyString())).thenReturn(null);
        return new ColumnRoleClassifier(semantic);
    }

    @Test
    @DisplayName("① 无数据源（datasourceId=null）不查语义层，纯启发式：date 词根+字符串样本归时间维、金额归度量")
    void heuristicWithoutDatasource() {
        SemanticService semantic = mock(SemanticService.class);
        ColumnRoleClassifier c = new ColumnRoleClassifier(semantic);
        List<List<Object>> rows = List.of(
                List.<Object>of("2026-01-01", 100L),
                List.<Object>of("2026-02-01", 250L));
        assertEquals(ColumnRole.TEMPORAL, c.classify("order_date", rows, 0, null));
        assertEquals(ColumnRole.MEASURE, c.classify("amount", rows, 1, null));
        // 数据源为空时短路：语义层一次都不应被查询
        verify(semantic, never()).columnRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("② 语义层人工角色先于启发式：列名像时间但人工标 MEASURE → 度量")
    void semanticRoleTakesPrecedence() {
        SemanticService semantic = mock(SemanticService.class);
        when(semantic.columnRole(DS, "stat_date")).thenReturn("MEASURE");
        ColumnRoleClassifier c = new ColumnRoleClassifier(semantic);
        List<List<Object>> rows = List.of(List.<Object>of("2026-01"), List.<Object>of("2026-02"));
        assertEquals(ColumnRole.MEASURE, c.classify("stat_date", rows, 0, DS));
    }

    @Test
    @DisplayName("③ 语义 AUTO/未知角色回落启发式；DIMENSION/TEMPORAL 映射类别/时间")
    void semanticAutoFallsBackAndMappings() {
        SemanticService semantic = mock(SemanticService.class);
        when(semantic.columnRole(DS, "city")).thenReturn("AUTO");
        when(semantic.columnRole(DS, "amount")).thenReturn("DIMENSION");
        when(semantic.columnRole(DS, "dt")).thenReturn("TEMPORAL");
        ColumnRoleClassifier c = new ColumnRoleClassifier(semantic);
        List<List<Object>> rows = List.of(List.<Object>of("上海", 1L, "2026-01"));
        // AUTO → 启发式：city 样本字符串且无时间特征 → CATEGORY
        assertEquals(ColumnRole.CATEGORY, c.classify("city", rows, 0, DS));
        // 人工 DIMENSION 压过"数值像度量"的启发式
        assertEquals(ColumnRole.CATEGORY, c.classify("amount", rows, 1, DS));
        assertEquals(ColumnRole.TEMPORAL, c.classify("dt", rows, 2, DS));
    }

    @Test
    @DisplayName("④ 词边界：candidate（含 date）/overtime（含 time）不误判为时间维，仍归度量")
    void wordBoundaryAvoidsFalseTemporal() {
        ColumnRoleClassifier c = classifierNoSemantic();
        List<List<Object>> rows = List.of(List.<Object>of(3, 7));
        assertEquals(ColumnRole.MEASURE, c.classify("candidate_score", rows, 0, null));
        assertEquals(ColumnRole.MEASURE, c.classify("overtime_ms", rows, 1, null));
        // 前缀为串首或下划线分隔仍判时间
        assertEquals(ColumnRole.TEMPORAL, c.classify("create_time", rows, 0, null));
    }

    @Test
    @DisplayName("⑤ 数值样本：year=2024 归时间维；id/编号/编码类数值归类别（不是度量）")
    void numericSpecialCases() {
        ColumnRoleClassifier c = classifierNoSemantic();
        List<List<Object>> rows = List.of(List.<Object>of(2024, 1001, 55L));
        assertEquals(ColumnRole.TEMPORAL, c.classify("year", rows, 0, null));
        assertEquals(ColumnRole.CATEGORY, c.classify("id", rows, 1, null));
        assertEquals(ColumnRole.CATEGORY, c.classify("订单编号", rows, 2, null));
    }

    @Test
    @DisplayName("⑥ 字符串样本形如日期（yyyy-MM / yyyy年MM月）即使列名无时间特征也归时间维")
    void dateStringLookalike() {
        ColumnRoleClassifier c = classifierNoSemantic();
        List<List<Object>> rows = List.of(List.<Object>of("2026-01", "2026年2月"));
        assertEquals(ColumnRole.TEMPORAL, c.classify("period", rows, 0, null));
        assertEquals(ColumnRole.TEMPORAL, c.classify("period", rows, 1, null));
    }

    @Test
    @DisplayName("⑦ Temporal 类型样本直接归时间维；classifyAll 与列序一一对应")
    void temporalObjectAndClassifyAll() {
        ColumnRoleClassifier c = classifierNoSemantic();
        com.aibi.module.datasource.model.QueryResult r = com.aibi.module.datasource.model.QueryResult.of(
                "SELECT 1", List.of("biz_date", "amount", "city"),
                List.of(List.<Object>of(LocalDate.of(2026, 1, 1), 100, "上海"),
                        List.<Object>of(LocalDate.of(2026, 2, 1), 250, "北京")),
                2, 1L);
        List<ColumnRole> roles = c.classifyAll(r, null);
        assertEquals(List.of(ColumnRole.TEMPORAL, ColumnRole.MEASURE, ColumnRole.CATEGORY), roles);
    }
}
