package com.aibi.common.security;

import com.aibi.common.tenant.DataScope;
import com.baomidou.mybatisplus.extension.plugins.handler.DataPermissionHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行级数据范围处理器（{@code DataPermissionInterceptor} 的回调）。
 *
 * <p>按 {@link DataScopeRegistrar} 在启动时注册的 statementId→规则，对命中 {@link RowScope} 标注的
 * Mapper 动作追加行级可见性条件。与租户隔离<b>正交</b>：租户条件由 {@code TenantLineInnerInterceptor}
 * 负责，本类只在其之上按"本人"再收敛一次。
 *
 * <p><b>当前范围实现</b>：仅 {@link DataScope#SELF} 真正追加 {@code uidColumn = 当前uid}；其余范围
 * （TENANT/ALL/CUSTOM/TEAM/TEAM_AND_CHILD）退化为"不追加"（仅租户隔离生效）。团队/跨团队范围需
 * {@code teamIds} 关联表支撑，本轮不启用（见设计文档 4.3），留 TODO 以免误伤或越权。
 *
 * <p>MyBatis-Plus 3.5.5 的 {@code DataPermissionHandler} 接口含默认方法
 * {@code String getSqlSegment(Table, Expression, String)} 委托到本 {@code Expression} 版，故实现此版即可。
 */
@Component
public class AibiDataScopeHandler implements DataPermissionHandler {

    private final Map<String, DataScopeRule> registry = new ConcurrentHashMap<>();

    /** 由 {@link DataScopeRegistrar} 在 ApplicationRunner 阶段填充。 */
    public void register(String mappedStatementId, DataScopeRule rule) {
        if (mappedStatementId != null && rule != null && rule.hasColumn()) {
            registry.put(mappedStatementId, rule);
        }
    }

    public boolean isRegistered(String mappedStatementId) {
        return registry.containsKey(mappedStatementId);
    }

    @Override
    public Expression getSqlSegment(Expression where, String mappedStatementId) {
        DataScopeRule rule = registry.get(mappedStatementId);
        if (rule == null || rule.uidColumn() == null || rule.uidColumn().isBlank()) {
            return null; // 未标注 / 无可行列：不追加（仅租户隔离生效）
        }
        AccessContext ctx = AccessContextHolder.getOrDefault();
        DataScope scope = ctx.dataScope();
        // SELF 且已登录：追加 uidColumn = 当前uid；其余范围本轮退化为仅租户隔离
        if (scope != DataScope.SELF || ctx.uid() == null) {
            return null;
        }
        EqualsTo eq = new EqualsTo();
        eq.setLeftExpression(new Column(rule.uidColumn()));
        eq.setRightExpression(new LongValue(String.valueOf(ctx.uid())));
        if (where == null) {
            return eq;
        }
        return new AndExpression(where, eq);
    }
}
