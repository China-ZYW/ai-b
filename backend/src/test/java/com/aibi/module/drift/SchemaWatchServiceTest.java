package com.aibi.module.drift;

import com.aibi.module.alert.entity.AlertRule;
import com.aibi.module.alert.mapper.AlertRuleMapper;
import com.aibi.module.chat.distill.GovernanceIssue;
import com.aibi.module.chat.distill.GovernanceIssueLedgerService;
import com.aibi.module.chat.distill.GovernanceSignal;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.service.DataSourceService;
import com.aibi.module.drift.entity.SchemaSnapshot;
import com.aibi.module.drift.mapper.SchemaSnapshotMapper;
import com.aibi.module.message.entity.NotifyMessage;
import com.aibi.module.message.mapper.NotifyMessageMapper;
import com.aibi.module.query.mapper.SavedQueryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SchemaWatchService} 编排单测（T12-3 K2）：首轮建基准 / 无变化 / 删列命中预警链 /
 * 无影响静默 / 台账失败不拦推送 / 预警规则反挂。
 */
@ExtendWith(MockitoExtension.class)
class SchemaWatchServiceTest {

    @Mock
    private DataSourceService dataSourceService;
    @Mock
    private SchemaSnapshotMapper snapshotMapper;
    @Mock
    private SavedQueryMapper savedQueryMapper;
    @Mock
    private MetricMapper metricMapper;
    @Mock
    private AlertRuleMapper alertRuleMapper;
    @Mock
    private GovernanceIssueLedgerService ledger;
    @Mock
    private NotifyMessageMapper notifyMessageMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SchemaWatchService service;

    @BeforeEach
    void setUp() {
        service = new SchemaWatchService(dataSourceService, snapshotMapper, savedQueryMapper,
                metricMapper, alertRuleMapper, ledger, notifyMessageMapper, objectMapper);
    }

    /** 本轮 schema：orders 表已无 city 列。 */
    private List<TableMeta> currentWithoutCity() {
        return List.of(new TableMeta("orders", null, List.of(
                new TableMeta.ColumnMeta("id", "int", null),
                new TableMeta.ColumnMeta("amount", "decimal", null))));
    }

    /** 上轮快照：orders 表含 city。 */
    private SchemaSnapshot snapshotWithCity() {
        SchemaSnapshot s = new SchemaSnapshot();
        s.setId(1L);
        s.setDatasourceId(9L);
        s.setSchemaJson("{\"orders\":[\"amount\",\"city\",\"id\"]}");
        return s;
    }

    private Metric brokenMetric() {
        Metric m = new Metric();
        m.setId(5L);
        m.setName("月度销售额");
        m.setUid(42L);
        m.setSqlText("SELECT city, SUM(amount) AS total FROM orders GROUP BY city");
        return m;
    }

    @Test
    @DisplayName("首轮：无快照只建基准，不比对、不反查资产")
    void firstRoundBuildsBaseline() {
        when(dataSourceService.scanSchemaRaw(9L)).thenReturn(currentWithoutCity());
        when(snapshotMapper.selectOne(any())).thenReturn(null);

        SchemaWatchService.WatchResult r = service.watch(9L);

        assertTrue(r.baselineCreated());
        ArgumentCaptor<SchemaSnapshot> cap = ArgumentCaptor.forClass(SchemaSnapshot.class);
        verify(snapshotMapper).insert(cap.capture());
        assertEquals(9L, cap.getValue().getDatasourceId());
        assertTrue(cap.getValue().getSchemaJson().contains("orders"));
        verify(savedQueryMapper, never()).selectList(any());
        verify(notifyMessageMapper, never()).insert(any(NotifyMessage.class));
    }

