package com.aibi.module.semantic.patrol;

import com.aibi.module.chat.distill.GovernanceIssue;
import com.aibi.module.chat.distill.GovernanceIssueLedgerService;
import com.aibi.module.chat.distill.GovernanceSignal;
import com.aibi.module.chat.nl2sql.SchemaProvider;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.entity.DataSource;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.semantic.entity.SemanticMeta;
import com.aibi.module.semantic.mapper.SemanticMetaMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 元数据巡检编排（T11-4）单测：mock Mapper/Service 不起容器，验证 ds 解析与上限截断、
 * 单 ds 失败静默跳过不中断、命中投影计数、台账 upsert 委派、投影 cap 截断（设计稿 §7.2）。
 * 三类信号的判定边界已由 {@code MetaPatrolScannerTest} 纯逻辑覆盖，此处不重复。
 */
class MetaPatrolServiceTest {

    private DataSourceService dataSourceService;
    private SchemaProvider schemaProvider;
    private SemanticMetaMapper semanticMetaMapper;
    private MetricMapper metricMapper;
    private GovernanceIssueLedgerService ledger;
    private MetaPatrolService service;

    @BeforeEach
    void setUp() {
        dataSourceService = mock(DataSourceService.class);
        DataSourceAdapterRegistry registry = mock(DataSourceAdapterRegistry.class);
        schemaProvider = mock(SchemaProvider.class);
        semanticMetaMapper = mock(SemanticMetaMapper.class);
        metricMapper = mock(MetricMapper.class);
        ledger = mock(GovernanceIssueLedgerService.class);
        when(registry.get(any(DataSourceType.class))).thenReturn(mock(DataSourceAdapter.class));
        service = new MetaPatrolService(dataSourceService, registry, schemaProvider,
                semanticMetaMapper, metricMapper, ledger);
    }

    // ------------------------------------------------------------------
    // 构造小工具
    // ------------------------------------------------------------------

    private static DataSourceConfig config(Long id) {
        return new DataSourceConfig(id, 1L, "测试源", DataSourceType.MYSQL, "localhost", 3306,
                "db", "u", "p", null, Map.of(), List.of(), false, null);
    }

    private static DataSource ds(Long id) {
        DataSource d = new DataSource();
        d.setId(id);
        d.setStatus(1);
        return d;
    }

    private static SemanticMeta col(Long ds, String table, String column, String alias, String desc) {
        SemanticMeta m = new SemanticMeta();
        m.setDatasourceId(ds);
        m.setObjectType("COLUMN");
        m.setTableName(table);
        m.setColumnName(column);
        m.setAliasCn(alias);
        m.setDescription(desc);
        m.setEnabled(1);
        return m;
    }

    private static TableMeta table(String name, String... cols) {
        List<TableMeta.ColumnMeta> cs = new ArrayList<>();
        for (String c : cols) {
            cs.add(new TableMeta.ColumnMeta(c, "int", null));
        }
        return new TableMeta(name, null, cs);
    }

