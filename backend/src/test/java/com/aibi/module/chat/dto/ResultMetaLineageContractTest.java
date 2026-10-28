package com.aibi.module.chat.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 溯源回传结果体（T10-3）与环比标记（T10-4b）数据契约测试：钉死 {@link ResultMeta} 第 9 组件
 * {@code lineage}、第 10 组件 {@code marks} 的扩展不破坏既有构造——8/9 参兼容构造器缺省组件恒为 null，
 * 10 参构造可携带 {@link Lineage} 与 {@link AnomalyMarks}，组件访问器行为正确。
 */
class ResultMetaLineageContractTest {

    @Test
    @DisplayName("① 8 参兼容构造：lineage/marks 均为 null，其余组件原样")
    void legacyEightArgConstructor() {
        ResultMeta m = new ResultMeta(false, 50, 50, false, false, 1, 0.8, true);
        assertNull(m.lineage());
        assertNull(m.marks());
        assertEquals(50, m.returnedRows());
        assertEquals(0.8, m.confidence());
        assertEquals(true, m.crossChecked());
    }

    @Test
    @DisplayName("② 9 参兼容构造（T10-3 形态）：lineage 落位、marks 为 null，组件总数 10")
    void nineArgCarriesLineage() {
        Lineage lin = new Lineage(List.of(new LineageTable("orders", "订单表", null)), "SELECT 1 FROM orders");
        ResultMeta m = new ResultMeta(false, 50, 50, false, false, 1, null, false, lin);
        assertEquals(10, ResultMeta.class.getRecordComponents().length);
        assertEquals("orders", m.lineage().tables().get(0).table());
        assertEquals("订单表", m.lineage().tables().get(0).aliasCn());
        assertNull(m.marks());
    }

    @Test
    @DisplayName("③ LineageTable 缺语义登记：别名与描述为 null，物理名仍在")
    void lineageTableAllowsNullEnrichment() {
        LineageTable t = new LineageTable("raw_tbl", null, null);
        assertEquals("raw_tbl", t.table());
        assertNull(t.aliasCn());
        assertNull(t.description());
    }

    @Test
    @DisplayName("④ 10 参构造（T10-4b）：marks 落位第 10 组件，lineage 一并携带")
    void tenArgCarriesMarks() {
        AnomalyMark mark = new AnomalyMark("2026-03", 2, 1, "amount", 62, 45, 37.8, true);
        AnomalyMarks marks = new AnomalyMarks(30, "stat_month", "相邻 stat_month 间共 1 处波动超过 30%",
                List.of(mark));
        ResultMeta m = new ResultMeta(false, 3, 3, false, false, 1, null, false, null, marks);
        assertEquals(30, m.marks().thresholdPct());
        assertEquals("2026-03", m.marks().items().get(0).timePoint());
        assertEquals(true, m.marks().items().get(0).increase());
        assertNull(m.lineage());
    }
}
