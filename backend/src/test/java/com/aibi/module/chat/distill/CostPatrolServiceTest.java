package com.aibi.module.chat.distill;

import com.aibi.common.tenant.PlanType;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成本巡检编排（T12-4）单测：不起 Spring、不连库——{@code QueryHistoryMapper}/{@code AibiConfig}/
 * {@code GovernanceIssueLedgerService} 全桩，验证取数口径透传、分组聚簇、台账 fail-soft 与结果摘要。
 */
class CostPatrolServiceTest {

    private QueryHistoryMapper historyMapper;
    private AibiConfig aibiConfig;
    private GovernanceIssueLedgerService ledger;
    private CostPatrolService service;

    @BeforeEach
    void setUp() {
        historyMapper = mock(QueryHistoryMapper.class);
        aibiConfig = mock(AibiConfig.class);
        ledger = mock(GovernanceIssueLedgerService.class);
        service = new CostPatrolService(historyMapper, aibiConfig, ledger);
        when(aibiConfig.costPatrolWindowDays()).thenReturn(7);
        when(aibiConfig.costPatrolSlowMs()).thenReturn(5000L);
        when(aibiConfig.costPatrolDupMinOccurrences()).thenReturn(5);
        when(aibiConfig.costPatrolMaterializeMinOccurrences()).thenReturn(3);
        when(aibiConfig.issueTopN()).thenReturn(50);
        TenantContext.set(1L, PlanType.FREE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static QueryHistory row(Long ds, String question, long elapsedMs) {
        QueryHistory r = new QueryHistory();
        r.setDatasourceId(ds);
        r.setQuestion(question);
        r.setStatus("OK");
        r.setElapsedMs(elapsedMs);
        r.setSqlText("SELECT 1");
        r.setSkill("nl2sql");
        return r;
    }

    @Test
    @DisplayName("窗口取数透传：since/tenant/ds 原样给 mapper，空历史短路不入台账")
    void emptyRowsShortCircuit() {
        when(historyMapper.selectForDistill(eq(1L), eq(9L), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of());
        CostPatrolResult res = service.patrol(9L);
        assertEquals(0, res.scannedRows());
        assertTrue(res.issues().isEmpty());
        assertNull(res.note());
        verify(ledger, never()).upsertSeen(any(), any());
    }

    @Test
    @DisplayName("又慢又高频簇：出物化候选、计数正确、命中行入台账（带 ds 锚点）")
    void materializeHitUpsertsLedger() {
        when(historyMapper.selectForDistill(anyLong(), any(), any(), anyInt())).thenReturn(List.of(
                row(9L, "近7天销售额", 6000), row(9L, "近7天销售额", 8000), row(9L, "近7天销售额", 7000)));
        CostPatrolResult res = service.patrol(9L);
        assertEquals(3, res.scannedRows());
        assertEquals(1, res.materialize());
        assertEquals(0, res.slow());
        assertEquals(0, res.dup());
        assertEquals(7, res.windowDays());
        assertNotNull(res.since());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GovernanceIssue>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledger).upsertSeen(cap.capture(), eq(9L));
        GovernanceIssue i = cap.getValue().get(0);
        assertEquals(GovernanceSignal.COST_PATROL_MATERIALIZE, i.signal());
        assertEquals(9L, i.jumpAnchor().get("datasourceId"));
    }

    @Test
    @DisplayName("未指定 ds：按行自带数据源分组各自聚簇，跨库同问法不互相稀释")
    void multiDatasourceGrouping() {
        when(historyMapper.selectForDistill(anyLong(), isNull(), any(), anyInt())).thenReturn(List.of(
                row(1L, "慢问法", 6000), row(1L, "慢问法", 6000),
                row(2L, "慢问法", 6000), row(2L, "慢问法", 6000),
                row(null, "脏行无ds", 9000)));
        CostPatrolResult res = service.patrol(null);
        // 两库各 2 次：avg 6000≥5000 且 occ 2 < 物化门槛 3 → 各出一条慢查询；无 ds 脏行剔除
        assertEquals(2, res.slow());
        assertEquals(2, res.issues().size());
        assertEquals(1L, res.issues().get(0).jumpAnchor().get("datasourceId"));
        assertEquals(2L, res.issues().get(1).jumpAnchor().get("datasourceId"));
    }

    @Test
    @DisplayName("台账写入失败不拦本次结果（fail-soft）")
    void ledgerFailureDoesNotBlockResult() {
        when(historyMapper.selectForDistill(anyLong(), any(), any(), anyInt())).thenReturn(List.of(
                row(9L, "慢问法", 6000), row(9L, "慢问法", 6000)));
        doThrow(new RuntimeException("db down")).when(ledger).upsertSeen(any(), any());
        CostPatrolResult res = service.patrol(9L);
        assertEquals(1, res.slow());
        assertEquals(1, res.issues().size());
    }

    @Test
    @DisplayName("达取数上限时 note 提示截断；未达上限 note 为空")
    void capNote() {
        // cap = max(50*20, 500) = 1000：喂满 cap 的快问快答行触发截断 note，同时验证一簇一行
        QueryHistory[] many = new QueryHistory[1000];
        for (int i = 0; i < 1000; i++) {
            many[i] = row(9L, "重复问法", 100);
        }
        when(historyMapper.selectForDistill(anyLong(), any(), any(), anyInt())).thenReturn(List.of(many));
        CostPatrolResult res = service.patrol(9L);
        assertNotNull(res.note());
        assertEquals(1, res.dup());
        // 一簇一行：1000 次同问法也只出一条建议（occurrences 记全量次数）
        assertEquals(1000, res.issues().get(0).occurrences());
    }
}
