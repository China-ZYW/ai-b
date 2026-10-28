package com.aibi.module.chat.skill;

import com.aibi.module.chat.nl2sql.ExecStats;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.nl2sql.QueryContext;
import com.aibi.module.datasource.model.DataSourceConfig;

/**
 * 技能执行上下文：在 {@link QueryContext}（问题 / 会话 / 多轮 / 进度 / 统计）之上补齐
 * “本次问数归属谁、查哪个库”的环境信息，使技能只依赖一个入参即可工作。
 *
 * <p>拆两层而非全部塞进 {@code QueryContext} 的原因：{@code QueryContext} 是 NL2SQL 引擎的契约
 * （被 {@code SqlRepairer}、少样本、历史等多处引用），频繁变动会牵连过多；而技能是新的编排层，
 * 需要租户/用户/数据源等环境维度，故以组合方式持有它，{@link #withQuery} 派生新上下文时环境不变。
 *
 * @param datasourceId 数据源 id（已在当前租户范围内解析通过）
 * @param config       已解密的数据源配置（技能执行 SQL 用）
 * @param query        问数上下文（问题、会话、多轮历史、进度、执行统计、校验反馈、图表要求）
 * @param tenantId     当前租户（隔离与配额归集）
 * @param uid          当前用户（历史归属）
 */
public record AskContext(
        Long datasourceId,
        DataSourceConfig config,
        QueryContext query,
        Long tenantId,
        long uid
) {

    public AskContext {
        if (query == null) {
            throw new IllegalArgumentException("QueryContext 不能为空");
        }
    }

    public String question() {
        return query.question();
    }

    public ProgressListener listener() {
        return query.listener();
    }

    public ExecStats stats() {
        return query.stats();
    }

    /** 派生：替换问数上下文（校验闭环重试时附加 retryHint），环境维度原样保留。 */
    public AskContext withQuery(QueryContext next) {
        return new AskContext(datasourceId, config, next, tenantId, uid);
    }
}
