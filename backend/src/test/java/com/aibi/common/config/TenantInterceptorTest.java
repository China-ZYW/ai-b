package com.aibi.common.config;

import com.aibi.common.tenant.PlanType;
import com.aibi.common.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户隔离拦截器单测（T3 基线，不起 Spring 上下文、不连库）。
 *
 * <p>直接驱动 {@link MybatisPlusConfig.AibiTenantLineHandler} 与 MP
 * {@link TenantLineInnerInterceptor#parserSingle(String, Object)}，断言三件事：
 * <ol>
 *   <li>IGNORE 名单表（模板/全局字典/登录链路表等）不注入 {@code tenant_id}；</li>
 *   <li>受控表在 SQL 层被强制注入正确租户条件（SELECT/INSERT/UPDATE、多表逐表注入）；</li>
 *   <li>无租户上下文时注入 {@code NULL}（查不到任何行，安全默认）而非放行全部。</li>
 * </ol>
 */
class TenantInterceptorTest {

    private final MybatisPlusConfig.AibiTenantLineHandler handler = new MybatisPlusConfig.AibiTenantLineHandler();
    private final TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(handler);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== IGNORE 名单 ====================

    @Test
    void globalTablesAreIgnored() {
        for (String t : new String[]{"tenant", "app_user", "dashboard_template", "llm_config",
                "sys_config", "chart_rule", "theme_config", "user_manual", "role", "permission",
                "role_permission", "user_identity", "team_member", "user_role",
                "invitation", "invitation_apply", "sys_dict", "schema_migration"}) {
            assertTrue(handler.ignoreTable(t), "名单表不应注入租户条件: " + t);
        }
    }

    @Test
    void ignoreTableHandlesQuotingAndCase() {
        // 各方言标识符转义与大小写差异不应影响名单命中
        assertTrue(handler.ignoreTable("`dashboard_template`"));
        assertTrue(handler.ignoreTable("\"LLM_CONFIG\""));
        assertTrue(handler.ignoreTable(" Sys_Config "));
        assertTrue(handler.ignoreTable(null));
    }

    @Test
    void businessTablesAreNotIgnored() {
        for (String t : new String[]{"query_history", "data_source", "query_favorite", "share_link"}) {
            assertFalse(handler.ignoreTable(t), "受控业务表必须注入租户条件: " + t);
        }
    }

    // ==================== 租户值来源 ====================

    @Test
    void noContextYieldsNullValueAsSafeDefault() {
        TenantContext.clear();
        assertInstanceOf(NullValue.class, handler.getTenantId(),
                "无租户上下文必须注入 NULL（恒不命中），绝不能放行全部");
    }

    @Test
    void contextYieldsLongValue() {
        TenantContext.set(7L, PlanType.FREE);
        assertEquals(7L, ((LongValue) handler.getTenantId()).getValue());
        assertEquals("tenant_id", handler.getTenantIdColumn());
    }

    // ==================== SQL 注入效果（parserSingle 真实改写） ====================

    @Test
    void selectOnControlledTableGetsTenantCondition() {
        TenantContext.set(7L, PlanType.FREE);
        String out = interceptor.parserSingle("SELECT * FROM query_history", null);
        assertTrue(out.contains("tenant_id = 7"), "受控表 SELECT 应注入租户条件，实际: " + out);
    }

    @Test
    void selectWithoutContextGetsNullCondition() {
        TenantContext.clear();
        String out = interceptor.parserSingle("SELECT * FROM query_history", null);
        assertTrue(out.contains("tenant_id = NULL"), "无上下文应注入 NULL 条件封死结果集，实际: " + out);
    }

    @Test
    void selectOnGlobalTableUntouched() {
        TenantContext.set(7L, PlanType.FREE);
        String out = interceptor.parserSingle("SELECT * FROM dashboard_template", null);
        assertFalse(out.contains("tenant_id"), "名单表不应被注入，实际: " + out);
    }

    @Test
    void joinInjectsEveryControlledTable() {
        TenantContext.set(7L, PlanType.FREE);
        String out = interceptor.parserSingle(
                "SELECT * FROM query_history qh JOIN data_source ds ON qh.ds_id = ds.id", null);
        int first = out.indexOf("tenant_id");
        assertTrue(first >= 0 && out.indexOf("tenant_id", first + 1) > 0,
                "多表 JOIN 应逐表注入租户条件（两张表各一条），实际: " + out);
    }

    @Test
    void insertAndUpdateAlsoCovered() {
        TenantContext.set(7L, PlanType.FREE);
        String ins = interceptor.parserSingle(
                "INSERT INTO query_favorite (name) VALUES ('x')", null);
        assertTrue(ins.contains("tenant_id"), "INSERT 应补齐租户列，实际: " + ins);
        String upd = interceptor.parserSingle(
                "UPDATE query_favorite SET name = 'y' WHERE id = 1", null);
        assertTrue(upd.contains("tenant_id = 7"), "UPDATE 应追加租户条件，实际: " + upd);
    }
}
