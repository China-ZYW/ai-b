package com.aibi.module.report.service;

import com.aibi.common.exception.BizException;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.message.entity.NotifyMessage;
import com.aibi.module.message.mapper.NotifyMessageMapper;
import com.aibi.module.query.dto.QueryDtos;
import com.aibi.module.query.entity.SavedQuery;
import com.aibi.module.query.mapper.SavedQueryMapper;
import com.aibi.module.query.service.QueryService;
import com.aibi.module.report.dto.ReportDtos;
import com.aibi.module.report.entity.ScheduledReport;
import com.aibi.module.report.mapper.ScheduledReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表编排（T12-1 K2）单测：mock Mapper/Service 不起容器（同 {@code MetaPatrolServiceTest} 风格）。
 * 覆盖设计稿验收 3 的四条：SKIPPED 分支、INAPP 收件人并集、EMAIL 未配置降级不炸、回写三字段；
 * 到期判定/渲染边界已由 K1 纯逻辑单测覆盖，此处不重复。
 */
class ReportServiceTest {

    private ScheduledReportMapper reportMapper;
    private SavedQueryMapper savedQueryMapper;
    private QueryService queryService;
    private NotifyMessageMapper messageMapper;
    private ReportMailSender mailSender;
    private ReportService service;

    private static final LocalDateTime RUN_AT = LocalDateTime.of(2026, 10, 26, 9, 0);

