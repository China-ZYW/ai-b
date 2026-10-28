package com.aibi.module.chat.skill.impl;

import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkill;
import com.aibi.module.chat.skill.QuestionMatcher;
import com.aibi.module.chat.skill.SkillIndexCache;
import com.aibi.module.chat.skill.SkillPlan;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.dashboard.mapper.MetricMapper;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.dac.DataMaskGate;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指标直达技能：问题命中已沉淀的指标（名称或原始问法一致）时，直接重跑指标已存 SQL。
 *
 * <p>价值：① 免 LLM、免配额、毫秒级；② 口径<b>永远与指标卡/驾驶舱一致</b>——同一个问题不会今天一个答案、
 * 明天另一个答案，这是"问数"走向"可信指标"的关键；③ 越用越准：把确认过的问法存为指标即等于给系统加了一条确定性规则。
 *
 * <p>命中判定（保守，宁可少命中也不错答）：归一化后与指标名或指标原始问法<b>完全相等</b>，
 * 或问题完整包含指标名且剩余长度 ≤ 6 字（"月度GMV趋势"）。模糊语义一律交给 NL2SQL，避免用错误口径回答新问题。
 *
 * <p>隔离：{@code metric} 为租户隔离表，查询由租户拦截器自动限定；SQL 经 {@link SqlValidator} 复校后
 * 由数据源适配器只读执行，与指标刷新同一安全等级。
 */
@Component
public class MetricSkill implements AskSkill {

    private static final Logger log = LoggerFactory.getLogger(MetricSkill.class);

    public static final String CODE = "metric";

    /** 命中指标后，问题除指标名外的剩余字符上限（超出视为"新问题"，交 NL2SQL）。 */
    private static final int TAIL_TOLERANCE = 6;

    private final MetricMapper metricMapper;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SkillIndexCache indexCache;
    private final SqlValidator sqlValidator;
    private final DataMaskGate dataMaskGate;
    private final AibiConfig aibiConfig;
    private final long maxRows;
    private final int timeoutSeconds;

    public MetricSkill(MetricMapper metricMapper,
                       DataSourceAdapterRegistry adapterRegistry,
                       SqlValidator sqlValidator,
                       DataMaskGate dataMaskGate,
                       SkillIndexCache indexCache,
                       AibiConfig aibiConfig,
                       @Value("${aibi.query.max-rows:10000}") long maxRows,
                       @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.metricMapper = metricMapper;
        this.adapterRegistry = adapterRegistry;
        this.sqlValidator = sqlValidator;
        this.dataMaskGate = dataMaskGate;
        this.indexCache = indexCache;
        this.aibiConfig = aibiConfig;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return "指标直达";
    }

    @Override
    public String description() {
        return "命中已沉淀指标时直接重跑指标 SQL，口径与指标卡一致且不消耗大模型";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean enabled() {
        return aibiConfig.askSkillEnabled(CODE, true);
    }

    @Override
    public boolean llmFree() {
        return true;
    }

    /**
     * 一次解析即锁定命中的指标实体并闭包进计划：执行阶段直接重跑该指标已存 SQL，
     * 不再重复扫描指标表（早期 match / execute 两段式会导致一次问数两遍全量查询）。
     *
     * @return 命中且指标已存 SQL 时返回执行计划；否则 {@link SkillPlan#NO}
     */
    @Override
    public SkillPlan plan(AskContext ctx) {
        Metric hit = hit(ctx);
        if (hit == null || hit.getSqlText() == null || hit.getSqlText().isBlank()) {
            return SkillPlan.NO;
        }
        return SkillPlan.of(100, "命中指标「" + hit.getName() + "」", () -> run(ctx, hit));
    }

    /** 重跑指标已存 SQL：安全复校后只读执行，口径与指标卡完全一致。 */
    private QueryResult run(AskContext ctx, Metric hit) {
        log.info("技能[{}] 命中指标[{}]{}，重跑已存 SQL（免 LLM）", CODE, hit.getId(), hit.getName());
        ctx.listener().onStage(ProgressListener.STAGE_EXECUTE, "命中指标「" + hit.getName() + "」，正在取数…");
        String safeSql = sqlValidator.validate(hit.getSqlText());
        DataSourceAdapter adapter = adapterRegistry.get(ctx.config().type());
        dataMaskGate.assertTablesAllowed(ctx.datasourceId(), safeSql);
        QueryResult result = adapter.execute(ctx.config(), safeSql, maxRows, timeoutSeconds);
        ctx.stats().setSqlAttempts(1);
        if (result.rowCount() >= maxRows) {
            ctx.stats().setTruncated(true);
        }
        return result;
    }

    /**
     * 候选集查询条件：该数据源下已保存 SQL 的指标（跨租户隔离由 MP 租户拦截器自动追加）。
     *
     * <p>刻意写成"先构造对象、再逐条追加条件"而非链式一行：<b>{@code LambdaQueryWrapper} 的链式方法
     * 返回自限定泛型 {@code Children}</b>，一旦出现在赋值/return 这类有目标类型的位置，javac 的类型推断会报
     * 出令人费解的 "需要 ')'" 语法错误。逐语句调用不触发推断，行为也更直观。
     */
    private LambdaQueryWrapper<Metric> metricWrapper(Long datasourceId) {
        LambdaQueryWrapper<Metric> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Metric::getDatasourceId, datasourceId);
        // 不再追加 IS NOT NULL 条件：SQL 为空的候选已在本类 {@link #plan} 里按 sqlText 非空过滤。
        // 少下一 SQL 条件换来更清晰的意图，也避免了不必要的推断负担
        return wrapper;
    }

    /** 命中的指标（未命中返回 null）；候选集走短 TTL 索引缓存，查询异常一律降级为未命中。 */
    private Metric hit(AskContext ctx) {
        String question = ctx.question();
        if (question == null || question.isBlank() || ctx.datasourceId() == null) {
            return null;
        }
        List<Metric> metrics;
        try {
            // 只在该数据源范围内取"有 SQL 的指标"作为候选集，租户隔离由拦截器自动追加；
            // 指标定义读多写少，每次问数都全量扫一遍 metric 表并不划算，故加一层短 TTL 索引缓存
            metrics = indexCache.<Metric>list("metric:" + ctx.tenantId() + ":" + ctx.datasourceId(),
                    () -> metricMapper.selectList(metricWrapper(ctx.datasourceId())));
        } catch (Exception e) {
            log.debug("指标直达匹配跳过（表未就绪）：{}", e.getMessage());
            return null;
        }
        String nq = QuestionMatcher.normalize(question);
        for (Metric m : metrics) {
            if (m.getName() != null && QuestionMatcher.sameQuestion(question, m.getName())) {
                return m;
            }
            if (m.getQuestion() != null && QuestionMatcher.sameQuestion(question, m.getQuestion())) {
                return m;
            }
            // 问题完整包含指标名且尾部足够短（如"月度GMV趋势"），视为同一问法
            if (m.getName() != null && QuestionMatcher.containsPhrase(question, m.getName())) {
                int tail = nq.length() - QuestionMatcher.normalize(m.getName()).length();
                if (tail <= TAIL_TOLERANCE) {
                    return m;
                }
            }
        }
        return null;
    }
}
