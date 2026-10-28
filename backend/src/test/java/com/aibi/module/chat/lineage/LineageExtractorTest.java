package com.aibi.module.chat.lineage;

import com.aibi.module.chat.dto.Lineage;
import com.aibi.module.chat.dto.LineageTable;
import com.aibi.module.chat.nl2sql.SemanticView;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.service.SemanticService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结果溯源装配器（T10-3）纯逻辑单测：不起 Spring、不连库——{@code SemanticService.view} 桩语义视图，
 * 覆盖确定性抽表、语义层富化、裸名去重、无数据源不富化、以及全程 fail-soft（异常一律降级 null）。
 */
class LineageExtractorTest {

    private static final Long DS = 100L;

    private static SemanticMeta tableRow(String table, String alias, String desc) {
        SemanticMeta m = new SemanticMeta();
        m.setObjectType("TABLE");
        m.setTableName(table);
        m.setAliasCn(alias);
        m.setDescription(desc);
        m.setEnabled(1);
        return m;
    }

    private static SemanticView viewWith(SemanticMeta... tables) {
        Map<String, SemanticMeta> t = new HashMap<>();
        for (SemanticMeta m : tables) {
            t.put(m.getTableName(), m);
        }
        return new SemanticView(t, Map.of());
    }

    private static LineageExtractor extractor(SemanticService svc) {
        return new LineageExtractor(svc);
    }

    @Test
    @DisplayName("① SQL 为空 → null")
    void blankSql() {
        assertNull(extractor(mock(SemanticService.class)).extract(DS, "  "));
        assertNull(extractor(mock(SemanticService.class)).extract(DS, null));
    }

    @Test
    @DisplayName("② 无语义数据：抽出物理表、别名为 null")
    void extractsTableWithoutAlias() {
        SemanticService svc = mock(SemanticService.class);
        when(svc.view(DS)).thenReturn(SemanticView.empty());
        Lineage lin = extractor(svc).extract(DS, "SELECT id FROM orders WHERE amt > 100");
        assertNotNull(lin);
        assertEquals(1, lin.tables().size());
        assertEquals("orders", lin.tables().get(0).table());
        assertNull(lin.tables().get(0).aliasCn());
        assertEquals("SELECT id FROM orders WHERE amt > 100", lin.sql());
    }

    @Test
    @DisplayName("③ 语义层命中（大小写不敏感）：业务名与描述被富化")
    void enrichesFromSemanticView() {
        SemanticService svc = mock(SemanticService.class);
        when(svc.view(DS)).thenReturn(viewWith(tableRow("Orders", "订单表", "全域订单明细")));
        Lineage lin = extractor(svc).extract(DS, "SELECT COUNT(*) FROM orders");
        assertNotNull(lin);
        LineageTable t0 = lin.tables().get(0);
        assertEquals("订单表", t0.aliasCn());
        assertEquals("全域订单明细", t0.description());
    }

    @Test
    @DisplayName("④ 同裸名不同限定写法去重：db.sales 与 sales 只留一条（保留更限定者）")
    void dedupesByBareName() {
        SemanticService svc = mock(SemanticService.class);
        when(svc.view(DS)).thenReturn(SemanticView.empty());
        Lineage lin = extractor(svc).extract(DS, "SELECT 1 FROM db.sales a JOIN sales b ON a.id = b.id");
        assertNotNull(lin);
        assertEquals(1, lin.tables().size(), lin.tables().toString());
        // 更完整（带库前缀）的物理名应被保留
        assertTrue(lin.tables().get(0).table().contains("."), lin.tables().get(0).table());
    }

    @Test
    @DisplayName("⑤ 多表：全部来源表按裸名排序产出")
    void multipleTablesSorted() {
        SemanticService svc = mock(SemanticService.class);
        when(svc.view(DS)).thenReturn(SemanticView.empty());
        Lineage lin = extractor(svc).extract(DS,
                "SELECT a.id FROM zz_city a JOIN aa_sales b ON a.id = b.city_id");
        assertNotNull(lin);
        assertEquals(2, lin.tables().size());
        assertEquals("aa_sales", lin.tables().get(0).table());
        assertEquals("zz_city", lin.tables().get(1).table());
    }

    @Test
    @DisplayName("⑥ 数据源为空：不查语义视图，只给物理表名")
    void nullDatasourceSkipsEnrichment() {
        SemanticService svc = mock(SemanticService.class);
        Lineage lin = extractor(svc).extract(null, "SELECT 1 FROM t1");
        assertNotNull(lin);
        assertEquals("t1", lin.tables().get(0).table());
        assertNull(lin.tables().get(0).aliasCn());
        verify(svc, never()).view(anyLong());
    }

    @Test
    @DisplayName("⑦ 抽不出任何表（SELECT 1）→ null")
    void noTableReturnsNull() {
        SemanticService svc = mock(SemanticService.class);
        when(svc.view(DS)).thenReturn(SemanticView.empty());
        assertNull(extractor(svc).extract(DS, "SELECT 1"));
    }

    @Test
    @DisplayName("⑧ fail-soft：语义视图读取抛异常 → 降级 null，绝不外抛")
    void failSoftOnException() {
        SemanticService svc = mock(SemanticService.class);
        when(svc.view(DS)).thenThrow(new IllegalStateException("语义库不可用"));
        assertNull(extractor(svc).extract(DS, "SELECT 1 FROM orders"));
    }

    @Test
    @DisplayName("⑨ LLM 给表名加了反引号：展示名去引号、富化仍按裸名命中")
    void stripsIdentifierQuotes() {
        SemanticService svc = mock(SemanticService.class);
        when(svc.view(DS)).thenReturn(viewWith(tableRow("sys_config", "系统配置表", null)));
        Lineage lin = extractor(svc).extract(DS, "SELECT config_key FROM `sys_config` LIMIT 5");
        assertNotNull(lin);
        assertEquals("sys_config", lin.tables().get(0).table());   // 无反引号
        assertEquals("系统配置表", lin.tables().get(0).aliasCn());   // 去引号后仍命中语义层
    }
}
