package com.aibi.module.chat.distill;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 台账服务（T11-1 二期 B）纯逻辑单测：mock mapper，不连库。
 * 覆盖设计稿 §9 用例：upsert 复活保护、attachStatus 回填、pendingNotifiable 去重、reopen 清空。
 */
class GovernanceIssueLedgerServiceTest {

    private GovernanceLedgerMapper mapper;
    private GovernanceIssueLedgerService service;

    @BeforeEach
    void setUp() {
        AccessContextHolder.set(AccessContext.builder().tenantId(1L).build());
        mapper = mock(GovernanceLedgerMapper.class);
        service = new GovernanceIssueLedgerService(mapper);
    }

    @AfterEach
    void tearDown() {
        AccessContextHolder.clear();
    }

    // ==================== sha256 ====================

    @Test
    @DisplayName("sha256 确定性：同串同结果，异串异结果")
    void sha256Deterministic() {
        String h1 = GovernanceIssueLedgerService.sha256("hello");
        String h2 = GovernanceIssueLedgerService.sha256("hello");
        String h3 = GovernanceIssueLedgerService.sha256("world");
        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
        assertEquals(64, h1.length());
    }

    @Test
    @DisplayName("sha256 null/空 → 64 个 0")
    void sha256Empty() {
        String h = GovernanceIssueLedgerService.sha256(null);
        assertEquals("0".repeat(64), h);
    }

    // ==================== upsertSeen ====================