    /** 让指定 ds 走通：schema 含 orders(id, amount)，语义行为 ghost 表幽灵行 + orders.id 正常行。 */
    private void stubHappyGhostDs(long dsId) {
        when(dataSourceService.loadConfig(dsId)).thenReturn(config(dsId));
        when(schemaProvider.getTables(any(), any())).thenReturn(List.of(table("orders", "id", "amount")));
        when(semanticMetaMapper.selectList(any())).thenReturn(List.of(
                col(dsId, "ghost_tbl", "x", "幽灵列", "说明"),   // 表不在 schema → BROKEN
                col(dsId, "orders", "id", "订单号", "主键")));    // 正常 → 放行
        when(metricMapper.selectList(any())).thenReturn(List.<Metric>of());
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("指定 ds：命中投影计数正确，且全量委派台账 upsertSeen")
    void patrolSingleDatasourceHitsAndUpserts() {
        stubHappyGhostDs(9L);

        MetaPatrolResult r = service.patrol(9L);

        assertEquals(1, r.scannedDatasources());
        assertEquals(0, r.failedDatasources());
        assertEquals(1, r.broken(), "幽灵表行应命中 1 条 BROKEN");
        assertEquals(0, r.diffDef());
        assertEquals(0, r.noComment());
        GovernanceIssue g = r.issues().get(0);
        assertEquals(GovernanceSignal.META_LINEAGE_BROKEN, g.signal());
        assertEquals("BROKEN|9|ghost_tbl", g.normalizedKey());
        verify(ledger).upsertSeen(eq(r.issues()), eq(9L));
    }

    @Test
    @DisplayName("单 ds schema 拉取失败：跳过计数不中断，其余 ds 照常")
    void failedDatasourceSkippedNotAborted() {
        when(dataSourceService.list(any(Wrapper.class))).thenReturn(List.of(ds(1L), ds(2L)));
        when(dataSourceService.loadConfig(1L)).thenThrow(new RuntimeException("Connection refused"));
        stubHappyGhostDs(2L);

        MetaPatrolResult r = service.patrol(null);

        assertEquals(1, r.scannedDatasources(), "坏 ds 跳过后仍扫成 1 个");
        assertEquals(1, r.failedDatasources());
        assertEquals(1, r.broken(), "存活 ds 的命中不受失败 ds 影响");
        assertNull(r.note(), "未超上限不带 note");
    }

    @Test
    @DisplayName("未指定 ds：启用数据源超 5 个截断为 5 并带 note")
    void datasourceCapFiveWithNote() {
        when(dataSourceService.list(any(Wrapper.class))).thenReturn(
                List.of(ds(1L), ds(2L), ds(3L), ds(4L), ds(5L), ds(6L), ds(7L)));
        when(dataSourceService.loadConfig(anyLong())).thenThrow(new RuntimeException("skip fast"));

        MetaPatrolResult r = service.patrol(null);

        assertEquals(0, r.scannedDatasources());
        assertEquals(MetaPatrolService.MAX_DATASOURCES, r.failedDatasources(), "只尝试了前 5 个");
        verify(dataSourceService, times(MetaPatrolService.MAX_DATASOURCES)).loadConfig(anyLong());
        assertNotNull(r.note());
        assertTrue(r.note().contains("仅扫前 5 个"), "实际 note：" + r.note());
    }

    @Test
    @DisplayName("投影 cap 100：命中 101 条 DIFFDEF 时全量落台账（upsert 不收 cap），响应截断 100")
    void issueProjectionCapHundred() {
        when(dataSourceService.loadConfig(9L)).thenReturn(config(9L));
        // 101 张表各含 c1/c2，别名冲突每表 1 条 DIFFDEF → 101 条，无 BROKEN/NO_COMMENT
        List<TableMeta> tables = new ArrayList<>();
        List<SemanticMeta> metas = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            String t = "tf" + i;
            tables.add(table(t, "c1", "c2"));
            metas.add(col(9L, t, "c1", "销售" + i, "甲口径"));
            metas.add(col(9L, t, "c2", "销售" + i, "乙口径"));
        }
        when(schemaProvider.getTables(any(), any())).thenReturn(tables);
        when(semanticMetaMapper.selectList(any())).thenReturn(metas);
        when(metricMapper.selectList(any())).thenReturn(List.<Metric>of());

        MetaPatrolResult r = service.patrol(9L);

        assertEquals(101, r.diffDef(), "计数按全量命中");
        assertEquals(MetaPatrolService.ISSUE_CAP, r.issues().size(), "投影按 cap 100 截断");
        verify(ledger).upsertSeen(any(), eq(9L));
    }

    @Test
    @DisplayName("无数据源可扫（租户未启用任何 ds）：空结果不炸，upsert 空列表照常委派")
    void noDatasourceEmptyResult() {
        when(dataSourceService.list(any(Wrapper.class))).thenReturn(List.<DataSource>of());

        MetaPatrolResult r = service.patrol(null);

        assertEquals(0, r.scannedDatasources());
        assertTrue(r.issues().isEmpty());
    }
}
