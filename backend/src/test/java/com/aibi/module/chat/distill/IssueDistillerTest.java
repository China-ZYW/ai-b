package com.aibi.module.chat.distill;

import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.history.dto.FeedbackCorrection;
import com.aibi.module.history.entity.QueryHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 问题沉淀内核（T11-1）纯逻辑单测：不起 Spring、不连库，直接喂内存构造的 {@code query_history} 行验证五信号规则、
 * 归一化分组、动作映射、阈值/时间窗边界与开关短路（对应设计稿 §9 用例 1~9）。
 */
class IssueDistillerTest {

    private final IssueDistiller distiller = new IssueDistiller(null, null, new ObjectMapper(), null);

    private static DistillQuery q() {
        return new DistillQuery(1L, null, 30, 60, 3, 50);
    }

    private static QueryHistory row(String question, String status, String skill) {
        QueryHistory h = new QueryHistory();
        h.setQuestion(question);
        h.setStatus(status);
        h.setSkill(skill);
        h.setDatasourceId(9L);
        h.setCreatedAt(LocalDateTime.now());
        return h;
    }

    private static GovernanceIssue find(List<GovernanceIssue> issues, GovernanceSignal signal) {
        return issues.stream().filter(i -> i.signal() == signal).findFirst().orElse(null);
    }

    @Test
    @DisplayName("S1 高频失败：同归一键 FAILED ≥ minOccurrences 成簇，nl2sql 兜底失败建议沉淀指标/少样本")
    void failedHighCluster() {
        List<QueryHistory> rows = List.of(
                row("近30天销售额", "FAILED", "nl2sql"),
                row("近30天销售额", "FAILED", "nl2sql"),
                row("最近一个月销售额", "FAILED", "nl2sql"));
        rows.forEach(r -> r.setErrorMsg("Unknown column 'gmv'"));
        DistillReport rep = distiller.distill(rows, q());

        GovernanceIssue s1 = find(rep.issues(), GovernanceSignal.FAILED_HIGH);
        assertNotNull(s1, "应产出高频失败簇");
        assertEquals(3, s1.occurrences(), "两条不同问法归一到同键应合并计数");
        assertTrue(s1.suggestedAction().contains("指标") || s1.suggestedAction().contains("少样本"),
                "nl2sql 兜底失败应建议沉淀指标/少样本，实际：" + s1.suggestedAction());
    }

    @Test
    @DisplayName("S2 低质量分：OK 但 verify_json.score 低于阈值且高频 → 命中低分簇")
    void lowScoreCluster() {
        List<QueryHistory> rows = List.of(
                row("华东订单量", "OK", "nl2sql"),
                row("华东订单量", "OK", "nl2sql"),
                row("华东订单量", "OK", "nl2sql"));
        rows.forEach(r -> r.setVerifyJson("{\"answered\":true,\"score\":40}"));
        DistillReport rep = distiller.distill(rows, q());

        assertNotNull(find(rep.issues(), GovernanceSignal.LOW_SCORE), "低分簇应命中");
    }

    @Test
    @DisplayName("S3 用户点踩：feedback=-1 逐条即候选，优先级最高")
    void thumbsDownDirect() {
        QueryHistory down = row("退货率趋势", "OK", "metric");
        down.setFeedback(-1);
        DistillReport rep = distiller.distill(List.of(down), q());

        GovernanceIssue s3 = find(rep.issues(), GovernanceSignal.THUMBS_DOWN);
        assertNotNull(s3, "单条点踩即应成候选（不受 minOccurrences 限制）");
        assertEquals(0, s3.signal().priority(), "点踩优先级应最高（ordinal=0）");
        // 点踩 + 走指标直达 → 建议人工复核口径
        assertTrue(s3.suggestedAction().contains("复核") && s3.suggestedAction().contains("指标"),
                "metric 点踩建议复核指标口径，实际：" + s3.suggestedAction());
    }

    @Test
    @DisplayName("S4 反复歧义：同会话内同归一键出现 ≥2 次 → 歧义簇")
    void ambiguousWithinConversation() {
        // 两句措辞不同但归一到同键“北京销售额”，模拟用户在一个会话里反复换说法问同一件事
        List<QueryHistory> rows = List.of(
                row("近30天北京销售额", "OK", "nl2sql"),
                row("最近一个月北京销售额", "OK", "nl2sql"));
        rows.forEach(r -> r.setConversationId("conv-1"));
        // 高校验分，避免与 S2 混淆
        rows.forEach(r -> r.setVerifyJson("{\"score\":95}"));
        DistillReport rep = distiller.distill(rows, q());

        assertNotNull(find(rep.issues(), GovernanceSignal.AMBIGUOUS), "同会话反复问同一意图应判歧义");
    }

