package com.aibi.module.chat.skill.impl;

import com.aibi.common.exception.BizException;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkill;
import com.aibi.module.chat.skill.SkillPlan;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.dac.DataMaskGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 直连 SQL 技能：用户直接粘贴 SELECT 提问时原样校验执行，<b>不经过大模型改写</b>。
 *
 * <p>价值：① 保证"问题 = 执行 SQL = 结果 = 解读 = 校验"五者一致，杜绝 LLM 把用户给定 SQL 改写得面目全非；
 * ② 免 LLM、免配额，秒级返回；③ 让高级用户（数据分析师）拥有确定的逃生通道。
 *
 * <p>判定三段（与自然语言问法互斥，误判风险低）：以 SELECT/WITH 起头 → 含 FROM 子句 →
 * 通过 {@link SqlValidator} 单条只读校验（解析失败说明是自然语言，回落 NL2SQL）。
 */
@Component
public class DirectSqlSkill implements AskSkill {

    private static final Logger log = LoggerFactory.getLogger(DirectSqlSkill.class);

    public static final String CODE = "direct_sql";

    private final DataSourceAdapterRegistry adapterRegistry;
    private final SqlValidator sqlValidator;
    private final DataMaskGate dataMaskGate;
    private final AibiConfig aibiConfig;
    private final long maxRows;
    private final int timeoutSeconds;

    public DirectSqlSkill(DataSourceAdapterRegistry adapterRegistry,
                          SqlValidator sqlValidator,
                          DataMaskGate dataMaskGate,
                          AibiConfig aibiConfig,
                          @Value("${aibi.query.max-rows:10000}") long maxRows,
                          @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.adapterRegistry = adapterRegistry;
        this.sqlValidator = sqlValidator;
        this.dataMaskGate = dataMaskGate;
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
        return "直连 SQL";
    }

    @Override
    public String description() {
        return "提问本身即只读 SELECT 时原样执行，不改写、不消耗大模型";
    }

    @Override
    public int order() {
        return 10;
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
     * 一次解析完成判定：检测到的 SQL 直接闭包进计划，执行阶段不再二次文本解析。
     *
     * @return 提问本身即 SQL 时返回执行计划，否则 {@link SkillPlan#NO}
     */
    @Override
    public SkillPlan plan(AskContext ctx) {
        String sql = directSqlIfAny(ctx.question());
        return sql == null ? SkillPlan.NO
                : SkillPlan.of(100, "提问本身即 SQL 语句", () -> run(ctx, sql));
    }

    /** 直通执行：原样校验后执行用户给定的 SQL，全程不触达大模型。 */
    private QueryResult run(AskContext ctx, String sql) {
        ProgressListener listener = ctx.listener();
        log.info("技能[{}] 命中，直通执行 [{}]: {}", CODE, ctx.config().safeDescriptor(), sql);
        listener.onStage(ProgressListener.STAGE_GENERATE, "检测到提问已是 SQL 语句，将直接执行");
        listener.onStage(ProgressListener.STAGE_SQL, "SQL 已通过安全校验（用户给定，不改写）");
        listener.onStage(ProgressListener.STAGE_EXECUTE, "正在执行查询…");
        DataSourceAdapter adapter = adapterRegistry.get(ctx.config().type());
        // 表级授权（v2）：手填 SQL 也可能指向隐藏表，执行前硬拦截
        dataMaskGate.assertTablesAllowed(ctx.datasourceId(), sql);
        QueryResult result = adapter.execute(ctx.config(), sql, maxRows, timeoutSeconds);
        ctx.stats().setSqlAttempts(1);
        if (result.rowCount() >= maxRows) {
            ctx.stats().setTruncated(true);
        }
        return result;
    }

    /**
     * 直连 SQL 检测：问题文本本身即完整只读 SELECT 时返回清理后的 SELECT，否则返回 null。
     */
    private String directSqlIfAny(String question) {
        if (question == null) {
            return null;
        }
        String trimmed = question.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            return null;
        }
        if (!lower.matches("(?s).*\\bfrom\\b.*")) {
            return null;
        }
        try {
            return sqlValidator.validate(trimmed);
        } catch (BizException e) {
            return null;
        }
    }
}
