package com.aibi.module.chat.skill.impl;

import com.aibi.module.chat.nl2sql.ConversationTurn;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.nl2sql.QueryContext;
import com.aibi.module.chat.nl2sql.SchemaProvider;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DrillDownSkill} 判定与接线单测（T12-2）：外部依赖全桩，验证"命中构造 / 弃权形态 /
 * 执行走安全门禁"三段，不触库、不触 LLM。
 */
class DrillDownSkillTest {

    private static final String TOP10_SQL =
            "SELECT city, SUM(amount) AS total FROM orders GROUP BY city ORDER BY total DESC LIMIT 10";

    private DataSourceAdapterRegistry adapterRegistry;
    private SchemaProvider schemaProvider;
    private SqlValidator sqlValidator;
    private DataMaskGate dataMaskGate;
    private DataSourceAdapter adapter;
    private DataSourceConfig config;
    private DrillDownSkill skill;

    @BeforeEach
    void setUp() {
        adapterRegistry = mock(DataSourceAdapterRegistry.class);
        schemaProvider = mock(SchemaProvider.class);
        sqlValidator = mock(SqlValidator.class);
        dataMaskGate = mock(DataMaskGate.class);
        adapter = mock(DataSourceAdapter.class);
        config = mock(DataSourceConfig.class);
        when(config.type()).thenReturn(DataSourceType.MYSQL);
        when(config.id()).thenReturn(1L);
        skill = new DrillDownSkill(adapterRegistry, schemaProvider, sqlValidator, dataMaskGate,
                mock(AibiConfig.class), 10000L, 30);
        when(adapterRegistry.get(any())).thenReturn(adapter);
        when(sqlValidator.validate(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** orders 表：province(省份)/city(城市) 列，中文标签可被"那按省份呢"命中。 */
    private void stubOrdersTable() {
        TableMeta orders = new TableMeta("orders", "订单表", List.of(
                new TableMeta.ColumnMeta("city", "varchar", "城市"),
                new TableMeta.ColumnMeta("province", "varchar", "省份"),
                new TableMeta.ColumnMeta("amount", "decimal", "销售额")),
                List.of(), List.of());
        when(schemaProvider.getTables(any(), any())).thenReturn(List.of(orders));
    }

    private AskContext ctx(String question, List<ConversationTurn> turns) {
        return new AskContext(1L, config,
                new QueryContext(question, "c1", turns, ProgressListener.NOOP), 1L, 1L);
    }

    @Test
    void planHitBuildsInheritedSqlWithNewDimension() {
        stubOrdersTable();
        var plan = skill.plan(ctx("那按省份呢", List.of(new ConversationTurn("各城市销售额", TOP10_SQL))));
        assertTrue(plan.matched());
        assertTrue(plan.reason().contains("province"), plan.reason());
    }

    @Test
    void declinesWhenNoHistoryOrNotDrillPhrasing() {
        // 首轮无历史
        assertFalse(skill.plan(ctx("那按省份呢", List.of())).matched());
        // 非"按W"表达
        stubOrdersTable();
        assertFalse(skill.plan(ctx("各省份销售额多少", List.of(
                new ConversationTurn("各城市销售额", TOP10_SQL)))).matched());
        // 维度列不在上轮表
        assertFalse(skill.plan(ctx("那按性别呢", List.of(
                new ConversationTurn("各城市销售额", TOP10_SQL)))).matched());
    }

    @Test
    void ambiguousColumnsDecline() {
        TableMeta orders = new TableMeta("orders", null, List.of(
                new TableMeta.ColumnMeta("province", "varchar", "省份"),
                new TableMeta.ColumnMeta("province_code", "varchar", "省份"),
                new TableMeta.ColumnMeta("city", "varchar", "城市")),
                List.of(), List.of());
        when(schemaProvider.getTables(any(), any())).thenReturn(List.of(orders));
        assertFalse(skill.plan(ctx("那按省份呢", List.of(
                new ConversationTurn("各城市销售额", TOP10_SQL)))).matched());
    }

    @Test
    void executionPassesReadOnlyValidationAndTableAcl() {
        stubOrdersTable();
        QueryResult result = QueryResult.of(TOP10_SQL, List.of("city", "province", "total"),
                List.of(List.<Object>of("hz", "zj", 100)), 1, 5L);
        when(adapter.execute(any(), anyString(), anyLong(), anyInt())).thenReturn(result);

        var plan = skill.plan(ctx("那按省份呢", List.of(new ConversationTurn("各城市销售额", TOP10_SQL))));
        assertTrue(plan.matched());
        QueryResult ran = plan.run();

        assertEquals(result, ran);
        // 构造产物先过只读复校、再过表级授权，才交给适配器执行
        verify(sqlValidator).validate(anyString());
        verify(dataMaskGate).assertTablesAllowed(eq(1L), anyString());
        verify(adapter).execute(eq(config), anyString(), eq(10000L), eq(30));
    }
}