    @Test
    @DisplayName("upsertSeen 新簇 INSERT OPEN")
    void upsertSeenInsertsNew() {
        GovernanceIssue issue = makeIssue(GovernanceSignal.FAILED_HIGH, "testKey", 9L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        service.upsertSeen(List.of(issue), 9L);
        verify(mapper).insert(any(GovernanceLedgerEntry.class));
    }

    @Test
    @DisplayName("upsertSeen 已 CONVERTED 行被再次命中只更新 lastSeenAt，不回退 OPEN")
    void upsertSeenDoesNotRevertConverted() {
        GovernanceIssue issue = makeIssue(GovernanceSignal.FAILED_HIGH, "testKey", 9L);
        GovernanceLedgerEntry existing = new GovernanceLedgerEntry();
        existing.setId(100L);
        existing.setTenantId(1L);
        existing.setDatasourceId(9L);
        existing.setSignal("FAILED_HIGH");
        existing.setKeyHash(GovernanceIssueLedgerService.sha256("testKey"));
        existing.setStatus("CONVERTED");
        existing.setRefType("METRIC");
        existing.setRefId(42L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        service.upsertSeen(List.of(issue), 9L);
        verify(mapper, never()).insert(any());
        verify(mapper).updateById(existing);
        // status 仍为 CONVERTED（未回退）
        assertEquals("CONVERTED", existing.getStatus());
    }

    // ==================== attachStatus ====================

    @Test
    @DisplayName("attachStatus 对台账已有行正确回填 ledgerId/status/ref")
    void attachStatusPopulatesLedgerFields() {
        GovernanceIssue issue = makeIssue(GovernanceSignal.FAILED_HIGH, "testKey", 9L);
        GovernanceLedgerEntry entry = new GovernanceLedgerEntry();
        entry.setId(55L);
        entry.setStatus("CONVERTED");
        entry.setRefType("METRIC");
        entry.setRefId(7L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entry);
        List<GovernanceIssue> result = service.attachStatus(List.of(issue), 9L);
        assertEquals(1, result.size());
        GovernanceIssue enriched = result.get(0);
        assertEquals(55L, enriched.ledgerId());
        assertEquals("CONVERTED", enriched.status());
        assertEquals("METRIC", enriched.refType());
        assertEquals(7L, enriched.refId());
    }

    // ==================== pendingNotifiable ====================

    @Test
    @DisplayName("pendingNotifiable 返回待推送行（OPEN + notifiedAt null/old）")
    void pendingNotifiableReturnsOpen() {
        GovernanceLedgerEntry e = new GovernanceLedgerEntry();
        e.setId(1L);
        e.setStatus("OPEN");
        e.setLastSeenAt(LocalDateTime.now());
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(e));
        List<GovernanceLedgerEntry> pending = service.pendingNotifiable();
        assertEquals(1, pending.size());
    }

    @Test
    @DisplayName("pendingNotifiable 无上下文时返回空列表")
    void pendingNotifiableNoContext() {
        AccessContextHolder.clear();
        List<GovernanceLedgerEntry> pending = service.pendingNotifiable();
        assertTrue(pending.isEmpty());
    }

    // ==================== reopen ====================

    @Test
    @DisplayName("reopen 清空 status→OPEN、ref/resolved/notified")
    void reopenClearsAll() {
        GovernanceLedgerEntry entry = new GovernanceLedgerEntry();
        entry.setId(99L);
        entry.setStatus("DISMISSED");
        entry.setRefType("FEWSHOT");
        entry.setRefId(3L);
        entry.setResolvedAt(LocalDateTime.now());
        entry.setResolvedBy(10L);
        entry.setNotifiedAt(LocalDateTime.now());
        when(mapper.selectById(99L)).thenReturn(entry);
        service.reopen(99L, 10L);
        assertEquals("OPEN", entry.getStatus());
        assertNull(entry.getRefType());
        assertNull(entry.getRefId());
        assertNull(entry.getResolvedAt());
        assertNull(entry.getResolvedBy());
        assertNull(entry.getNotifiedAt());
        verify(mapper).updateById(entry);
    }

    @Test
    @DisplayName("reopen null id 不操作")
    void reopenNullId() {
        service.reopen(null, 1L);
        verify(mapper, never()).updateById(any());
    }

    // ==================== 巡检行读侧投影（T11-4） ====================

    private GovernanceLedgerEntry metaRow(Long id, String signal, Long ds, String key, String question,
                                          int occurrences, String status) {
        GovernanceLedgerEntry e = new GovernanceLedgerEntry();
        e.setId(id);
        e.setTenantId(1L);
        e.setDatasourceId(ds);
        e.setSignal(signal);
        e.setNormalizedKey(key);
        e.setQuestion(question);
        e.setOccurrences(occurrences);
        e.setStatus(status);
        return e;
    }

    @Test
    @DisplayName("listMetaPatrol：META_* 台账行投影为 GovernanceIssue，按信号优先级排序、处置态透传")
    void listMetaPatrolProjectsAndSorts() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                metaRow(3L, "META_NO_COMMENT", 9L, "NOCOM|9|t1", "表 t1 裸列", 7, "OPEN"),
                metaRow(1L, "META_LINEAGE_BROKEN", 9L, "BROKEN|9|ghost", "幽灵表", 1, "DISMISSED"),
                metaRow(2L, "META_SAME_NAME_DIFF_DEF", 9L, "DIFFDEF|9|ALIAS|销售", "别名冲突", 2, "OPEN")));

        List<GovernanceIssue> out = service.listMetaPatrol(true);

        assertEquals(3, out.size());
        assertEquals(GovernanceSignal.META_LINEAGE_BROKEN, out.get(0).signal(), "BROKEN 排最前");
        assertEquals(GovernanceSignal.META_SAME_NAME_DIFF_DEF, out.get(1).signal());
        assertEquals(GovernanceSignal.META_NO_COMMENT, out.get(2).signal());
        GovernanceIssue first = out.get(0);
        assertEquals(1L, first.ledgerId());
        assertEquals("DISMISSED", first.status(), "includeHandled=true 时处置态透传");
        assertEquals("meta_patrol", first.skill());
        assertEquals(7, out.get(2).occurrences());
        assertEquals(9L, out.get(2).jumpAnchor().get("datasourceId"));
        assertNull(first.sampleSql());
    }

    @Test
    @DisplayName("listMetaPatrol：非 META 行（防御）与未知信号跳过，不炸清单")
    void listMetaPatrolSkipsForeignRows() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                metaRow(5L, "THUMBS_DOWN", 9L, "k", "问数行不应由巡检口透出", 1, "OPEN"),
                metaRow(6L, "META_FUTURE_SIGNAL", 9L, "k", "未来回滚残留", 1, "OPEN"),
                metaRow(7L, "META_NO_COMMENT", 9L, "NOCOM|9|ok", "正常行", 2, "OPEN")));

        List<GovernanceIssue> out = service.listMetaPatrol(false);

        assertEquals(1, out.size(), "仅巡检三值透出");
        assertEquals(7L, out.get(0).ledgerId());
    }

    // ==================== 辅助 ====================

    private GovernanceIssue makeIssue(GovernanceSignal signal, String key, Long ds) {
        return new GovernanceIssue(signal, "测试问句", key, 5,
                "nl2sql", null, "建议", null, false,
                Map.of("datasourceId", ds),
                null, "OPEN", null, null);
    }
}
