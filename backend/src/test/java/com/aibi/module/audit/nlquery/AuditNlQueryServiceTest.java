package com.aibi.module.audit.nlquery;

import com.aibi.common.exception.BizException;
import com.aibi.common.response.PageResult;
import com.aibi.module.audit.dto.AuditLogVO;
import com.aibi.module.audit.dto.AuditQuery;
import com.aibi.module.audit.dto.OperationLogVO;
import com.aibi.module.audit.dto.OperationQuery;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.audit.service.OperationLogService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.history.entity.QueryHistory;
import com.aibi.module.history.mapper.QueryHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三源融合服务（T11-3 K2）纯逻辑单测：不起 Spring、不连库——三源检索全部 mock，
 * 钉死融合排序/截断、sources 分派、点名人解析失败返空不返全、OPS module 不进精确过滤、
 * QUERY 行展示名批量补齐与 result_json 快照摘要（含坏损 fail-soft）。
 */
class AuditNlQueryServiceTest {

    private AuditService auditService;
    private OperationLogService operationLogService;
    private QueryHistoryMapper queryHistoryMapper;
    private UserMapper userMapper;
    private TenantMapper tenantMapper;
    private LlmRouter llm;
    private AibiConfig aibiConfig;
    private AuditNlQueryService service;

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 25, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 25, 11, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 10, 25, 12, 0);

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        operationLogService = mock(OperationLogService.class);
        queryHistoryMapper = mock(QueryHistoryMapper.class);
        userMapper = mock(UserMapper.class);
        tenantMapper = mock(TenantMapper.class);
        llm = mock(LlmRouter.class);
        aibiConfig = mock(AibiConfig.class);
        when(aibiConfig.auditNlQueryEnabled()).thenReturn(true);
        when(aibiConfig.auditNlQueryModel()).thenReturn("");
        service = new AuditNlQueryService(auditService, operationLogService, queryHistoryMapper,
                userMapper, tenantMapper, new ObjectMapper(), llm, aibiConfig);
        // 默认：三源皆空、点名解析不到人（各用例按需覆盖）
        when(auditService.search(any())).thenReturn(PageResult.of(List.of(), 0, 200, 0));
        when(operationLogService.search(any())).thenReturn(PageResult.of(List.of(), 0, 200, 0));
        when(queryHistoryMapper.selectAuditNlProjection(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(userMapper.selectList(any())).thenReturn(List.of());
    }

    private static AuditQuerySlots slots(String username, String keyword, List<String> sources) {
        return new AuditQuerySlots(null, null, username, null, null, null, keyword, sources, null);
    }

    private static AuditLogVO auditVo(long id, LocalDateTime at, String action, String username) {
        return new AuditLogVO(id, 1L, "电商租户", 7L, username, null, "USER", action,
                "DATASOURCE", "3", null, null, "OK", null, "1.2.3.4", null, null, at);
    }

    private static OperationLogVO opsVo(long id, LocalDateTime at, String username, String uri) {
        return new OperationLogVO(id, 1L, "电商租户", 7L, username, null, null, "rid",
                "chat", "POST " + uri, "POST", uri, null, null, "1.2.3.4", null, 200, null, 35L, at);
    }

    private static QueryHistory history(long id, Long uid, String question, String resultJson) {
        QueryHistory h = new QueryHistory();
        h.setId(id);
        h.setTenantId(1L);
        h.setUid(uid);
        h.setQuestion(question);
        h.setStatus("OK");
        h.setSkill("metric");
        h.setRowCount(42);
        h.setElapsedMs(88L);
        h.setResultJson(resultJson);
        h.setCreatedAt(T3);
        return h;
    }

    private static User user(long id, String username, String nickname) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(nickname);
        return u;
    }

    // ==================== 融合装配 ====================

    @Test
    @DisplayName("三源行按时间倒序归并，来源标注正确")
    void fuseSortsAndLabels() {
        when(auditService.search(any())).thenReturn(
                PageResult.of(List.of(auditVo(1, T1, "LOGIN", "alice")), 1, 200, 0));
        when(operationLogService.search(any())).thenReturn(
                PageResult.of(List.of(opsVo(2, T3, "bob", "/api/chat/query")), 1, 200, 0));
        when(queryHistoryMapper.selectAuditNlProjection(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(history(3, 9L, "本月GMV", null)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(9, "nine", "小九")));

        List<AuditNlRow> rows = service.fuse(slots(null, null, null), 1L);
        assertEquals(3, rows.size());
        assertEquals("OPS", rows.get(0).source());   // T3
        assertEquals("QUERY", rows.get(1).source()); // T3（同刻按加入序，均在最前两位）
        assertEquals("AUDIT", rows.get(2).source()); // T1
        assertEquals("小九", rows.get(1).username());
        assertTrue(rows.get(1).summary().contains("本月GMV"));
    }

    @Test
    @DisplayName("sources 子集分派：只要 QUERY 就不碰审计两源")
    void sourcesDispatch() {
        service.fuse(slots(null, "订单", List.of("QUERY")), 1L);
        verify(auditService, never()).search(any());
        verify(operationLogService, never()).search(any());
        verify(queryHistoryMapper).selectAuditNlProjection(eq(1L), isNull(), isNull(), eq("订单"), isNull(), anyInt());
    }

    @Test
    @DisplayName("点名人解析失败：AUDIT/QUERY 返空（宁缺勿错），OPS 照常带 username LIKE")
    void personUnresolvedYieldsEmptyNotAll() {
        List<AuditNlRow> rows = service.fuse(slots("查无此人", null, null), 1L);
        verify(auditService, never()).search(any());
        assertTrue(rows.isEmpty());
        ArgumentCaptor<OperationQuery> cap = ArgumentCaptor.forClass(OperationQuery.class);
        verify(operationLogService).search(cap.capture());
        assertEquals("查无此人", cap.getValue().username());
    }

    @Test
    @DisplayName("点名解析多 uid：AUDIT 源逐 uid 查询归并（上限 5）")
    void auditMultiUidLoop() {
        when(userMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(user(11, "zhangsan", "张三"), user(12, "zhangsan2", "张三二")));
        List<AuditNlRow> rows = service.fuse(slots("张三", null, List.of("AUDIT")), 1L);
        verify(auditService, times(2)).search(any());
        assertTrue(rows.isEmpty()); // 两路都空，只验证调用次数与不抛
    }

    @Test
    @DisplayName("OPS 源：module 槽不进精确过滤（派生英文码必空），keyword 走 uri LIKE")
    void opsModuleNotExact() {
        service.fuse(new AuditQuerySlots(null, null, null, null, null, "数据查询", "订单",
                List.of("OPS"), null), 1L);
        ArgumentCaptor<OperationQuery> cap = ArgumentCaptor.forClass(OperationQuery.class);
        verify(operationLogService).search(cap.capture());
        assertNull(cap.getValue().module());
        assertEquals("订单", cap.getValue().uri());
    }

    @Test
    @DisplayName("融合行超过 ROW_CAP 截断")
    void rowCapTrims() {
        List<AuditLogVO> many = new ArrayList<>();
        for (int i = 0; i < AuditNlQueryService.ROW_CAP + 50; i++) {
            many.add(auditVo(i, T1.plusSeconds(i), "LOGIN", "u" + i));
        }
        when(auditService.search(any())).thenReturn(PageResult.of(many, many.size(), 200, 0));
        assertEquals(AuditNlQueryService.ROW_CAP,
                service.fuse(slots(null, null, List.of("AUDIT")), 1L).size());
    }

    // ==================== 快照摘要（拍板 P2） ====================

    @Test
    @DisplayName("快照摘要：行列数 + 前 3 列名 + 首行前 3 值")
    void summaryFromSnapshot() {
        QueryHistory h = history(1, 1L, "各区域销量",
                "{\"columns\":[\"区域\",\"销量\",\"金额\",\"日期\"],\"rows\":[\"华东\",120,9999.5,\"2026-10-25\"]}");
        h.setResultJson("{\"columns\":[\"区域\",\"销量\",\"金额\",\"日期\"],\"rows\":[[\"华东\",120,9999.5,\"2026-10-25\"]]}");
        String s = service.resultSummary(h);
        assertTrue(s.startsWith("共 42 行 × 4 列（区域，销量，金额…）"));
        assertTrue(s.contains("华东 | 120 | 9999.5"));
    }

    @Test
    @DisplayName("无快照/坏损 JSON/空列 → null（不重跑、不假装有摘要）")
    void summaryFailSoft() {
        assertNull(service.resultSummary(history(1, 1L, "q", null)));
        assertNull(service.resultSummary(history(1, 1L, "q", "{not json")));
        assertNull(service.resultSummary(history(1, 1L, "q", "{\"columns\":[]}")));
    }

    @Test
    @DisplayName("超长示例值截 32 字符加省略号")
    void summaryTruncatesValues() {
        QueryHistory h = history(1, 1L, "q",
                "{\"columns\":[\"备注\"],\"rows\":[[\"" + "长".repeat(40) + "\"]]}");
        String s = service.resultSummary(h);
        assertTrue(s.contains("长".repeat(32) + "…"));
    }

    // ==================== 平台域展示名 ====================

    @Test
    @DisplayName("平台域（tenantId=null）：QUERY 行批量补租户名；组织域不查租户表")
    void platformTenantNameFill() {
        QueryHistory h = history(5, 9L, "本月GMV", null);
        when(queryHistoryMapper.selectAuditNlProjection(isNull(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(h));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(9, "nine", null)));
        Tenant t = new Tenant();
        t.setId(1L);
        t.setName("电商公司");
        when(tenantMapper.selectBatchIds(any())).thenReturn(List.of(t));

        List<AuditNlRow> rows = service.fuse(slots(null, null, List.of("QUERY")), null);
        assertEquals("电商公司", rows.get(0).tenantName());
        assertEquals("nine", rows.get(0).username()); // 无昵称回落 username

        service.fuse(slots(null, null, List.of("QUERY")), 1L);
        verify(tenantMapper, times(1)).selectBatchIds(any()); // 组织域那次不调用
    }

    @Test
    @DisplayName("QUERY 行 uid 已注销：展示 #uid 而非空串")
    void deletedUserShowsPlaceholder() {
        when(queryHistoryMapper.selectAuditNlProjection(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(history(6, 404L, "查库存", null)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());
        List<AuditNlRow> rows = service.fuse(slots(null, null, List.of("QUERY")), 1L);
        assertEquals("#404", rows.get(0).username());
    }

    // ==================== 编排（K3）：门控/降级/规则时间盖写 ====================

    @Test
    @DisplayName("门控未启用：403 拒用，不碰 LLM")
    void gateOffRejects() {
        when(aibiConfig.auditNlQueryEnabled()).thenReturn(false);
        assertThrows(BizException.class, () -> service.nlQuery("今天谁查了订单", 1L));
        verify(llm, never()).chat(any());
    }

    @Test
    @DisplayName("LLM 正常 JSON → 槽位落地并执行融合")
    void llmJsonToSlots() {
        when(llm.chat(any())).thenReturn("{\"username\":\"alice\",\"action\":\"LOGIN_FAILED\","
                + "\"sources\":[\"AUDIT\"]}");
        AuditNlResult r = service.nlQuery("alice 登录失败", 1L);
        assertFalse(r.degraded());
        assertEquals("alice", r.slots().username());
        assertEquals("LOGIN_FAILED", r.slots().action());
        assertEquals(List.of("AUDIT"), r.slots().sources());
    }

    @Test
    @DisplayName("规则时间命中即盖写 LLM 时间槽（确定性优先）")
    void ruleTimeOverridesLlm() {
        when(llm.chat(any())).thenReturn("{\"timeFrom\":\"2020-01-01 00:00:00\","
                + "\"timeTo\":\"2020-01-01 23:59:59\",\"keyword\":\"订单\",\"sources\":[\"QUERY\"]}");
        AuditNlResult r = service.nlQuery("今天谁查了订单", 1L);
        String expectFrom = java.time.LocalDate.now().atStartOfDay()
                .format(AuditTimePhrases.TS);
        assertEquals(expectFrom, r.slots().timeFrom()); // LLM 臆造的 2020 被规则盖掉
        assertEquals("订单", r.slots().keyword());
    }

    @Test
    @DisplayName("LLM 异常/无配置 → degraded（不抛不 500）")
    void llmFailureDegrades() {
        when(llm.chat(any())).thenThrow(new BizException(
                com.aibi.common.exception.ErrorCode.SYSTEM_ERROR, "未配置 API Key"));
        AuditNlResult r = service.nlQuery("今天谁查了订单", 1L);
        assertTrue(r.degraded());
        assertNull(r.slots());
        assertEquals(0, r.total());
    }

    @Test
    @DisplayName("LLM 输出不含 JSON → degraded")
    void nonJsonOutputDegrades() {
        when(llm.chat(any())).thenReturn("抱歉，我无法解析这个问题。");
        assertTrue(service.nlQuery("随便看看", 1L).degraded());
    }

    @Test
    @DisplayName("指定模型未命中（chatWithModel 返 null）→ 回落默认链")
    void specifiedModelFallbackToDefaultChain() {
        when(aibiConfig.auditNlQueryModel()).thenReturn("gpt-nonexist");
        when(llm.chatWithModel(any(), eq("gpt-nonexist"))).thenReturn(null);
        when(llm.chat(any())).thenReturn("{\"keyword\":\"销售\"}");
        AuditNlResult r = service.nlQuery("谁查了销售数据", 1L);
        assertFalse(r.degraded());
        assertEquals("销售", r.slots().keyword());
        verify(llm).chat(any());
    }

    @Test
    @DisplayName("JSON 提取：围栏包裹/前后废话只取对象子串")
    void extractJsonObjectToleratesFence() {
        assertEquals("{\"a\":1}", AuditNlQueryService.extractJsonObject("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", AuditNlQueryService.extractJsonObject("结果是：{\"a\":1}，请查收"));
        assertNull(AuditNlQueryService.extractJsonObject("没有对象"));
        assertNull(AuditNlQueryService.extractJsonObject(null));
    }
}
