package com.aibi.module.chat.nl2sql;

import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.datasource.model.DataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行反馈修正器（§3.6 SqlRepairer）：当生成的 SQL 在真实库上执行报错时，
 * 把"表结构 + 用户问题 + 上一条 SQL + 数据库报错"回喂 LLM，让其自我修正。
 *
 * <p>这是把准确率从"能跑"抬到"可用"的关键回路。修正后的 SQL 仍必须再过一次 {@link SqlValidator}，
 * 重试次数由 {@link Nl2SqlEngine} 控制（默认 ≤2），避免无限循环与成本失控。
 */
@Component
public class SqlRepairer {

    private static final Logger log = LoggerFactory.getLogger(SqlRepairer.class);

    private final LlmRouter llm;
    private final PromptBuilder promptBuilder;
    private final SqlValidator sqlValidator;

    public SqlRepairer(LlmRouter llm, PromptBuilder promptBuilder, SqlValidator sqlValidator) {
        this.llm = llm;
        this.promptBuilder = promptBuilder;
        this.sqlValidator = sqlValidator;
    }

    /**
     * 基于数据库报错修正 SQL。
     *
     * @param failedSql 执行失败的 SQL
     * @param dbError   数据库返回的错误信息
     * @return 已再次通过安全校验的修正 SQL
     */
    public String repair(DataSourceConfig config, SchemaSlice slice, String question,
                         String failedSql, String dbError) {
        String prompt = promptBuilder.buildRepair(config, slice, question, failedSql, dbError);
        String raw = llm.chat(prompt);
        String repaired = sqlValidator.validate(raw);
        log.info("SQL 修正：[{}] => [{}]", failedSql, repaired);
        return repaired;
    }

    /**
     * JOIN 静态校验后的修正（多表问数准确率）：SQL <b>能跑通</b>但连接键有问题
     * （笛卡尔积 / 与关联图谱矛盾 / 两表同名主键互连）时，把 {@link JoinGuard} 的分析结论回喂 LLM，
     * 只改 JOIN、其余保持不变。
     *
     * <p>这类错误比报错更难发现：结果非空、行数正常，只有数值被 fan-out 放大了若干倍。
     * 修正结果同样再过一次 {@link SqlValidator}；由 {@link Nl2SqlEngine} 控制只修一次，不形成重试循环。
     *
     * @param issue {@link JoinGuard#inspect} 给出的修正意见
     * @return 已再次通过安全校验的修正 SQL
     */
    public String repairJoin(DataSourceConfig config, SchemaSlice slice, String question,
                            String sql, String issue) {
        String prompt = promptBuilder.buildJoinRepair(config, slice, question, sql, issue);
        String repaired = sqlValidator.validate(llm.chat(prompt));
        log.info("JOIN 静态校验修正：[{}] => [{}]", sql, repaired);
        return repaired;
    }

    /**
     * 空结果自修复（配置化升级 V2）：SQL 执行成功但返回 0 行时，以"放宽过滤/核对枚举取值"
     * 的修正 Prompt 让 LLM 重试一次，降低"无数据"类误判；修正 SQL 同样再过 {@link SqlValidator}。
     *
     * @param emptySql        返回 0 行的 SQL
     * @param semanticSection 业务语义段落（可空，含枚举取值时修正效果更好）
     * @return 已再次通过安全校验的修正 SQL
     */
    public String repairEmpty(DataSourceConfig config, SchemaSlice slice, String question,
                              String emptySql, String semanticSection) {
        String prompt = promptBuilder.buildEmptyResultRepair(config, slice, question, emptySql, semanticSection);
        String raw = llm.chat(prompt);
        String repaired = sqlValidator.validate(raw);
        log.info("空结果自修复：[{}] => [{}]", emptySql, repaired);
        return repaired;
    }
}
