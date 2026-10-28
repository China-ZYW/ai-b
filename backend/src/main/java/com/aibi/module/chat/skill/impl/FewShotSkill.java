package com.aibi.module.chat.skill.impl;

import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkill;
import com.aibi.module.chat.skill.QuestionMatcher;
import com.aibi.module.chat.skill.SkillIndexCache;
import com.aibi.module.chat.skill.SkillPlan;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.fewshot.entity.FewShotExample;
import com.aibi.module.fewshot.mapper.FewShotExampleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 样例复用技能：问题与人工维护的 curated 样例问法一致时，直接执行样例已验证 SQL。
 *
 * <p>价值：把"人工校准过的标准问法"变成确定性能力——样例库里已确认的问法不再让大模型重猜，
 * 既零成本、零延迟抖动，又保证同一个问法永远得到同一个答案；同时命中即累加 {@code use_count}，
 * 为"哪些问法最常被问"提供运营数据（可据此把高频样例进一步沉淀为指标）。
 *
 * <p>与少样本注入（{@code FewShotStore}）的区别：少样本是"把样例塞进 Prompt 让 LLM 参考"，
 * 仍需一次大模型调用；本技能是"直接复用结果"，完全不调用模型。二者互补而非替代。
 *
 * <p>相比上一版，{@link #plan} 已在解析阶段锁定样例实体并闭包，执行阶段不再第二次查询样例表。
 */
@Component
public class FewShotSkill implements AskSkill {

    private static final Logger log = LoggerFactory.getLogger(FewShotSkill.class);

    public static final String CODE = "fewshot";

    private final FewShotExampleMapper exampleMapper;
    private final DataSourceAdapterRegistry adapterRegistry;
    private final SqlValidator sqlValidator;
    private final DataMaskGate dataMaskGate;
    private final SkillIndexCache indexCache;
    private final AibiConfig aibiConfig;
    private final long maxRows;
    private final int timeoutSeconds;

    public FewShotSkill(FewShotExampleMapper exampleMapper,
                        DataSourceAdapterRegistry adapterRegistry,
                        SqlValidator sqlValidator,
                        DataMaskGate dataMaskGate,
                        SkillIndexCache indexCache,
                        AibiConfig aibiConfig,
                        @Value("${aibi.query.max-rows:10000}") long maxRows,
                        @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.exampleMapper = exampleMapper;
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
        return "样例复用";
    }

    @Override
    public String description() {
        return "命中人工维护的标准问法样例时直接执行其已验证 SQL，免大模型且口径稳定";
    }

    @Override
    public int order() {
        return 30;
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
     * 一次解析即锁定命中的样例并闭包进计划：执行阶段直接跑该样例已验证 SQL，不再二次查询。
     *
     * @return 命中且样例已配置 SQL 时返回执行计划；否则 {@link SkillPlan#NO}
     */
    @Override
    public SkillPlan plan(AskContext ctx) {
        FewShotExample hit = hit(ctx);
        if (hit == null || hit.getSqlText() == null || hit.getSqlText().isBlank()) {
            return SkillPlan.NO;
        }
        return SkillPlan.of(95, "复用标准问法样例", () -> run(ctx, hit));
    }

    /** 执行样例已验证 SQL（安全复校后只读执行），并累加热度统计。 */
    private QueryResult run(AskContext ctx, FewShotExample hit) {
        log.info("技能[{}] 命中样例[{}]，直接执行已验证 SQL（免 LLM）", CODE, hit.getId());
        ctx.listener().onStage(ProgressListener.STAGE_EXECUTE, "命中标准问法，正在取数…");
        String safeSql = sqlValidator.validate(hit.getSqlText());
        DataSourceAdapter adapter = adapterRegistry.get(ctx.config().type());
        dataMaskGate.assertTablesAllowed(ctx.datasourceId(), safeSql);
        QueryResult result = adapter.execute(ctx.config(), safeSql, maxRows, timeoutSeconds);
        ctx.stats().setSqlAttempts(1);
        if (result.rowCount() >= maxRows) {
            ctx.stats().setTruncated(true);
        }
        bumpUseCount(hit);
        return result;
    }

    /**
     * 候选集查询条件：该数据源下启用且已配置 SQL 的样例（跨租户隔离由租户拦截器自动追加）。
     *
     * <p>抽成方法而非在调用处现搭 wrapper：条件对象是纯函数、无状态依赖，抽出来既便于单测，
     * 也让"命中判定读哪些行"这件事在一处可见。
     */
    private LambdaQueryWrapper<FewShotExample> exampleWrapper(Long datasourceId) {
        // 同 {@code MetricSkill#metricWrapper}：不用链式，避开 LambdaQueryWrapper 自限定泛型的目标类型推断问题
        LambdaQueryWrapper<FewShotExample> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FewShotExample::getDatasourceId, datasourceId);
        wrapper.eq(FewShotExample::getEnabled, 1);
        // 不再追加 IS NOT NULL 条件：SQL 为空的候选已在本类 {@link #plan} 里按 sqlText 非空过滤
        return wrapper;
    }

    /** 命中的样例（归一化后问法完全一致）；候选集走短 TTL 索引缓存，异常降级为未命中。 */
    private FewShotExample hit(AskContext ctx) {
        String question = ctx.question();
        if (question == null || question.isBlank() || ctx.datasourceId() == null) {
            return null;
        }
        List<FewShotExample> rows;
        try {
            // 只取该数据源下启用且有 SQL 的样例作为候选集（租户隔离由拦截器自动追加）；
            // 样例库同样是读多写少，每次问数都全量扫一遍并不划算，故加一层短 TTL 索引缓存
            rows = indexCache.<FewShotExample>list("fewshot:" + ctx.tenantId() + ":" + ctx.datasourceId(),
                    () -> exampleMapper.selectList(exampleWrapper(ctx.datasourceId())));
        } catch (Exception e) {
            log.debug("样例复用匹配跳过（表未就绪）：{}", e.getMessage());
            return null;
        }
        for (FewShotExample e : rows) {
            if (QuestionMatcher.sameQuestion(question, e.getQuestion())) {
                return e;
            }
        }
        return null;
    }

    /** 命中即累加 use_count（统计用，失败不影响主链路）。 */
    private void bumpUseCount(FewShotExample hit) {
        try {
            FewShotExample row = exampleMapper.selectById(hit.getId());
            if (row != null) {
                row.setUseCount((row.getUseCount() == null ? 0 : row.getUseCount()) + 1);
                exampleMapper.updateById(row);
            }
        } catch (Exception e) {
            log.debug("样例 use_count 回写跳过：{}", e.getMessage());
        }
    }
}
