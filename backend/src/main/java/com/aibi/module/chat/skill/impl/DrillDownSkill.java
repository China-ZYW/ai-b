package com.aibi.module.chat.skill.impl;

import com.aibi.module.chat.nl2sql.ConversationTurn;
import com.aibi.module.chat.nl2sql.ProgressListener;
import com.aibi.module.chat.nl2sql.SchemaProvider;
import com.aibi.module.chat.nl2sql.SqlValidator;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkill;
import com.aibi.module.chat.skill.SkillPlan;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.dac.DataMaskGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多轮下钻技能（T12-2）：追问点名新的分组维度（"那按省份呢"）时，<b>复用上轮已执行 SQL 的
 * 全部口径</b>（表/过滤/聚合/排序/行数上限），确定性追加维度列直接构造下钻 SQL 执行——
 * 全程零大模型，口径与上轮逐字节一致。
 *
 * <p><b>与追问改写的分工</b>：{@code QuestionRewriter} 负责把追问补全成独立问句（通用但重新生成，
 * 口径是否继承全看模型发挥）；本技能在执行补全后的问句上识别"按W"下钻表达，命中即走确定性构造。
 * 判定严格（单层单表 GROUP BY + 维度词唯一命中列），任何环节不满足即 {@link SkillPlan#NO} 弃权，
 * 由既有技能/智能生成兜底——<b>未命中路径与引入本技能前逐字节等价</b>。
 *
 * <p><b>排序 order=8</b>：先于指标直达（order 更大者）。命中即用户意图最忠实的"把上轮结果换个
 * 分组看"；严格检测与弃权机制对冲误判风险。免 LLM 故不耗配额、不可被校验重试改写（口径权威）。
 *
 * <p><b>安全</b>：构造产物是与上轮同源的一条 SELECT，仍过 {@link SqlValidator} 只读复校与
 * {@link DataMaskGate} 表级授权后才执行，与直连 SQL 技能同一套出口约束；脱敏/图表/历史由
 * {@code ChatService} 统一后置，技能不旁路。
 */
@Component
public class DrillDownSkill implements AskSkill {

    private static final Logger log = LoggerFactory.getLogger(DrillDownSkill.class);

    public static final String CODE = "drilldown";

    /** 上轮 SQL 主表名抽取（取深度无关的第一个 FROM 词；形态复杂的 SQL 会先被 Planner 弃权）。 */
    private static final Pattern MAIN_TABLE =
            Pattern.compile("(?i)\\bFROM\\s+[`\"\\[]?([A-Za-z0-9_\\p{IsHan}\\u0080-\\uFFFF]+)");

    private final DataSourceAdapterRegistry adapterRegistry;
    private final SchemaProvider schemaProvider;
    private final SqlValidator sqlValidator;
    private final DataMaskGate dataMaskGate;
    private final AibiConfig aibiConfig;
    private final long maxRows;
    private final int timeoutSeconds;

    public DrillDownSkill(DataSourceAdapterRegistry adapterRegistry,
                          SchemaProvider schemaProvider,
                          SqlValidator sqlValidator,
                          DataMaskGate dataMaskGate,
                          AibiConfig aibiConfig,
                          @Value("${aibi.query.max-rows:10000}") long maxRows,
                          @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.adapterRegistry = adapterRegistry;
        this.schemaProvider = schemaProvider;
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
        return "下钻继承";
    }

    @Override
    public String description() {
        return "追问点名新分组维度时，复用上轮 SQL 的口径与条件，仅追加维度构造下钻查询";
    }

    @Override
    public int order() {
        return 8;
    }

    @Override
    public boolean enabled() {
        return aibiConfig.askSkillEnabled(CODE, true);
    }

    @Override
    public boolean llmFree() {
        return true;
    }

    @Override
    public SkillPlan plan(AskContext ctx) {
        // ① 廉价预探测（不碰元数据）：绝大多数问句在这一步被排除
        if (DrillDownPlanner.extractDimension(ctx.question()).isEmpty()) {
            return SkillPlan.NO;
        }
        // ② 上轮必须有一条成功执行的 SQL
        List<ConversationTurn> turns = ctx.query().turns();
        if (turns == null || turns.isEmpty()) {
            return SkillPlan.NO;
        }
        String lastSql = turns.get(turns.size() - 1).sql();
        if (lastSql == null || lastSql.isBlank()) {
            return SkillPlan.NO;
        }
        // ③ 候选维度列 = 上轮 SQL 主表的真实列（列注释作匹配标签）；带缓存、已按 ACL 滤表
        DataSourceAdapter adapter = adapterRegistry.get(ctx.config().type());
        List<TableMeta> tables = schemaProvider.getTables(adapter, ctx.config());
        Matcher tm = MAIN_TABLE.matcher(lastSql);
        if (!tm.find()) {
            return SkillPlan.NO;
        }
        String tableName = tm.group(1);
        Optional<TableMeta> table = findTable(tables, tableName);
        if (table.isEmpty()) {
            return SkillPlan.NO;
        }
        List<DrillDownPlanner.Dim> dims = new ArrayList<>(table.get().columns().size());
        for (TableMeta.ColumnMeta c : table.get().columns()) {
            String label = c.comment() == null || c.comment().isBlank() ? c.name() : c.comment();
            dims.add(new DrillDownPlanner.Dim(c.name(), label));
        }
        // ④ 确定性构造：形态/歧义/已分组等任一不满足即弃权，走既有链路（行为与今日等价）
        Optional<DrillDownPlanner.Plan> plan = DrillDownPlanner.planWithDims(ctx.question(), lastSql, dims);
        if (plan.isEmpty()) {
            return SkillPlan.NO;
        }
        DrillDownPlanner.Plan p = plan.get();
        log.info("技能[{}] 命中下钻：{} <- [{}] {}", CODE, p.dimension(), tableName, p.sql());
        return SkillPlan.of(90, "继承上一轮查询口径，仅追加分组维度：" + p.dimension(),
                () -> run(ctx, p.sql(), p.dimension()));
    }

    /** 直通执行：复校只读与表级授权后执行构造 SQL，全程不触达大模型。 */
    private QueryResult run(AskContext ctx, String sql, String dimension) {
        ProgressListener listener = ctx.listener();
        log.info("技能[{}] 下钻直通执行 [{}]: {}", CODE, ctx.config().safeDescriptor(), sql);
        listener.onStage(ProgressListener.STAGE_GENERATE, "下钻沿用上一轮口径，仅追加维度分组");
        listener.onStage(ProgressListener.STAGE_SQL, "下钻 SQL 已通过安全校验（继承上轮，不改写）");
        // 双保险：构造产物必须再次通过单条只读校验（与执行用户 SQL 同等门禁）
        String checked = sqlValidator.validate(sql);
        listener.onStage(ProgressListener.STAGE_EXECUTE, "正在执行查询…");
        DataSourceAdapter adapter = adapterRegistry.get(ctx.config().type());
        dataMaskGate.assertTablesAllowed(ctx.datasourceId(), checked);
        QueryResult result = adapter.execute(ctx.config(), checked, maxRows, timeoutSeconds);
        ctx.stats().setSqlAttempts(1);
        if (result.rowCount() >= maxRows) {
            ctx.stats().setTruncated(true);
        }
        return result;
    }

    /** 主表匹配：忽略大小写等值优先；兼容 schema 限定名（db.orders 与 orders 互认）。 */
    private static Optional<TableMeta> findTable(List<TableMeta> tables, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (TableMeta t : tables) {
            if (t.name() != null && t.name().toLowerCase(Locale.ROOT).equals(n)) {
                return Optional.of(t);
            }
        }
        String bare = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n;
        for (TableMeta t : tables) {
            if (t.name() == null) {
                continue;
            }
            String tn = t.name().toLowerCase(Locale.ROOT);
            String tbare = tn.contains(".") ? tn.substring(tn.lastIndexOf('.') + 1) : tn;
            if (tbare.equals(bare)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