    @Test
    @DisplayName("归一化分组：'近30天销售额' 与 '最近一个月销售额' 归一键相同，数字/时间词被剥离")
    void normalizationCollapsesTimePhrases() {
        String k1 = IssueDistiller.normalizeKey("近30天销售额");
        String k2 = IssueDistiller.normalizeKey("最近一个月销售额");
        assertEquals(k1, k2, "两个时间措辞应归一到同簇");
        assertEquals("销售额", k1, "数字与时间量词应被剥离，仅留业务词");
        assertFalse(k1.contains("30") || k1.contains("天") || k1.contains("月"),
                "不应残留数字/时间单位");
    }

    @Test
    @DisplayName("阈值边界：出现次数 = minOccurrences-1 不成簇；= minOccurrences 成簇")
    void minOccurrencesBoundary() {
        List<QueryHistory> two = List.of(
                row("利润", "FAILED", "nl2sql"),
                row("利润", "FAILED", "nl2sql"));
        assertNullRow(distiller.distill(two, q()), GovernanceSignal.FAILED_HIGH, "2 次 < 阈值 3 不应成簇");

        List<QueryHistory> three = List.of(
                row("利润", "FAILED", "nl2sql"),
                row("利润", "FAILED", "nl2sql"),
                row("利润", "FAILED", "nl2sql"));
        assertNotNull(find(distiller.distill(three, q()).issues(), GovernanceSignal.FAILED_HIGH),
                "3 次 = 阈值应成簇");
    }

    private void assertNullRow(DistillReport rep, GovernanceSignal signal, String msg) {
        if (find(rep.issues(), signal) != null) {
            throw new AssertionError(msg);
        }
    }

    @Test
    @DisplayName("时间窗：created_at 早于窗口的失败不计入")
    void outOfWindowExcluded() {
        List<QueryHistory> rows = List.of(
                row("库存", "FAILED", "nl2sql"),
                row("库存", "FAILED", "nl2sql"),
                row("库存", "FAILED", "nl2sql"));
        LocalDateTime old = LocalDateTime.now().minusDays(60);
        rows.forEach(r -> r.setCreatedAt(old));
        DistillReport rep = distiller.distill(rows, q());

        assertTrue(rep.issues().isEmpty(), "60 天前的记录在 30 天窗内应被排除");
    }

    @Test
    @DisplayName("开关关闭：distillForTenant 短路返回空报告，不触碰数据库")
    void disabledReturnsEmpty() {
        AibiConfig cfg = mock(AibiConfig.class);
        when(cfg.issueDistillEnabled()).thenReturn(false);
        IssueDistiller off = new IssueDistiller(null, cfg, new ObjectMapper(), null);

        // datasourceId 与 mapper 都不会被使用（enabled=false 先短路）
        DistillReport rep = off.distillForTenant(null);
        assertTrue(rep.issues().isEmpty(), "开关关闭应返回空报告");
    }

    @Test
    @DisplayName("代价熔断（S5）：错误信息含熔断标记的单条即成 actionable 候选")
    void costBlockedSignal() {
        QueryHistory blocked = row("明细全表导出", "FAILED", "nl2sql");
        blocked.setErrorMsg("[CostGuard] 预估扫描行超预算，已熔断");
        DistillReport rep = distiller.distill(List.of(blocked), q());

        GovernanceIssue s5 = find(rep.issues(), GovernanceSignal.COST_BLOCKED);
        assertNotNull(s5, "熔断问法应单独成候选");
        assertTrue(s5.suggestedAction().contains("预聚合") || s5.suggestedAction().contains("指标"),
                "熔断建议建预聚合指标，实际：" + s5.suggestedAction());
    }

    @Test
    @DisplayName("二期 代表 SQL：失败簇无成功样本 → sampleSql 取失败草稿、hasSuccessSql=false、锚点带 historyId")
    void failedClusterSqlDraft() {
        List<QueryHistory> rows = List.of(
                row("近30天销售额", "FAILED", "nl2sql"),
                row("近30天销售额", "FAILED", "nl2sql"),
                row("近30天销售额", "FAILED", "nl2sql"));
        rows.forEach(r -> r.setErrorMsg("Unknown column 'gmv'"));
        rows.forEach(r -> r.setSqlText("SELECT gmv FROM sales"));
        rows.get(0).setId(101L);
        DistillReport rep = distiller.distill(rows, q());

        GovernanceIssue s1 = find(rep.issues(), GovernanceSignal.FAILED_HIGH);
        assertNotNull(s1, "应产出高频失败簇");
        assertEquals("SELECT gmv FROM sales", s1.sampleSql(), "失败簇应带回草稿 SQL 供人工校对");
        assertFalse(s1.hasSuccessSql(), "全失败簇无可信成功 SQL");
        assertEquals(101L, s1.jumpAnchor().get("historyId"), "跳转锚点应带 historyId");
    }

