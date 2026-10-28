package com.aibi.module.chat.nl2sql;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.semantic.service.SemanticService;
import com.aibi.module.semantic.service.TableRelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nl2SqlEngine 问数主链路与「准确率增强三代开关」的纯逻辑测试（重构任务清单 T3 第五组）。
 *
 * <p>不起 Spring、不连库、不调模型：引擎的 13 个协作者全部 mock，只验证<b>编排语义</b>——
 * 哪个开关触发哪条辅助链路、辅助产物在什么条件下被采纳/放弃、失败时主链路是否零侵入保留原 SQL。
 * 这些分支是问数链路最容易"配置开了但没生效 / 出了 bug 静默改坏结果"的地方。
 *
 * <p>{@link SqlValidator} 默认 mock 成回声（输入即输出），个别用例让它对特定 SQL 抛错以模拟非法；
 * {@link JoinGuard}/{@link JoinGraph} 为引擎内静态直调，无法 mock，用例 12-13 按真实规则构造（其契约本身已由 JoinGuardTest 覆盖）。
 */
class Nl2SqlEngineTest {

    private static final TableMeta ORDERS =
            new TableMeta("orders", "订单表", List.of(), List.of("id"), List.of());
    private static final TableMeta USERS =
            new TableMeta("users", "用户表", List.of(), List.of("id"), List.of());
    private static final SchemaSlice SLICE_ORDERS = new SchemaSlice(List.of(ORDERS), 1, false);
    private static final SchemaSlice SLICE_TWO = new SchemaSlice(List.of(ORDERS, USERS), 2, false);
    private static final DataSourceConfig CFG = new DataSourceConfig(
            1L, 1L, "ds", DataSourceType.MYSQL, "localhost", 3306, "db",
            "u", "p", null, null, null, false, null);
    private static final QueryResult ONE_ROW =
            QueryResult.of("SELECT 1", List.of("c"), List.of(List.of((Object) 1)), 1, 5);
    private static final QueryResult ZERO_ROW =
            QueryResult.of("SELECT 1", List.of("c"), List.of(), 0, 5);

    private final DataSourceAdapterRegistry registry = mock(DataSourceAdapterRegistry.class);
    private final DataSourceAdapter adapter = mock(DataSourceAdapter.class);
    private final SchemaProvider schemaProvider = mock(SchemaProvider.class);
    private final SchemaLinker schemaLinker = mock(SchemaLinker.class);
    private final IntentClassifier intentClassifier = mock(IntentClassifier.class);
    private final FewShotStore fewShotStore = mock(FewShotStore.class);
    private final PromptBuilder promptBuilder = mock(PromptBuilder.class);
    private final LlmRouter llm = mock(LlmRouter.class);
    private final SqlValidator sqlValidator = mock(SqlValidator.class);
    private final SqlRepairer sqlRepairer = mock(SqlRepairer.class);
    private final AibiConfig aibiConfig = mock(AibiConfig.class);
    private final SemanticService semanticService = mock(SemanticService.class);
    private final TableRelationService tableRelationService = mock(TableRelationService.class);
    private final DataMaskGate dataMaskGate = mock(DataMaskGate.class);

    /** 进度阶段收集（断言增强链路确实发生）；接口无抽象方法，不能 lambda，用匿名类 */
    private final List<String> stages = new ArrayList<>();
    private final ProgressListener listener = new ProgressListener() {
        @Override
        public void onStage(String stage, String detail) {
            stages.add(stage + "|" + detail);
        }
    };
    private ExecStats stats;
    private Nl2SqlEngine engine;