    @Test
    @DisplayName("无变化：滚动快照、静默返回，不碰资产表")
    void noChangeQuiet() {
        when(dataSourceService.scanSchemaRaw(9L)).thenReturn(List.of(new TableMeta("orders", null,
                List.of(new TableMeta.ColumnMeta("id", "int", null)))));
        SchemaSnapshot snap = new SchemaSnapshot();
        snap.setId(1L);
        snap.setDatasourceId(9L);
        snap.setSchemaJson("{\"orders\":[\"id\"]}");
        when(snapshotMapper.selectOne(any())).thenReturn(snap);

        SchemaWatchService.WatchResult r = service.watch(9L);

        assertEquals("结构无变化", r.note());
        assertTrue(r.impacts().isEmpty());
        verify(snapshotMapper).updateById(snap);
        verify(savedQueryMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("删列命中指标引用：台账入账 + 站内信定向创建人 + 快照滚动")
    void columnDropNotifiesOwner() {
        when(dataSourceService.scanSchemaRaw(9L)).thenReturn(currentWithoutCity());
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotWithCity());
        when(savedQueryMapper.selectList(any())).thenReturn(List.of());
        when(metricMapper.selectList(any())).thenReturn(List.of(brokenMetric()));
        when(alertRuleMapper.selectList(any())).thenReturn(List.of());

        SchemaWatchService.WatchResult r = service.watch(9L);

        assertEquals(1, r.impacts().size());
        assertTrue(r.impacts().get(0).brokenRefs().get(0).contains("city"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GovernanceIssue>> issues = ArgumentCaptor.forClass(List.class);
        verify(ledger).upsertSeen(issues.capture(), eq(9L));
        assertEquals(GovernanceSignal.META_SCHEMA_CHANGED, issues.getValue().get(0).signal());
        assertEquals("WATCH|METRIC|5", issues.getValue().get(0).normalizedKey());
        ArgumentCaptor<NotifyMessage> msg = ArgumentCaptor.forClass(NotifyMessage.class);
        verify(notifyMessageMapper).insert(msg.capture());
        assertEquals(42L, msg.getValue().getUid(), "定向受影响指标创建人");
        assertTrue(msg.getValue().getContent().contains("月度销售额"));
        assertEquals(1, r.notifiedUsers());
        verify(snapshotMapper).updateById(any(SchemaSnapshot.class));
    }

    @Test
    @DisplayName("有删除但无受影响资产：不入账不推送，note 说明")
    void dropsWithoutImpact() {
        when(dataSourceService.scanSchemaRaw(9L)).thenReturn(currentWithoutCity());
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotWithCity());
        when(savedQueryMapper.selectList(any())).thenReturn(List.of());
        when(metricMapper.selectList(any())).thenReturn(List.of());

        SchemaWatchService.WatchResult r = service.watch(9L);

        assertTrue(r.impacts().isEmpty());
        assertEquals("有删除变更，但未发现受影响资产", r.note());
        verify(ledger, never()).upsertSeen(anyList(), anyLong());
        verify(notifyMessageMapper, never()).insert(any(NotifyMessage.class));
        verify(snapshotMapper).updateById(any(SchemaSnapshot.class));
    }

    @Test
    @DisplayName("台账入账失败 fail-soft：站内信与快照滚动不受拦")
    void ledgerFailureDoesNotBlock() {
        when(dataSourceService.scanSchemaRaw(9L)).thenReturn(currentWithoutCity());
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotWithCity());
        when(savedQueryMapper.selectList(any())).thenReturn(List.of());
        when(metricMapper.selectList(any())).thenReturn(List.of(brokenMetric()));
        when(alertRuleMapper.selectList(any())).thenReturn(List.of());
        doThrow(new RuntimeException("db down")).when(ledger).upsertSeen(anyList(), anyLong());

        SchemaWatchService.WatchResult r = service.watch(9L);

        assertEquals(1, r.notifiedUsers());
        verify(snapshotMapper).updateById(any(SchemaSnapshot.class));
    }

    @Test
    @DisplayName("坏指标挂着启用预警规则：明细追加规则引用提示")
    void alertRuleHintAttached() {
        AlertRule rule = new AlertRule();
        rule.setId(7L);
        rule.setName("销售额暴跌");
        rule.setMetricKey("metric:5");
        rule.setEnabled(true);
        when(dataSourceService.scanSchemaRaw(9L)).thenReturn(currentWithoutCity());
        when(snapshotMapper.selectOne(any())).thenReturn(snapshotWithCity());
        when(savedQueryMapper.selectList(any())).thenReturn(List.of());
        when(metricMapper.selectList(any())).thenReturn(List.of(brokenMetric()));
        when(alertRuleMapper.selectList(any())).thenReturn(List.of(rule));

        SchemaWatchService.WatchResult r = service.watch(9L);

        assertTrue(r.impacts().get(0).brokenRefs().stream()
                .anyMatch(s -> s.contains("销售额暴跌")), "明细含预警规则名");
    }
}