    @BeforeEach
    void setUp() {
        reportMapper = mock(ScheduledReportMapper.class);
        savedQueryMapper = mock(SavedQueryMapper.class);
        queryService = mock(QueryService.class);
        messageMapper = mock(NotifyMessageMapper.class);
        mailSender = mock(ReportMailSender.class);
        service = new ReportService(reportMapper, savedQueryMapper, queryService,
                messageMapper, mailSender, new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 构造小工具
    // ------------------------------------------------------------------

    private static ScheduledReport report(String channel) {
        ScheduledReport r = new ScheduledReport();
        r.setId(7L);
        r.setTenantId(1L);
        r.setUid(1L);
        r.setName("日销报表");
        r.setTemplateId(9L);
        r.setFreq("HOURLY");
        r.setChannel(channel);
        r.setEnabled(true);
        r.setCreatedAt(RUN_AT.minusDays(1));
        return r;
    }

    private static SavedQuery template() {
        SavedQuery t = new SavedQuery();
        t.setId(9L);
        t.setTenantId(1L);
        t.setUid(1L);
        t.setName("销售模板");
        t.setSqlTemplate("SELECT 1");
        return t;
    }

    private static QueryDtos.QueryRunVO vo(int rows) {
        List<List<Object>> data = new java.util.ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            data.add(List.of(i));
        }
        QueryResult result = QueryResult.of("SELECT 1", List.of("n"), data, rows, 5);
        return new QueryDtos.QueryRunVO(result, null, "direct_sql", false, 9L);
    }

    private void stubHappyPath() {
        when(savedQueryMapper.selectById(9L)).thenReturn(template());
        when(queryService.runTemplate(eq(9L), any())).thenReturn(vo(2));
    }

    /** 捕获回写瘦对象的三字段。 */
    private ScheduledReport capturedWriteBack() {
        ArgumentCaptor<ScheduledReport> cap = ArgumentCaptor.forClass(ScheduledReport.class);
        verify(reportMapper).updateById(cap.capture());
        return cap.getValue();
    }

    // ------------------------------------------------------------------

    @Test
    void templateDeletedSkipsWithoutDelivery() {
        when(savedQueryMapper.selectById(9L)).thenReturn(null);
        String status = service.runOnce(report("BOTH"), RUN_AT);

        assertEquals(ReportService.STATUS_SKIPPED, status);
        // SKIPPED 不外发：两个渠道都没碰
        verify(messageMapper, never()).insert(any(NotifyMessage.class));
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
        ScheduledReport upd = capturedWriteBack();
        assertEquals(ReportService.STATUS_SKIPPED, upd.getLastStatus());
        assertEquals(RUN_AT, upd.getLastRunAt());
        assertTrue(upd.getLastError().contains("模板"));
    }

    @Test
    void templateOwnershipChangeSkips() {
        SavedQuery stolen = template();
        stolen.setUid(999L); // 模板易主（理论不该发生，防御核账）
        when(savedQueryMapper.selectById(9L)).thenReturn(stolen);
        assertEquals(ReportService.STATUS_SKIPPED, service.runOnce(report("INAPP"), RUN_AT));
        verify(queryService, never()).runTemplate(anyLong(), any());
    }

    @Test
    void inappRecipientsAreUidsUnionCreator() {
        stubHappyPath();
        ScheduledReport r = report("INAPP");
        r.setInappUids("2,3");
        String status = service.runOnce(r, RUN_AT);

        assertEquals(ReportService.STATUS_OK, status);
        // 收件人 = {2,3} ∪ 创建人 1 → 三条
        ArgumentCaptor<NotifyMessage> cap = ArgumentCaptor.forClass(NotifyMessage.class);
        verify(messageMapper, times(3)).insert(cap.capture());
        List<Long> uids = cap.getAllValues().stream().map(NotifyMessage::getUid).toList();
        assertTrue(uids.containsAll(List.of(1L, 2L, 3L)));
        NotifyMessage first = cap.getAllValues().get(0);
        assertEquals("INFO", first.getSeverity());
        // 报表不是告警：告警关联列必须置空（拍板 P3）
        org.junit.jupiter.api.Assertions.assertNull(first.getRuleId());
        org.junit.jupiter.api.Assertions.assertNull(first.getRecordId());
        assertTrue(first.getContent().contains("【定时报表】日销报表"));
    }

    @Test
    void emailUnavailableOnBothDegradesToInappOnly() {
        stubHappyPath();
        when(mailSender.available()).thenReturn(false);
        ScheduledReport r = report("BOTH");
        r.setEmailTargets("a@b.com");

        String status = service.runOnce(r, RUN_AT);
        // 渠道不可用 ≠ 整单失败：站内已达成 → OK + 注记（设计稿 §6-4）
        assertEquals(ReportService.STATUS_OK, status);
        verify(messageMapper, times(1)).insert(any(NotifyMessage.class));
        assertEquals("邮件渠道未配置，仅站内", capturedWriteBack().getLastError());
    }

    @Test
    void emailOnlyUnavailableFailsBecauseNothingDelivered() {
        stubHappyPath();
        when(mailSender.available()).thenReturn(false);
        ScheduledReport r = report("EMAIL");
        r.setEmailTargets("a@b.com");

        assertEquals(ReportService.STATUS_FAILED, service.runOnce(r, RUN_AT));
        assertTrue(capturedWriteBack().getLastError().contains("本次未投递"));
    }

    @Test
    void bothChannelEmailSendFailureStillOkWithNote() {
        stubHappyPath();
        when(mailSender.available()).thenReturn(true);
        org.mockito.Mockito.doThrow(new ReportMailSender.ReportMailException("SMTP 挂了"))
                .when(mailSender).send(anyString(), anyString(), anyString());
        ScheduledReport r = report("BOTH");
        r.setEmailTargets("a@b.com");

        // BOTH：站内成功即整单 OK，邮件失败折进注记
        assertEquals(ReportService.STATUS_OK, service.runOnce(r, RUN_AT));
        assertTrue(capturedWriteBack().getLastError().contains("邮件发送失败"));
    }

    @Test
    void queryFailureMarksFailedAndKeepsShortError() {
        when(savedQueryMapper.selectById(9L)).thenReturn(template());
        when(queryService.runTemplate(eq(9L), any())).thenThrow(new RuntimeException("数据源连接超时"));
        ScheduledReport r = report("INAPP");

        assertEquals(ReportService.STATUS_FAILED, service.runOnce(r, RUN_AT));
        ScheduledReport upd = capturedWriteBack();
        assertEquals("数据源连接超时", upd.getLastError());
        // 执行都没成功不外发
        verify(messageMapper, never()).insert(any(NotifyMessage.class));
    }

    @Test
    void successClearsStaleErrorWithEmptyString() {
        stubHappyPath();
        service.runOnce(report("INAPP"), RUN_AT);
        // MP updateById 跳过 null——成功轮必须用空串显式清旧错
        assertEquals("", capturedWriteBack().getLastError());
    }

    // ------------------------------------------------------------------
    // 创建校验（闭集 + 条件必填 + 模板归属）
    // ------------------------------------------------------------------

    private static ReportDtos.ReportUpsertCmd cmd(String freq, String runTime, String weekdays,
                                                  String channel, String emailTargets) {
        return new ReportDtos.ReportUpsertCmd("日报", 9L, null, freq, runTime, weekdays,
                channel, null, emailTargets, true);
    }

    @Test
    void createRejectsIllegalConfig() {
        when(savedQueryMapper.selectById(9L)).thenReturn(template());
        assertThrows(BizException.class,
                () -> service.create(cmd("CRON", "09:00", null, "INAPP", null), 1L, 1L));
        assertThrows(BizException.class,
                () -> service.create(cmd("DAILY", "25:00", null, "INAPP", null), 1L, 1L));
        assertThrows(BizException.class,
                () -> service.create(cmd("WEEKLY", "09:00", null, "INAPP", null), 1L, 1L));
        assertThrows(BizException.class,
                () -> service.create(cmd("HOURLY", null, null, "EMAIL", null), 1L, 1L));
    }

    @Test
    void createRejectsTemplateNotOwnedByMe() {
        SavedQuery other = template();
        other.setUid(555L);
        when(savedQueryMapper.selectById(9L)).thenReturn(other);
        assertThrows(BizException.class,
                () -> service.create(cmd("DAILY", "09:00", null, "INAPP", null), 1L, 1L));
        verify(reportMapper, never()).insert(any(ScheduledReport.class));
    }

    @Test
    void createNormalizesHourlyAndClearsScheduleCols() {
        when(savedQueryMapper.selectById(9L)).thenReturn(template());
        service.create(cmd("HOURLY", "09:00", "1,2", "INAPP", null), 1L, 1L);
        ArgumentCaptor<ScheduledReport> cap = ArgumentCaptor.forClass(ScheduledReport.class);
        verify(reportMapper).insert(cap.capture());
        ScheduledReport saved = cap.getValue();
        // HOURLY 不带无意义的时刻/周几（防脏数据干扰判定）
        org.junit.jupiter.api.Assertions.assertNull(saved.getRunTime());
        org.junit.jupiter.api.Assertions.assertNull(saved.getWeekdays());
    }

    // ------------------------------------------------------------------
    // 相对时间参数（T12-1b）：执行时按锚点展开 + 创建入口 dry-run 拦截
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void relativeTimeParamsExpandedAtRunAtAnchor() {
        stubHappyPath();
        ScheduledReport r = report("INAPP");
        r.setParamsJson("{\"biz_date\":\"{{yesterday:yyyyMMdd}}\",\"city\":\"hangzhou\"}");

        assertEquals(ReportService.STATUS_OK, service.runOnce(r, RUN_AT));
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(queryService).runTemplate(eq(9L), cap.capture());
        // 锚点 RUN_AT=2026-10-26 → 昨天 20261025；固定值不受展开影响
        assertEquals("20261025", cap.getValue().get("biz_date"));
        assertEquals("hangzhou", cap.getValue().get("city"));
    }

    @Test
    void unknownTimeTokenFailsRunAndRejectedAtCreate() {
        stubHappyPath();
        ScheduledReport bad = report("INAPP");
        bad.setParamsJson("{\"d\":\"{{next_week}}\"}");
        // 执行链路：展开抛错折进 FAILED，宁停不错跑，且残缺参数绝不外发
        assertEquals(ReportService.STATUS_FAILED, service.runOnce(bad, RUN_AT));
        assertTrue(capturedWriteBack().getLastError().contains("next_week"));
        verify(messageMapper, never()).insert(any(NotifyMessage.class));
        // 创建入口：dry-run 当场拒，不等到夜里执行才暴露
        when(savedQueryMapper.selectById(9L)).thenReturn(template());
        ReportDtos.ReportUpsertCmd c = new ReportDtos.ReportUpsertCmd(
                "日报", 9L, "{\"d\":\"{{bad_token}}\"}", "DAILY", "09:00", null,
                "INAPP", null, null, true);
        assertThrows(BizException.class, () -> service.create(c, 1L, 1L));
    }
}