    @BeforeEach
    void setUp() {
        stats = new ExecStats();
        engine = new Nl2SqlEngine(registry, schemaProvider, schemaLinker, intentClassifier,
                fewShotStore, promptBuilder, llm, sqlValidator, sqlRepairer, aibiConfig,
                semanticService, tableRelationService, dataMaskGate, 10000L, 30);

        when(registry.get(any(DataSourceType.class))).thenReturn(adapter);
        when(schemaProvider.getTables(any(), any())).thenReturn(List.of(ORDERS));
        when(tableRelationService.relationsOf(any())).thenReturn(List.of());
        when(schemaLinker.link(any(), any(), any(), any())).thenReturn(SLICE_ORDERS);
        when(intentClassifier.classify(any())).thenReturn(Intent.AGGREGATION);
        when(fewShotStore.similar(any(), any(), anyInt(), any(), anyInt())).thenReturn(List.of());
        when(promptBuilder.build(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("PROMPT");
        // 默认：单次生成得到 SELECT 1；validate 回声；执行返回 1 行
        when(llm.chat(anyString())).thenReturn("SELECT 1");
        when(sqlValidator.validate(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(adapter.execute(any(), anyString(), anyLong(), anyInt())).thenReturn(ONE_ROW);
        // 辅助模型名留空（多数用例不需要辅助链路）
        when(aibiConfig.crossCheckModel()).thenReturn("");
        when(aibiConfig.aiAnalysisModel()).thenReturn("");
    }

    private QueryContext ctx(String question) {
        return new QueryContext(question, null, List.of(), listener, stats);
    }

    /** 最后一次执行实际落定的 SQL（修正/自修复重试场景下会有多次 execute 调用） */
    private String executedSql() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(adapter, org.mockito.Mockito.atLeastOnce())
                .execute(any(DataSourceConfig.class), captor.capture(), anyLong(), anyInt());
        var all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    // ==================== 主链路（所有增强开关关闭 = 原三代前行为） ====================

    @Test
    void mainPath_singleGeneration_noAuxCalls() {
        QueryResult r = engine.generate(CFG, ctx("各区域销售额"));
        assertEquals("SELECT 1", r.sql());
        assertEquals(1, stats.getSqlAttempts());
        verify(llm, times(1)).chat(anyString());
        verify(llm, never()).chatWithModel(anyString(), anyString());
        assertFalse(stats.isCrossChecked());
        assertFalse(stats.isRepaired());
    }

    @Test
    void mainPath_illegalSqlRejectedByValidator() {
        when(llm.chat(anyString())).thenReturn("DROP TABLE users");
        when(sqlValidator.validate(eq("DROP TABLE users")))
                .thenThrow(new BizException(ErrorCode.SQL_GENERATE_FAILED, "仅允许 SELECT 查询"));
        assertThrows(BizException.class, () -> engine.generate(CFG, ctx("删掉用户表")));
        // 生成非法 SQL 不应到达执行层
        verify(adapter, never()).execute(any(), anyString(), anyLong(), anyInt());
    }

    // ==================== V3：self-consistency 多路生成择优 ====================

    @Test
    void selfConsistency_majorityVotePicksDominantCandidate() {
        when(aibiConfig.selfConsistency()).thenReturn(true);
        when(aibiConfig.consistencySamples()).thenReturn(3);
        when(llm.chat(anyString())).thenReturn("SELECT a", "SELECT b", "SELECT a");

        engine.generate(CFG, ctx("问题"));
        assertEquals(3, stats.getConsistencyCandidates());
        assertEquals("SELECT a", executedSql()); // 2:1 多数胜出
        verify(llm, times(3)).chat(anyString());
    }

    @Test
    void selfConsistency_allCandidatesInvalid_fallsBackToSingle() {
        when(aibiConfig.selfConsistency()).thenReturn(true);
        when(aibiConfig.consistencySamples()).thenReturn(2);
        when(llm.chat(anyString())).thenReturn("BAD", "BAD", "SELECT ok");
        when(sqlValidator.validate(eq("BAD")))
                .thenThrow(new BizException(ErrorCode.SQL_GENERATE_FAILED, "解析失败"));

        engine.generate(CFG, ctx("问题"));
        assertEquals(0, stats.getConsistencyCandidates());
        assertEquals("SELECT ok", executedSql()); // 回落单次生成
        verify(llm, times(3)).chat(anyString());  // 2 候选 + 1 回落
    }

    // ==================== V5：辅助模型交叉校验（crossCheck） ====================

    @Test
    void crossCheck_adoptsAuxFixWhenDifferentAndValid() {
        when(aibiConfig.crossCheckEnabled()).thenReturn(true);
        when(aibiConfig.crossCheckModel()).thenReturn("aux");
        when(llm.chatWithModel(anyString(), eq("aux"))).thenReturn("SELECT fixed");

        engine.generate(CFG, ctx("问题"));
        assertEquals("SELECT fixed", executedSql());
        assertTrue(stats.isCrossChecked());
    }

    @Test
    void crossCheck_sameSqlAsCandidate_notAdopted() {
        when(aibiConfig.crossCheckEnabled()).thenReturn(true);
        when(aibiConfig.aiAnalysisModel()).thenReturn("aux"); // crossCheckModel 空 → 回落 analysisModel
        when(llm.chatWithModel(anyString(), eq("aux"))).thenReturn("SELECT 1");

        engine.generate(CFG, ctx("问题"));
        assertEquals("SELECT 1", executedSql());
        assertFalse(stats.isCrossChecked()); // 与候选相同视为无修正，不置 crossChecked
    }

    @Test
    void crossCheck_invalidAuxSql_keepsOriginal() {
        when(aibiConfig.crossCheckEnabled()).thenReturn(true);
        when(aibiConfig.crossCheckModel()).thenReturn("aux");
        when(llm.chatWithModel(anyString(), eq("aux"))).thenReturn("DELETE FROM t");
        when(sqlValidator.validate(eq("DELETE FROM t")))
                .thenThrow(new BizException(ErrorCode.SQL_GENERATE_FAILED, "仅允许 SELECT"));

        engine.generate(CFG, ctx("问题"));
        assertEquals("SELECT 1", executedSql()); // 修正版非法 → 主链路零侵入保留原 SQL
        assertFalse(stats.isCrossChecked());
    }

    @Test
    void crossCheck_noAuxModelConfigured_skipsWithoutCall() {
        when(aibiConfig.crossCheckEnabled()).thenReturn(true);
        // crossCheckModel 与 aiAnalysisModel 均为空（setUp 默认）

        engine.generate(CFG, ctx("问题"));
        verify(llm, never()).chatWithModel(anyString(), anyString());
        assertEquals("SELECT 1", executedSql());
    }

    // ==================== V5：双生成共识（cross-consistency，与 crossCheck 互斥优先） ====================

    @Test
    void crossConsistency_mainAuxAgree_highConfidence() {
        when(aibiConfig.crossConsistencyEnabled()).thenReturn(true);
        when(aibiConfig.crossCheckModel()).thenReturn("aux");
        when(llm.chatWithModel(anyString(), eq("aux"))).thenReturn("SELECT 1"); // 与主一致

        engine.generate(CFG, ctx("问题"));
        assertTrue(stats.isCrossConsistent());
        assertFalse(stats.isCrossChecked()); // 共识一致不置 crossChecked
        verify(llm, times(1)).chatWithModel(anyString(), anyString()); // 无需裁决
    }

    @Test
    void crossConsistency_disagree_judgeDecidesAndPicksAux() {
        when(aibiConfig.crossConsistencyEnabled()).thenReturn(true);
        when(aibiConfig.crossCheckModel()).thenReturn("aux");
        when(llm.chatWithModel(anyString(), eq("aux")))
                .thenReturn("SELECT B", "B：主候选漏了 GROUP BY，更合理"); // 第2次是裁决

        engine.generate(CFG, ctx("问题"));
        assertFalse(stats.isCrossConsistent());
        assertEquals("SELECT B", executedSql());
        verify(llm, times(2)).chatWithModel(anyString(), anyString());
    }

    @Test
    void crossConsistency_takesPrecedenceOverCrossCheck() {
        // 同时开启：走共识分支，绝不进入 crossCheck 单模型复核（否则辅助调用为 2 次）
        when(aibiConfig.crossConsistencyEnabled()).thenReturn(true);
        when(aibiConfig.crossCheckEnabled()).thenReturn(true);
        when(aibiConfig.crossCheckModel()).thenReturn("aux");
        when(llm.chatWithModel(anyString(), eq("aux"))).thenReturn("SELECT 1");

        engine.generate(CFG, ctx("问题"));
        verify(llm, times(1)).chatWithModel(anyString(), anyString());
        assertFalse(stats.isCrossChecked());
        assertTrue(stats.isCrossConsistent());
    }

    // ==================== V5-B：Schema 选表交叉复核 ====================

    @Test
    void schemaCrossCheck_expandsSliceWithAuxTables() {
        when(aibiConfig.schemaCrossCheckEnabled()).thenReturn(true);
        when(aibiConfig.crossCheckModel()).thenReturn("aux");
        when(schemaProvider.getTables(any(), any())).thenReturn(List.of(ORDERS, USERS));
        when(llm.chatWithModel(anyString(), eq("aux"))).thenReturn("users"); // 辅助补选 users

        engine.generate(CFG, ctx("问题"));
        assertTrue(stages.stream().anyMatch(s -> s.contains("复核后共 2 张相关表")),
                "选表复核应扩展切片并广播阶段：" + stages);
        verify(llm, times(1)).chatWithModel(anyString(), eq("aux"));
    }

    // ==================== JOIN 静态校验（joinGuard 为真实静态直调） ====================

    @Test
    void joinGuard_adoptsRepairWhenIssueFixedOnce() {
        when(aibiConfig.joinGuardEnabled()).thenReturn(true);
        when(llm.chat(anyString())).thenReturn("SELECT * FROM orders, users"); // 逗号连接=笛卡尔积
        when(schemaLinker.link(any(), any(), any(), any())).thenReturn(SLICE_TWO);
        when(tableRelationService.relationsOf(any())).thenReturn(List.of(
                new TableRelation("orders", "user_id", "users", "id", TableRelation.Source.FOREIGN_KEY)));
        when(sqlRepairer.repairJoin(any(), any(), any(), anyString(), anyString()))
                .thenReturn("SELECT * FROM orders JOIN users ON orders.user_id = users.id");

        engine.generate(CFG, ctx("各订单的用户"));
        assertEquals("SELECT * FROM orders JOIN users ON orders.user_id = users.id", executedSql());
        assertTrue(stats.isRepaired());
    }

    @Test
    void joinGuard_keepsOriginalWhenRepairUnchanged() {
        when(aibiConfig.joinGuardEnabled()).thenReturn(true);
        when(llm.chat(anyString())).thenReturn("SELECT * FROM orders, users");
        when(schemaLinker.link(any(), any(), any(), any())).thenReturn(SLICE_TWO);
        when(sqlRepairer.repairJoin(any(), any(), any(), anyString(), anyString()))
                .thenReturn("SELECT * FROM orders, users"); // 修了等于没修

        engine.generate(CFG, ctx("问题"));
        assertEquals("SELECT * FROM orders, users", executedSql());
        assertFalse(stats.isRepaired());
    }

    // ==================== 执行期：报错修正 / 不可修正直抛 / 空结果自修复 ====================

    @Test
    void execFailure_repairableBizException_re_feedsRepairerAndRetries() {
        when(aibiConfig.maxRepair()).thenReturn(2);
        when(llm.chat(anyString())).thenReturn("SELECT bad");
        when(adapter.execute(any(), eq("SELECT bad"), anyLong(), anyInt()))
                .thenThrow(new BizException(ErrorCode.SQL_GENERATE_FAILED, "Unknown column 'bad'"));
        when(sqlRepairer.repair(any(), any(), any(), eq("SELECT bad"), anyString()))
                .thenReturn("SELECT good");
        when(adapter.execute(any(), eq("SELECT good"), anyLong(), anyInt())).thenReturn(ONE_ROW);

        engine.generate(CFG, ctx("问题"));
        assertTrue(stats.isRepaired());
        assertEquals(2, stats.getSqlAttempts());
        assertEquals("SELECT good", executedSql());
    }

    @Test
    void execFailure_nonRepairableError_throwsWithoutRepair() {
        when(adapter.execute(any(), anyString(), anyLong(), anyInt()))
                .thenThrow(new BizException(ErrorCode.PARAM_ERROR, "查询超时等非SQL问题"));

        assertThrows(BizException.class, () -> engine.generate(CFG, ctx("问题")));
        verify(sqlRepairer, never()).repair(any(), any(), any(), any(), any());
    }

    @Test
    void emptyResultRepair_relaxesFilterOnce() {
        when(aibiConfig.emptyResultRepair()).thenReturn(true);
        when(adapter.execute(any(), eq("SELECT 1"), anyLong(), anyInt())).thenReturn(ZERO_ROW);
        when(sqlRepairer.repairEmpty(any(), any(), any(), anyString(), any()))
                .thenReturn("SELECT relaxed");
        when(adapter.execute(any(), eq("SELECT relaxed"), anyLong(), anyInt())).thenReturn(ONE_ROW);

        engine.generate(CFG, ctx("问题"));
        assertTrue(stats.isEmptyRetried());
        assertEquals("SELECT relaxed", executedSql());
    }
}
