package com.aibi.module.chat.nl2sql;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.chat.llm.LlmRouter;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.DataSourceConfig;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.dac.DataMaskGate;
import com.aibi.module.semantic.service.SemanticService;
import com.aibi.module.semantic.service.TableRelationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NL2SQL 核心引擎（§3.6）：编排
 * 意图分类 → Schema 缓存与相关表裁剪 → 少样本 → Prompt 组装 → LLM 生成 → 安全校验 → 只读执行 →
 * （执行失败则）反馈修正重试（≤{@code maxRepair}）。
 *
 * <p>从 {@code ChatService} 抽出，使"核心引擎"成为独立、可单测、可替换的领域组件；
 * {@code ChatService} 回归应用层职责（租户/数据源解析、历史落库、计费）。
 *
 * <p>隔离与安全：租户身份隐含在 {@link DataSourceConfig#tenantId()} 中并贯穿缓存键与少样本查询；
 * SQL 只读校验由 {@link SqlValidator} 兜底；执行走适配器（租户专属只读连接池 + 方言/JDBC 双重限流）。
 *
 * <p>配置化升级 V2：策略参数（few-shot-limit/max-repair/empty-result-repair）改读 {@link AibiConfig}
 * （DB 优先、yml 兜底）；接入语义层（选表计分 + Prompt 业务语义段 + 骨架懒初始化）；
 * 新增"空结果自修复"：执行成功但 0 行时放宽过滤重试一次，仍 0 行则如实返回。
 *
 * <p>准确率升级 V3：新增 <b>self-consistency</b> —— 开关开时以措辞变体多路生成候选 SQL，逐条安全校验后
 * 按"结构多数投票"择优选 1 条再执行（{@code nl2sql.self-consistency} / {@code consistency-samples} 可配），
 * 全部候选失败则回落单次生成；执行过程元信息经 {@link ExecStats} 回填供结果完整度展示。
 *
 * <p>准确率升级 V5：新增 <b>辅助模型交叉校验</b> —— 开关 {@code nl2sql.cross-check} 开时，把已生成的候选 SQL
 * 交<b>另一个不同的模型</b>（{@code ai.cross-check-model}，留空复用 {@code ai.analysis-model}）做独立复核，
 * 模型判定有误则采纳其修正版（仍过 {@link SqlValidator} 安全校验、且与原候选不同才采用）。与 V3 同源模型
 * 内部的"多数投票"不同，V5 引入<b>跨模型视角</b>，可显著减少单模型盲区导致的答非所问；主链路零侵入、永不抛错。
 *
 * <p><b>Skill 化（V4）</b>：本引擎只作为"通用生成"这一条能力存在，由
 * {@code com.aibi.module.chat.skill.impl.Nl2SqlSkill} 包装为兜底技能；
 * 原先内嵌的"直连 SQL 旁路"已上移为独立的 {@code DirectSqlSkill}——是否走大模型属于<b>路由决策</b>，
 * 不应埋在引擎内部（否则每次新增一种免 LLM 能力都要改引擎）。引擎自身职责收敛为：
 * 给一个问题，产出一份执行结果。
 */
@Component
public class Nl2SqlEngine {

    private static final Logger log = LoggerFactory.getLogger(Nl2SqlEngine.class);

    private final DataSourceAdapterRegistry adapterRegistry;
    private final SchemaProvider schemaProvider;
    private final SchemaLinker schemaLinker;
    private final IntentClassifier intentClassifier;
    private final FewShotStore fewShotStore;
    private final PromptBuilder promptBuilder;
    private final LlmRouter llm;
    private final SqlValidator sqlValidator;
    private final SqlRepairer sqlRepairer;
    private final AibiConfig aibiConfig;
    private final SemanticService semanticService;
    private final TableRelationService tableRelationService;
    private final DataMaskGate dataMaskGate;

    private final long maxRows;
    private final int timeoutSeconds;

    public Nl2SqlEngine(DataSourceAdapterRegistry adapterRegistry,
                        SchemaProvider schemaProvider,
                        SchemaLinker schemaLinker,
                        IntentClassifier intentClassifier,
                        FewShotStore fewShotStore,
                        PromptBuilder promptBuilder,
                        LlmRouter llm,
                        SqlValidator sqlValidator,
                        SqlRepairer sqlRepairer,
                        AibiConfig aibiConfig,
                        SemanticService semanticService,
                        TableRelationService tableRelationService,
                        DataMaskGate dataMaskGate,
                        @Value("${aibi.query.max-rows:10000}") long maxRows,
                        @Value("${aibi.query.timeout-seconds:30}") int timeoutSeconds) {
        this.adapterRegistry = adapterRegistry;
        this.schemaProvider = schemaProvider;
        this.schemaLinker = schemaLinker;
        this.intentClassifier = intentClassifier;
        this.fewShotStore = fewShotStore;
        this.promptBuilder = promptBuilder;
        this.llm = llm;
        this.sqlValidator = sqlValidator;
        this.sqlRepairer = sqlRepairer;
        this.aibiConfig = aibiConfig;
        this.semanticService = semanticService;
        this.tableRelationService = tableRelationService;
        this.dataMaskGate = dataMaskGate;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 单轮便捷入口：无会话上下文、无进度监听。
     *
     * @param config   已解析且凭证已解密的数据源配置（含租户 id）
     * @param question 用户自然语言问题
     */
    public QueryResult generate(DataSourceConfig config, String question) {
        return generate(config, QueryContext.of(question));
    }

    /**
     * 针对某数据源把一个自然语言问题翻译为 SQL 并只读执行，返回结果。
     * 支持多轮上下文（{@link QueryContext#turns()}）与分步进度回调（{@link QueryContext#listener()}，供 M5 SSE）。
     *
     * @param config 已解析且凭证已解密的数据源配置（含租户 id）
     * @param ctx    问数上下文（问题 / 会话 / 多轮历史 / 进度监听）
     */
    public QueryResult generate(DataSourceConfig config, QueryContext ctx) {
        String question = ctx.question();
        ProgressListener listener = ctx.listener();
        DataSourceAdapter adapter = adapterRegistry.get(config.type());

        // ① Schema（带缓存） + 语义层视图（骨架懒初始化，异步不阻塞主链路） + ② 相关表裁剪（含语义计分）
        //    校验闭环重试时改用 LLM 凭"表名+注释"全量目录选表：子串打分在中文问法下易退化
        //    （无命中时按字母序取前 N 张），重试必须保证校验意见指向的表真正进入 Prompt
        listener.onStage(ProgressListener.STAGE_SCHEMA, "正在加载表结构…");
        List<TableMeta> tables = schemaProvider.getTables(adapter, config);
        semanticService.asyncInitSkeleton(config.id());
        SemanticView semanticView = semanticService.view(config.id());
        // 关联推导策略：选表扩散、Prompt 的【关联关系】段、辅助模型复核必须用同一份口径，否则互相矛盾。
        // 业务库普遍没有外键约束，命名推断是多表问数拿到连接键的主要途径（可在配置中心关）。
        // 人工登记的连接键以最高可信度并入（同键压过外键与推断）；读不到表时返回空列表，主链路退回外键+推断。
        RelationHints hints = new RelationHints(aibiConfig.relationInfer(),
                tableRelationService.relationsOf(config.id()));
        SchemaSlice slice;
        if (ctx.retryHint() != null && !ctx.retryHint().isBlank()) {
            listener.onStage(ProgressListener.STAGE_SCHEMA, "重试选表：按全量目录智能选取相关表…");
            slice = linkByLlm(tables, question, ctx.retryHint(), hints);
            if (slice == null) {
                slice = schemaLinker.link(tables, question + " " + ctx.retryHint(), semanticView, hints);
            }
        } else {
            slice = schemaLinker.link(tables, question, semanticView, hints);
            // 子串打分无任何命中（中文问法大库场景）会退化为按序取前 N 张表，
            // 首轮也回落 LLM 目录选表，从源头避免"只看错表→生成错 SQL→校验低分"链路
            if (slice.degraded()) {
                listener.onStage(ProgressListener.STAGE_SCHEMA, "子串选表未命中，按全量目录智能选取相关表…");
                SchemaSlice llmSlice = linkByLlm(tables, question, null, hints);
                if (llmSlice != null) {
                    slice = llmSlice;
                }
            }
        }
        listener.onStage(ProgressListener.STAGE_SCHEMA, "已选取 " + slice.tables().size() + " 张相关表");

        // ②' Schema 选表交叉复核（V5-B，提升问数准确率）：生成 SQL 前用<b>另一模型</b>独立重挑相关表，
        //    与主链路选出的表取并集（仅保留库中真实存在的表名），从源头减少「只看错表→生成错 SQL」的链路。
        //    永不抛错、无辅助模型 / 调用失败 / 未新增表则保留主切片（主链路零侵入）。
        if (aibiConfig.schemaCrossCheckEnabled()) {
            SchemaSlice expanded = schemaCrossCheck(tables, question, slice, listener);
            if (expanded != null) {
                slice = expanded;
                listener.onStage(ProgressListener.STAGE_SCHEMA, "辅助选表复核后共 " + slice.tables().size() + " 张相关表");
            }
        }

        // ③ 意图分类 + ④ 少样本（curated 样例库 + 反馈晋升 + 历史，条数可配置）
        Intent intent = intentClassifier.classify(question);
        // Phase-1 意图对齐：开关开时把运行时识别的意图名传给少样本检索做双向微调（关则传 null 退回原行为）
        boolean fewShotIntentBoost = aibiConfig.fewShotIntentBoost();
        List<FewShot> shots = fewShotStore.similar(config.id(), question, aibiConfig.fewShotLimit(),
                fewShotIntentBoost ? intent.name() : null, aibiConfig.fewShotIntentWeight());
        listener.onStage(ProgressListener.STAGE_INTENT, "识别意图：" + intent.name());

        // ⑤ Prompt 组装（含多轮上下文与业务语义段） + ⑥ LLM 生成 + ⑦ 安全校验
        String semanticSection = semanticService.buildPromptSection(config.id(), slice);
        String prompt = promptBuilder.build(config, slice, intent, shots, ctx.turns(), question,
                semanticSection, ctx.retryHint(), ctx.requestedChart());
        listener.onStage(ProgressListener.STAGE_GENERATE, "正在生成 SQL…");
        // V3 self-consistency：开关开且路数>1 时多路生成候选后择优，否则走单次生成
        int samples = aibiConfig.selfConsistency() ? aibiConfig.consistencySamples() : 1;
        String sql = samples > 1
                ? generateWithConsistency(prompt, samples, listener, ctx.stats())
                : sqlValidator.validate(llm.chat(prompt));
        log.info("生成 SQL [{}][intent={}][shots={}][turns={}][candidates={}]: {}",
                config.safeDescriptor(), intent, shots.size(), ctx.turns().size(),
                ctx.stats().getConsistencyCandidates(), sql);
        listener.onStage(ProgressListener.STAGE_SQL, "SQL 已生成并通过安全校验");

        // ⑨ 辅助模型交叉校验（V5，提升问数准确率）：开关开时把（问题 + 候选 SQL + Schema 切片）交
        //    <b>另一模型</b>复核，辅助模型认为有误则给出修正版 SQL；仅当它通过安全校验、且与候选不重复时才采纳。
        //    永不抛错、无辅助模型 / 调用失败 / 修正版无效则保留原 SQL（主链路零侵入）。
        //    与 cross-consistency（双生成共识）互斥：开启 cross-consistency 时优先走双生成，不再做单模型复核。
        if (aibiConfig.crossConsistencyEnabled()) {
            String ccSql = crossConsistency(question, slice, sql, prompt, listener, ctx.stats());
            if (ccSql != null && !ccSql.isBlank() && !normalizeSql(ccSql).equals(normalizeSql(sql))) {
                ctx.stats().setCrossChecked(true);
                log.info("cross-consistency 已采纳 SQL [{}] → [{}]", sql, ccSql);
                sql = ccSql;
            }
        } else if (aibiConfig.crossCheckEnabled()) {
            String auxSql = crossCheck(config, question, slice, sql, semanticSection, listener, ctx.stats());
            if (auxSql != null && !auxSql.isBlank() && !normalizeSql(auxSql).equals(normalizeSql(sql))) {
                ctx.stats().setCrossChecked(true);
                log.info("辅助模型交叉校验：已采纳修正 SQL [{}] → [{}]", sql, auxSql);
                sql = auxSql;
            }
        }

        // ⑦' JOIN 静态校验（多表问数准确率）：错连不会报错——SQL 跑通、结果非空，只有数值被 fan-out 放大，
        //    执行期的报错修正与空结果自修复都抓不到它。这里用与 Prompt 完全同源的关联图谱做确定性判断，零模型成本。
        if (aibiConfig.joinGuardEnabled()) {
            sql = joinGuardRepair(config, slice, question, sql, listener, ctx.stats());
        }

        // ⑧ 只读执行 +（失败）执行反馈修正 +（0 行）空结果自修复
        listener.onStage(ProgressListener.STAGE_EXECUTE, "正在执行查询…");
        return executeWithRepair(adapter, config, slice, question, sql, semanticSection, listener, ctx.stats());
    }

    /**
     * JOIN 静态校验并修正（<b>永不抛出</b>）：{@link JoinGuard} 发现连接键问题时回喂模型修正一次。
     *
     * <p>只修一次且不递归：修正后的 SQL 若仍有问题，记日志后照常执行——静态校验是增强而非硬门禁，
     * 反复重试既拖慢响应，也可能把本来正确的部分改坏（后续还有执行期修正与辅助模型校验兜底）。
     * 任何异常一律保留原 SQL，主链路零侵入。
     */
    private String joinGuardRepair(DataSourceConfig config, SchemaSlice slice, String question,
                                   String sql, ProgressListener listener, ExecStats stats) {
        try {
            String issue = JoinGuard.inspect(sql, slice.tables(), slice.relations());
            if (issue == null) {
                return sql;
            }
            log.info("JOIN 静态校验发现问题：{}", issue);
            listener.onStage(ProgressListener.STAGE_VERIFY, "检测到 JOIN 连接键可疑，正在修正…");
            String fixed = sqlRepairer.repairJoin(config, slice, question, sql, issue);
            if (fixed == null || fixed.isBlank() || normalizeSql(fixed).equals(normalizeSql(sql))) {
                return sql;
            }
            String remain = JoinGuard.inspect(fixed, slice.tables(), slice.relations());
            if (remain != null) {
                log.info("JOIN 静态校验：修正后仍有问题，按修正版执行并交后续校验兜底：{}", remain);
            }
            stats.setRepaired(true);
            log.info("JOIN 静态校验已采纳修正 SQL [{}] → [{}]", sql, fixed);
            return fixed;
        } catch (RuntimeException e) {
            log.warn("JOIN 静态校验失败，保留原 SQL：{}", e.getMessage());
            return sql;
        }
    }

    /**
     * 辅助模型交叉校验（V5，提升问数准确率）：用<b>指定模型</b>（{@link AibiConfig#crossCheckModel()}，
     * 留空则复用 {@link AibiConfig#aiAnalysisModel()}）对候选 SQL 做一次独立复核，产出修正版 SQL。
     *
     * <p>策略：把"问题 + 相关表结构（含列与主键/外键标记）+ 可用关联关系 + 业务语义段 + 候选 SQL"交辅助模型，
     * 要求其校验表/列是否存在、JOIN 是否用了正确的关联键、聚合与过滤是否合理，
     * 正确则原样返回、有误则返回修正版；只输出一条 SQL。返回文本经 {@link SqlValidator} 安全校验（非法即视为无效），
     * 调用方进一步比对是否与原候选不同才采纳。整段<b>永不抛出</b>——任何异常（缺失辅助模型 / 调用失败 / 校验不通过）
     * 一律返回 {@code null}，由主链路保留原 SQL。
     *
     * @return 辅助模型返回的（已安全校验的）修正版 SQL，或 {@code null} 表示跳过
     */
    private String crossCheck(DataSourceConfig config, String question, SchemaSlice slice, String sql,
                              String semanticSection, ProgressListener listener, ExecStats stats) {
        try {
            listener.onStage(ProgressListener.STAGE_VERIFY, "辅助模型交叉校验 SQL…");
            // 只给“表名 + 注释”时辅助模型无法复核列名与 JOIN 键（多表问数最容易错的地方），
            // 故改用与主生成完全一致的表结构文本，并附上可用关联关系
            String schemaText = slice.toPromptText();
            String relations = slice.toRelationsText();
            String model = aibiConfig.crossCheckModel();
            if (model.isBlank()) {
                model = aibiConfig.aiAnalysisModel();
            }
            if (model.isBlank()) {
                log.debug("辅助模型交叉校验：未指定 cross-check-model 且无 analysis-model，跳过");
                return null;
            }
            String prompt = "你是一名严谨的 SQL 评审专家，负责交叉校验另一条模型生成的 SQL 是否正确。\n"
                    + "数据库类型：" + config.type() + "。\n"
                    + "相关表结构（列后的“主键”/“外键→表.列”为库中真实约束）：\n" + schemaText + "\n"
                    + (relations.isBlank() ? "" : "可用关联关系：\n" + relations + "\n")
                    + (semanticSection == null || semanticSection.isBlank() ? "" : "业务语义层：\n" + semanticSection + "\n")
                    + "用户问题：" + question + "\n"
                    + "候选 SQL：\n" + sql + "\n"
                    + "请校验：① 表名 / 列名是否真实存在于上面的表结构；"
                    + "② 多表 JOIN 是否用了上面列出的关联键（错连、漏连、自造连接键、逗号连接都要改）；"
                    + "③ 一对多关联后直接聚合是否会重复计数（需要时改为子查询先聚合或 COUNT(DISTINCT 主键)）；"
                    + "④ 聚合、过滤条件是否合理、能否正确回答用户问题。\n"
                    + "若正确，请原样输出该 SQL；若有错误（表名/列名拼错、错连、缺 GROUP BY、过滤条件反了等），请输出修正后的 SQL。\n"
                    + "只输出最终的一条 SELECT，不要任何解释，不要代码围栏之外的文字。\n";
            String aux = llm.chatWithModel(prompt, model);
            if (aux == null || aux.isBlank()) {
                return null; // 无辅助模型 / 链路中无该模型 → 跳过
            }
            return sqlValidator.validate(aux); // 安全校验，非法返回 null → 保留原 SQL
        } catch (RuntimeException e) {
            log.warn("辅助模型交叉校验失败，保留原 SQL：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 双生成共识（cross-consistency，提升问数准确率）：主模型已生成 {@code mainSql} 后，用<b>指定辅助模型</b>
     * 在<b>完全相同</b>的 Prompt 下独立再生成一条候选（跨模型视角），归一化比对：
     * <ul>
     *   <li>一致 → 标记高置信，直接采纳主 SQL（不变化）；</li>
     *   <li>不一致 → 由辅助模型当裁判，在一行内选更合理的 A（主）/ B（辅助）采纳。</li>
     * </ul>
     * 整段<b>永不抛出</b>——任何异常（缺失辅助模型 / 调用失败 / 辅助生成非法 / 裁决失败）一律返回
     * {@code null}，由主链路保留原 SQL（等价于不启用本模式）。
     *
     * @param prompt 主生成所用的完整 Prompt（复用以保证双生成处于完全同等条件，暴露真实分歧）
     * @return 经裁决采纳的 SQL（可能与 {@code mainSql} 相同），或 {@code null} 表示跳过
     */
    private String crossConsistency(String question, SchemaSlice slice, String mainSql,
                                    String prompt, ProgressListener listener, ExecStats stats) {
        try {
            listener.onStage(ProgressListener.STAGE_VERIFY, "辅助模型双生成共识校验…");
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return null;
            }
            String auxSql = llm.chatWithModel(prompt, model);
            if (auxSql == null || auxSql.isBlank()) {
                return null;
            }
            auxSql = sqlValidator.validate(auxSql);
            if (auxSql == null) {
                return null; // 辅助生成非法，放弃共识
            }
            if (normalizeSql(auxSql).equals(normalizeSql(mainSql))) {
                stats.setCrossConsistent(true);
                log.info("cross-consistency：主辅模型生成一致，高置信采纳");
                return mainSql;
            }
            // 不一致：让辅助模型当裁判，在一行内选 A（主）或 B（辅助）更合理
            listener.onStage(ProgressListener.STAGE_VERIFY, "双生成结果不一致，正在进行一致性裁决…");
            String judge = llm.chatWithModel(
                    "下面是同一个问题由两条模型各自独立生成的 SQL，请判断哪一条更可能正确回答该问题。\n"
                            + "用户问题：" + question + "\n"
                            + "A：\n" + mainSql + "\nB：\n" + auxSql + "\n"
                            + "只回复一行：以「A」或「B」开头，后接极简理由（不超过 30 字）。不要输出 SQL。\n",
                    model);
            boolean pickAux = judge != null && judge.trim().toUpperCase(Locale.ROOT).startsWith("B");
            stats.setCrossConsistent(false);
            log.info("cross-consistency 不一致，裁决={}（理由：{}）", pickAux ? "B(辅助)" : "A(主)", judge);
            return pickAux ? auxSql : mainSql;
        } catch (RuntimeException e) {
            log.warn("cross-consistency 失败，保留主 SQL：{}", e.getMessage());
            return null;
        }
    }

    /**
     * Schema 选表交叉复核（V5-B，提升问数准确率）：用<b>指定辅助模型</b>基于全量表的"表名: 注释"目录
     * 独立重挑 1~5 张相关表，与主链路选出的 {@code base} 切片<b>取并集</b>（仅保留库中真实存在的表名），
     * 从源头减少「子串打分退化 / 中文问法大库」场景下的选表遗漏。
     * 整段<b>永不抛出</b>——任何异常 / 无辅助模型 / 未新增表一律返回 {@code null}，由主链路保留原切片。
     */
    private SchemaSlice schemaCrossCheck(List<TableMeta> tables, String question, SchemaSlice base,
                                         ProgressListener listener) {
        try {
            listener.onStage(ProgressListener.STAGE_SCHEMA, "辅助模型交叉复核选表…");
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return null;
            }
            StringBuilder catalog = new StringBuilder();
            Map<String, TableMeta> byName = new HashMap<>();
            for (TableMeta t : tables) {
                catalog.append(t.name())
                        .append(t.comment() == null || t.comment().isBlank() ? "" : ": " + t.comment())
                        .append('\n');
                byName.put(t.name().toLowerCase(Locale.ROOT), t);
            }
            String prompt = "你是数据库专家。下面列出数据源全部表（表名: 表注释）。\n"
                    + catalog
                    + "\n用户问题：" + question + "\n"
                    + "请选出回答用户问题所需的表（1~5 张）。只输出表名，逗号分隔，不要任何解释。\n";
            String answer = llm.chatWithModel(prompt, model);
            if (answer == null || answer.isBlank()) {
                return null;
            }
            List<TableMeta> merged = new ArrayList<>(base.tables());
            Set<String> seen = new HashSet<>();
            for (TableMeta t : base.tables()) {
                seen.add(t.name().toLowerCase(Locale.ROOT));
            }
            int added = 0;
            for (String tok : answer.split("[,，;；\\s]+")) {
                String clean = tok.trim().replace("`", "").toLowerCase(Locale.ROOT);
                TableMeta t = byName.get(clean);
                if (t != null && !seen.contains(clean) && merged.size() < SchemaLinker.MAX_TABLES_IN_PROMPT) {
                    merged.add(t);
                    seen.add(clean);
                    added++;
                }
            }
            if (added == 0) {
                return null;
            }
            log.info("辅助模型选表复核：主切片 {} 张 + 辅助补 {} 张 = {}", base.tables().size(), added, merged.size());
            // withTables：补进来的表也要能参与关联推导，故沿用主切片的关联策略
            return base.withTables(merged);
        } catch (RuntimeException e) {
            log.warn("辅助模型选表复核失败，保留主切片：{}", e.getMessage());
            return null;
        }
    }

    /** 解析辅助模型名：优先 {@code cross-check-model}，留空复用 {@code analysis-model}；二者皆空返回 null（调用方跳过）。 */
    private String resolveAuxModel() {
        String m = aibiConfig.crossCheckModel();
        if (m == null || m.isBlank()) {
            m = aibiConfig.aiAnalysisModel();
        }
        return m == null ? null : m;
    }

    /**
     * 修复二次复核（V5-F，提升问数准确率）：把上一条失败 SQL 与报错信息交<b>辅助模型</b>复核，
     * 让其判断「修复后的 SQL 是否仍可能触发同类错误 / 仍回答问题」，若仍可能则返回下一步修正方向（一句话）。
     * 该方向被回喂到下一次 {@code SqlRepairer.repair} 的报错文本，使修复更有的放矢。
     * 整段<b>永不抛出</b>——任何异常 / 无辅助模型 / 判为已修复一律返回 {@code null}（不附加方向）。
     */
    private String repairCrossCheck(String question, String failedSql, String errorMsg, ProgressListener listener) {
        try {
            String model = resolveAuxModel();
            if (model == null || model.isBlank()) {
                return null;
            }
            String judge = llm.chatWithModel(
                    "你是 SQL 修复复核专家。上一条 SQL 执行报错：\n" + errorMsg + "\n"
                            + "准备用于修复的 SQL：\n" + failedSql + "\n"
                            + "用户问题：" + question + "\n"
                            + "请判断：这条修复后的 SQL 是否仍可能触发同类错误（如表/列不存在、语法错、聚合/分组缺失）？"
                            + "若仍可能，请给出下一步最应该修正的方向（一句话，不要输出 SQL）；"
                            + "若已修复则只回复「已修复」。\n",
                    model);
            if (judge == null) {
                return null;
            }
            String j = judge.trim();
            // 防御：辅助调用失败时 chatWithModel 会回落主模型、可能返回一整段 SQL 而非"方向"，
            // 这种情形下不应把它回喂成修复报错文本（会污染下一轮修复）。仅接受"已修复"或简短方向文本。
            if (j.length() > 120 || j.toLowerCase(Locale.ROOT).contains("select ")
                    || j.toLowerCase(Locale.ROOT).contains(" from ") || j.startsWith("update")
                    || j.startsWith("delete") || j.startsWith("insert")) {
                return null;
            }
            if (j.startsWith("已修复") || j.toLowerCase(Locale.ROOT).contains("已修复")) {
                return null;
            }
            return j;
        } catch (RuntimeException e) {
            log.debug("修复复核辅助调用失败，跳过：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 重试选表（校验闭环专用）：把全量表的"表名: 注释"紧凑目录交给 LLM 选 1~5 张，
     * 避免子串打分退化导致重试仍只看错表；目录仅含名字与注释，token 成本可控且仅重试触发。
     * 解析失败/无命中返回 {@code null}，由调用方回落子串裁剪。
     */
    private SchemaSlice linkByLlm(List<TableMeta> tables, String question, String retryHint, RelationHints hints) {
        try {
            StringBuilder catalog = new StringBuilder();
            Map<String, TableMeta> byName = new HashMap<>();
            for (TableMeta t : tables) {
                catalog.append(t.name())
                        .append(t.comment() == null || t.comment().isBlank() ? "" : ": " + t.comment())
                        .append('\n');
                byName.put(t.name().toLowerCase(Locale.ROOT), t);
            }
            String prompt = "你是数据库专家。下面列出数据源全部表（表名: 表注释）。\n"
                    + catalog
                    + "\n用户问题：" + question
                    + (retryHint == null || retryHint.isBlank() ? ""
                            : "\n质量校验反馈（上一轮 SQL 未回答问题的原因）：\n" + retryHint)
                    + "\n请选出回答用户问题所需的表（1~5 张），只输出表名，逗号分隔，不要任何解释。\n";
            String answer = llm.chat(prompt);
            List<TableMeta> picked = new ArrayList<>();
            for (String tok : (answer == null ? "" : answer).split("[,，;；\\s]+")) {
                String clean = tok.trim().replace("`", "").toLowerCase(Locale.ROOT);
                TableMeta t = byName.get(clean);
                if (t != null && picked.size() < 5 && !picked.contains(t)) {
                    picked.add(t);
                }
            }
            if (picked.isEmpty()) {
                return null;
            }
            log.info("重试 LLM 选表：{}", picked.stream().map(TableMeta::name).toList());
            RelationHints rh = hints == null ? RelationHints.none() : hints;
            return new SchemaSlice(picked, tables.size(), true, false, rh.infer(), rh.manual());
        } catch (RuntimeException e) {
            log.debug("重试 LLM 选表失败，回落子串裁剪：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 执行 SQL；若因 SQL 本身问题失败，则回喂报错让 LLM 修正后重试，最多 {@link AibiConfig#maxRepair()} 次。
     * 查询超时（QUERY_TIMEOUT）等非"SQL 写法"问题不做修正，直接抛出。
     * 执行成功但 0 行时，若开关开启则以"放宽过滤/核对枚举"修正重试一次（仍 0 行则如实返回）。
     */
    private QueryResult executeWithRepair(DataSourceAdapter adapter, DataSourceConfig config,
                                          SchemaSlice slice, String question, String sql,
                                          String semanticSection, ProgressListener listener,
                                          ExecStats stats) {
        String current = sql;
        boolean emptyRetried = false;
        int maxRepair = aibiConfig.maxRepair();
        for (int attempt = 0; ; attempt++) {
            try {
                // 表级授权（v2）：SQL 执行前硬拦截指向隐藏表的查询，防"手填/生成"的 SQL 绕过 schema 层省略
                dataMaskGate.assertTablesAllowed(config.id(), current);
                QueryResult result = adapter.execute(config, current, maxRows, timeoutSeconds);
                // 回填执行元信息（V3 数据完整度）：尝试次数 + 是否触顶行数上限（近似截断）
                stats.setSqlAttempts(attempt + 1);
                if (result.rowCount() >= maxRows) {
                    stats.setTruncated(true);
                }
                // 空结果自修复：仅重试一次；修复失败/SQL 未变化则如实返回原结果
                if (result.rowCount() == 0 && !emptyRetried && aibiConfig.emptyResultRepair()) {
                    emptyRetried = true;
                    try {
                        listener.onStage(ProgressListener.STAGE_REPAIR, "查询结果为空，正在放宽条件重试…");
                        String relaxed = sqlRepairer.repairEmpty(config, slice, question, current, semanticSection);
                        if (relaxed != null && !relaxed.equalsIgnoreCase(current)) {
                            stats.setEmptyRetried(true);
                            current = relaxed;
                            listener.onStage(ProgressListener.STAGE_EXECUTE, "重新执行放宽后的查询…");
                            attempt--;
                            continue;
                        }
                    } catch (RuntimeException re) {
                        log.warn("空结果自修复失败，返回原空结果：{}", re.getMessage());
                    }
                }
                return result;
            } catch (BizException e) {
                boolean repairable = e.getErrorCode() == ErrorCode.SQL_GENERATE_FAILED && attempt < maxRepair;
                if (!repairable) {
                    throw e;
                }
                log.warn("SQL 执行失败，进行第 {} 次修正。原因：{}", attempt + 1, e.getMessage());
                stats.setRepaired(true);
                listener.onStage(ProgressListener.STAGE_REPAIR,
                        "查询报错，正在自动修正 SQL（第 " + (attempt + 1) + " 次）");
                // ⑨-F 修复二次复核（V5-F）：把上一条失败 SQL 与报错交辅助模型复核，
                //    让其判断错误是否真正解决 / 仍回答问题，并把修正方向回喂下一次修复；永不抛错。
                String errMsg = e.getMessage();
                if (aibiConfig.repairCrossCheckEnabled()) {
                    String dir = repairCrossCheck(question, current, errMsg, listener);
                    if (dir != null && !dir.isBlank()) {
                        errMsg = errMsg + "\n辅助复核修正方向：" + dir;
                    }
                }
                current = sqlRepairer.repair(config, slice, question, current, errMsg);
                listener.onStage(ProgressListener.STAGE_EXECUTE, "重新执行修正后的查询…");
            }
        }
    }

    /**
     * self-consistency 多路生成择优（V3，问数准确率）：以措辞变体诱发多样性，生成 N 条候选 SQL，
     * 逐条经 {@link SqlValidator} 校验后按"结构多数投票"择优选 1 条。全部候选失败时回落单次生成
     * （保持原有报错语义）；单条有效时直接采用。单候选异常仅记录不抛出，绝不影响主链路。
     */
    private String generateWithConsistency(String basePrompt, int samples,
                                           ProgressListener listener, ExecStats stats) {
        listener.onStage(ProgressListener.STAGE_CONSISTENCY,
                "正在多路生成候选 SQL（self-consistency ×" + samples + "）…");
        List<String> valid = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            try {
                String prompt = (i == 0) ? basePrompt : variantPrompt(basePrompt, i);
                String sql = sqlValidator.validate(llm.chat(prompt));
                if (sql != null && !sql.isBlank()) {
                    valid.add(sql);
                }
            } catch (RuntimeException e) {
                log.debug("self-consistency 候选 {} 生成/校验失败：{}", i + 1, e.getMessage());
            }
        }
        stats.setConsistencyCandidates(valid.size());
        if (valid.isEmpty()) {
            log.warn("self-consistency 无有效候选，回落单次生成");
            return sqlValidator.validate(llm.chat(basePrompt));
        }
        String picked = valid.size() == 1 ? valid.get(0) : majorityVote(valid);
        listener.onStage(ProgressListener.STAGE_CONSISTENCY, "多路候选 " + valid.size() + " 条，已择优");
        log.info("self-consistency：{} 条有效候选，择优 SQL: {}", valid.size(), picked);
        return picked;
    }

    /**
     * 措辞变体：在保证语义正确前提下引导 LLM 独立重组查询，诱发多样候选（温度不可控时的等价手段）。
     *
     * <p>多表问数下<b>不再鼓励“换连接顺序”</b>：连接键由【关联关系】段（外键推导）给定，
     * 是确定信息而非可选项；让候选在连接键上也发散只会把“错连”混进投票池，反而拉低
     * self-consistency 的择优质量。变体只允许在聚合写法、过滤表达与子查询组织上发散。
     */
    private String variantPrompt(String basePrompt, int variant) {
        return basePrompt + "\n\n（这是第 " + variant + " 种独立思路：请在保证语义完全正确的前提下，"
                + "重新组织这条查询——可考虑不同的聚合写法、过滤表达或子查询组织方式；"
                + "若上文给出了【关联关系】，连接键必须沿用其中列出的键、不得替换或省略；仍只输出最终的一条 SELECT。）";
    }

    /** 结构多数投票：按归一化 SQL 文本聚类，取出现次数最多者（并列时取最先出现，稳定可复现）。 */
    private String majorityVote(List<String> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> original = new HashMap<>();
        for (String sql : candidates) {
            String key = normalizeSql(sql);
            counts.merge(key, 1, Integer::sum);
            original.putIfAbsent(key, sql);
        }
        String bestKey = null;
        int best = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestKey = e.getKey();
            }
        }
        return original.get(bestKey);
    }

    /** SQL 归一化（投票聚类用）：小写、压缩空白、去分号，消除纯格式差异。 */
    private String normalizeSql(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replace(";", "").trim();
    }
}