    @Test
    @DisplayName("二期 代表 SQL：簇内有 OK 行带 sql → 优先取可信成功 SQL、hasSuccessSql=true")
    void clusterPrefersSuccessSql() {
        // 同会话内两句归一到同键“北京销售额”→歧义簇；其中一行是 OK 且带成功 SQL，应优先取作可信草稿
        QueryHistory okWithSql = row("近30天北京销售额", "OK", "nl2sql");
        okWithSql.setSqlText("SELECT sum(amount) FROM orders WHERE region='北京'");
        okWithSql.setConversationId("conv-9");
        okWithSql.setVerifyJson("{\"score\":95}");
        okWithSql.setId(7L);
        QueryHistory again = row("最近一个月北京销售额", "OK", "nl2sql");
        again.setConversationId("conv-9");
        again.setVerifyJson("{\"score\":95}");
        DistillReport rep = distiller.distill(List.of(okWithSql, again), q());

        GovernanceIssue amb = find(rep.issues(), GovernanceSignal.AMBIGUOUS);
        assertNotNull(amb, "同会话反复问同一意图应判歧义");
        assertTrue(amb.hasSuccessSql(), "簇内有成功 SQL 应标为可信");
        assertNotNull(amb.sampleSql(), "应带回代表 SQL");
        assertTrue(amb.sampleSql().contains("sum(amount)"), "应优先取 OK 行的可信 SQL，实际：" + amb.sampleSql());
    }

    @Test
    @DisplayName("T11-2 点踩带修正建议：expected_sql/feedback_note 首个非空者带出到 userSuggestedSql/userNote")
    void thumbsDownCarriesUserCorrection() {
        QueryHistory down1 = row("退货率趋势", "OK", "metric");
        down1.setFeedback(-1); // 先踩但没填建议
        QueryHistory down2 = row("退货率趋势", "OK", "metric");
        down2.setFeedback(-1);
        down2.setExpectedSql("SELECT count(*) FROM returns / count(*) FROM orders");
        down2.setFeedbackNote("退货率应为退货单数/总单数，不是金额占比");
        DistillReport rep = distiller.distill(List.of(down1, down2), q());

        GovernanceIssue s3 = find(rep.issues(), GovernanceSignal.THUMBS_DOWN);
        assertNotNull(s3, "单条点踩即应成候选");
        assertEquals("SELECT count(*) FROM returns / count(*) FROM orders", s3.userSuggestedSql(),
                "簇内首个非空期望 SQL 应带出");
        assertEquals("退货率应为退货单数/总单数，不是金额占比", s3.userNote(),
                "簇内首个非空口径说明应带出");
    }

    @Test
    @DisplayName("T11-2 点踩无建议：userSuggestedSql/userNote 均为 null，一期行为不回归")
    void thumbsDownWithoutCorrection() {
        QueryHistory down = row("退货率趋势", "OK", "metric");
        down.setFeedback(-1);
        DistillReport rep = distiller.distill(List.of(down), q());

        GovernanceIssue s3 = find(rep.issues(), GovernanceSignal.THUMBS_DOWN);
        assertNotNull(s3);
        assertTrue(s3.userSuggestedSql() == null && s3.userNote() == null,
                "未采集时两个建议字段应为 null");
    }

    @Test
    @DisplayName("T11-2 兼容构造：14 参老式创建 GovernanceIssue 时建议字段默认 null")
    void legacyConstructorDefaultsNull() {
        GovernanceIssue issue = new GovernanceIssue(GovernanceSignal.THUMBS_DOWN, "q", "k", 1,
                "metric", "err", "act", "sql", true, null, null, "OPEN", null, null);
        assertTrue(issue.userSuggestedSql() == null && issue.userNote() == null,
                "兼容构造器应委派 null 建议");
    }

    @Test
    @DisplayName("T11-2 FeedbackCorrection.hasContent：全空/空白为 false，任一有值为 true")
    void correctionHasContent() {
        assertFalse(new FeedbackCorrection(null, null, null).hasContent());
        assertFalse(new FeedbackCorrection("  ", "", null).hasContent(), "空白/空串不算内容");
        assertTrue(new FeedbackCorrection(null, "口径不对", null).hasContent());
        assertTrue(new FeedbackCorrection("SELECT 1", null, null).hasContent());
    }
}
