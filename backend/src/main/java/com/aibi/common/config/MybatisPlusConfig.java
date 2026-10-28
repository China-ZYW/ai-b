package com.aibi.common.config;

import com.aibi.common.security.AibiDataScopeHandler;
import com.aibi.common.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis-Plus 配置：POOL 模式的行级租户隔离 + 行级数据范围（SELF）二次收敛。
 *
 * <p>{@link TenantLineInnerInterceptor} 在 SQL 执行前自动为受控表追加 {@code tenant_id = ?}，
 * 值来自 {@link TenantContext}。这样"隔离"由框架强制保证，而非依赖每条 SQL 手写 WHERE，
 * 杜绝"忘记加 tenant_id"导致的跨租户越权（见设计文档 3.2）。
 *
 * <p>{@link DataPermissionInterceptor}（{@code AibiDataScopeHandler}）在租户隔离之上，对标注了
 * {@code @RowScope} 的 Mapper 动作按当前访问上下文的 {@code DataScope} 再收敛——本轮仅实现
 * {@code SELF}（追加 {@code uidColumn = 当前uid}），其余范围退化为仅租户隔离（见设计文档 4.3）。
 *
 * <p>说明：业务表（{@code data_source/query_history/query_favorite/share_link}）均经 MyBatis-Plus 映射，
 * 一律由本拦截器自动注入 {@code tenant_id}；对匿名公开访问（分享链接）或复杂 JOIN 聚合的个别 Mapper 方法，
 * 用 {@code @InterceptorIgnore(tenantLine="true")} 关闭注入并显式传参。启动播种的 {@code DataSeeder} 走
 * JdbcTemplate、无租户上下文，不受本拦截器影响。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 不参与租户隔离自动注入的表：租户表、用户表（登录在租户上下文之前，按 username/uid 全局寻址）、字典表等。
     *
     * <p><b>列入本名单但带 {@code tenant_id} 的表</b>（{@code team_member}/{@code user_role}/
     * {@code invitation}/{@code invitation_apply}）必须遵守三条约束，否则会从"静默越权"变成难以排查的数据泄露：
     * <ol>
     *   <li>写操作显式带 {@code tenant_id}：{@code Wrappers.lambdaUpdate().eq(X::getTenantId, ctx.tenantId())}；</li>
     *   <li>登录后读取显式带 {@code tenant_id}：由 {@code uid → app_user.tenant_id} 反查后再过滤；</li>
     *   <li>读出口用 {@code TenantAssertion.assertMatch(row.getTenantId(), ctx.tenantId())} 兜底。</li>
     * </ol>
     */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "tenant", "app_user", "sys_dict", "flyway_schema_history", "schema_migration",
            // 全局表：大模型配置（tenant_id 可空=全局默认）与驾驶舱内置模板，按显式条件解析而非自动注入
            "llm_config", "dashboard_template",
            // 配置化升级 V2：配置中心/图表规则/主题令牌均为"全局默认+租户覆盖"二级 scope，服务层显式解析
            "sys_config", "chart_rule", "theme_config",
            // 用户手册：平台级全局单例文档（超管编辑发布，全体用户阅读），无 tenant_id
            "user_manual",
            // RBAC 全局字典（V2026_09）：全平台共享，无 tenant_id 概念
            "user_identity", "role", "permission", "role_permission",
            // 登录链路须在 TenantContext 建立之前读取（否则 tenant_id=NULL 恒不命中 → 角色空集 → 全站 403）
            "team_member", "user_role",
            // 匿名接受邀请：请求尚无租户上下文，按 token_hash 全局寻址后显式设租户
            "invitation", "invitation_apply");

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(AibiDataScopeHandler dataScopeHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new AibiTenantLineHandler()));
        // 行级数据范围（SELF）二次收敛：在租户隔离之上追加本人条件；其余范围退化为仅租户隔离
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataScopeHandler));
        return interceptor;
    }

    /** 从 {@link TenantContext} 读取当前租户 id，作为自动注入的 tenant_id 值。 */
    static class AibiTenantLineHandler implements TenantLineHandler {

        @Override
        public Expression getTenantId() {
            Long tid = TenantContext.tenantId();
            // 无租户上下文时返回 NULL，使查询命中不到任何行（安全默认），而非放行全部
            return tid == null ? new NullValue() : new LongValue(tid);
        }

        @Override
        public String getTenantIdColumn() {
            return "tenant_id";
        }

        @Override
        public boolean ignoreTable(String tableName) {
            if (tableName == null) {
                return true;
            }
            // 去除可能的引号（` " [ ]）再比对，兼容各方言标识符转义
            String normalized = tableName.replace("`", "").replace("\"", "")
                    .replace("[", "").replace("]", "").trim().toLowerCase();
            return IGNORE_TABLES.contains(normalized);
        }
    }
}
