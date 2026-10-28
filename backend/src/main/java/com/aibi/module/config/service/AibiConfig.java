package com.aibi.module.config.service;

import com.aibi.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 平台配置门面（配置化升级 V2）：把散落在 {@code application.yml} 与组件常量里的"可变策略"
 * 统一收口为类型化读取点，<b>DB 配置优先、yml/代码默认兜底</b>。
 *
 * <p>业务组件（Nl2SqlEngine / ChatService / ChartRecommender / 前端结果面板）只依赖本门面，
 * 不感知配置来自 sys_config 还是 yml；超管/租户在管理页改配置即秒级生效（ConfigService 缓存失效）。
 *
 * <p>category 约定：{@code NL2SQL}=引擎策略、{@code CHART}=图表推荐、{@code RESULT}=问数结果展示、
 * {@code STYLE}=样式、{@code SYSTEM}=平台级。
 */
@Component
public class AibiConfig {

    public static final String CAT_NL2SQL = "NL2SQL";
    public static final String CAT_CHART = "CHART";
    public static final String CAT_RESULT = "RESULT";
    public static final String CAT_STYLE = "STYLE";
    public static final String CAT_SYSTEM = "SYSTEM";
    /** V3 AI 分析层：图表决策 / 结果解读 / 质量校验的开关与模型覆盖。 */
    public static final String CAT_AI = "AI";
    /** V4 问数技能：各答问技能（直连 SQL / 指标直达 / 样例复用 / 智能生成）的启停。 */
    public static final String CAT_ASK = "ASK";
    /** 全局操作日志审计：请求级自动捕获的启停（V4.4）。 */
    public static final String CAT_AUDIT = "AUDIT";
    /** V5 归因分析 + 数据血缘：模块启停与 SQL 合规开关。 */
    public static final String CAT_ATTRIBUTION = "ATTRIBUTION";
    /** 数据新鲜度（P2）：SLA 阈值小时数。 */
    public static final String CAT_FRESHNESS = "FRESHNESS";
    /** 指标后台自动分析（Skill）：扫描库表结构自动识别指标 / 维度并建指标。 */
    public static final String CAT_METRIC = "METRIC";
    /** 驾驶舱 AI 自动化大屏（Skill）：基于自动生成的指标自动编排图表与布局。 */
    public static final String CAT_COCKPIT = "COCKPIT";

    /**
     * Prompt "严格要求"默认条款（可被 sys_config NL2SQL/prompt.rules 覆盖/增删）。
     *
     * <p>第三条针对多表问数：错连与 fan-out（一对多关联后直接求和）是多表场景下最主要的失分与
     * “静默错数”来源，与 Prompt 里由外键推导的【关联关系】段（见 {@code PromptBuilder}）相互印证。
     * 默认值必须与 {@code ConfigCatalogSeeder#buildConfigCatalog()} 里 prompt.rules 的目录默认保持一致。
     */
    public static final List<String> DEFAULT_PROMPT_RULES = List.of(
            "\"销售额/金额\"通常用金额字段或 单价*数量；涉及聚合必须写 GROUP BY。",
            "涉及\"已支付/有效订单\"等语义时用对应状态字段过滤（如 status='paid'）。",
            "多表查询必须用明确的关联键 JOIN，禁止逗号连接与笛卡尔积；"
                    + "一对多关联后要先去重或先按主表粒度在子查询里聚合，避免金额/计数被放大。");

    private final ConfigService configService;

    private final int defFewShotLimit;
    private final int defMaxRepair;
    private final int defConversationTurns;

    public AibiConfig(ConfigService configService,
                      @Value("${aibi.nl2sql.few-shot-limit:3}") int defFewShotLimit,
                      @Value("${aibi.nl2sql.max-repair:2}") int defMaxRepair,
                      @Value("${aibi.nl2sql.conversation-turns:3}") int defConversationTurns) {
        this.configService = configService;
        this.defFewShotLimit = defFewShotLimit;
        this.defMaxRepair = defMaxRepair;
        this.defConversationTurns = defConversationTurns;
    }

    // ==================== NL2SQL 引擎策略 ====================

    /** 少样本注入条数。 */
    public int fewShotLimit() {
        return configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "few-shot-limit", defFewShotLimit);
    }

    /** 执行报错后 LLM 自动修正的最大重试次数。 */
    public int maxRepair() {
        return configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "max-repair", defMaxRepair);
    }

    /** 多轮对话注入 Prompt 的最近历史轮数。 */
    public int conversationTurns() {
        return configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "conversation-turns", defConversationTurns);
    }

    /** 空结果自修复开关：0 行时以"放宽过滤/核对枚举"修正 Prompt 重试一次。 */
    public boolean emptyResultRepair() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "empty-result-repair", true);
    }

    /** self-consistency 开关（V3）：开则多路生成候选 SQL 后择优，提升准确率（代价是更多 LLM 调用）。 */
    public boolean selfConsistency() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "self-consistency", true);
    }

    /** self-consistency 候选 SQL 生成路数（V3）；≤1 等价于关闭多路。 */
    public int consistencySamples() {
        return configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "consistency-samples", 3);
    }

    /** Prompt "严格要求"业务规则清单（DB 可配置化增删改）。 */
    public List<String> promptRules() {
        return configService.getStringList(TenantContext.tenantId(), CAT_NL2SQL,
                "prompt.rules", DEFAULT_PROMPT_RULES);
    }

    /**
     * NL2SQL 当前日期锚点开关（{@code ASK / time-anchor}）：开启后在生成 SQL 的 Prompt 里注入
     * 「【当前日期】YYYY-MM-DD（周X）」一行，引导大模型把"今天/昨天/本周/上周/本月/上月/本季度/今年/最近N天"
     * 等相对时间<b>换算成绝对日期</b>写入 WHERE，而不是臆测年份或依赖方言日期函数。
     *
     * <p><b>默认开启</b>：纯文本几 token、不增任何 LLM 调用；对全体带时间的问数都是通用正确性修复，
     * 也是"本月销售报告"这类对话内报告可用的硬前提。仅当库内以相对日期函数为准、不希望出现绝对日期字面量时关闭。
     */
    public boolean timeAnchorEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "time-anchor", true);
    }

    /**
     * 关联命名推断开关（多表问数准确率）：开则在库中外键之外，再按 {@code order_id → orders.id}
     * 这类命名与类型约定推断表间连接键（见 {@code JoinGraph}）。
     *
     * <p><b>默认开启</b>：业务库与分析型库普遍不建 FOREIGN KEY，只靠外键时多表问数拿不到任何连接键，
     * LLM 只能自造 join 条件（错连）或漏掉维度表。推断结果在 Prompt 里单独标注为「按命名推断、未必准确」，
     * 不会当成硬约束；库命名极不规范时可在「问数调优 → 配置中心」关掉。
     */
    public boolean relationInfer() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "relation-infer", true);
    }

    /**
     * JOIN 静态校验开关（多表问数准确率）：开则在执行<b>前</b>用 {@code JoinGuard} 解析 SQL 的连接结构，
     * 发现笛卡尔积、连接键与关联图谱矛盾、两表同名主键互连时回喂模型修正一次。
     *
     * <p><b>默认开启</b>：错连不会报错——SQL 跑通、结果非空、行数还挺多，只有数值是错的，
     * 执行期报错修正与空结果自修复都抓不到它；本校验零模型成本、只在有确凿证据时才介入。
     * 解析不了（方言专有语法、中文标识符、UNION、FROM 子查询）一律放行，不会误伤合法 SQL。
     */
    public boolean joinGuardEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "join-guard", true);
    }

    /**
     * 少样本「意图对齐」微调开关（提升问数准确率，Phase-1）：开启后，运行时识别的意图与样例库里标注的
     * 意图做<b>双向对齐</b>——同意图样例提权、异意图样例降权（仅微调、不替代词面重合度主信号）。
     *
     * <p><b>默认开启</b>：在不增加任何 LLM 调用的前提下，让"意图相符"的样例更易被注入、把词面巧合但
     * 意图南辕北辙的样例压下去，提升首次命中率。关闭则完全退回当前行为（仅词面重合度排序）。
     * 旧的、未标注意图的样例（{@code few_shot_example.intent} 为 null）不受对齐影响——双向调整对其恒为 0，
     * 向后兼容；历史问法（{@code query_history} 无 intent 列）同理只靠词面主信号。
     */
    public boolean fewShotIntentBoost() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "fewshot-intent-boost", true);
    }

    /**
     * 少样本「意图对齐」微调强度（仅当 {@link #fewShotIntentBoost()} 开启时生效）：同意图样例的加分值、
     * 异意图样例的减分值（= 该值的一半）。
     *
     * <p>影响：值越大，意图对齐对排序的影响越强；建议 2~6，默认 4。设 0 等同关闭对齐（调整恒为 0）。
     * 负值按 0 处理，避免把意图对齐变成纯噪声。
     */
    public int fewShotIntentWeight() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "fewshot-intent-weight", 4);
        return v < 0 ? 0 : v;
    }

    // ==================== 执行前代价预估熔断（T10-1：扫全表/笛卡尔/超预算在库之前拦） ====================

    /**
     * 代价预估总开关（{@code NL2SQL / cost-guard}）：开启后所有技能的 SQL 在 {@code JdbcDataSourceAdapter.execute}
     * 下发数据库<b>之前</b>先由 {@code HeuristicCostGuard} 做一次静态代价预估，对「大概率扫全表 / 笛卡尔爆炸 /
     * 预估扫描行超预算」的查询主动拒绝并给拆分建议。
     *
     * <p><b>纯确定性、零 LLM、默认开</b>：解析不了（方言专有语法/中文标识符/UNION/子查询）、行数取不到一律放行，
     * 绝不误伤合法 SQL；关闭则 {@code assess} 恒返回放行，行为与未接入本特性逐字节等价。
     */
    public boolean costGuardEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "cost-guard", true);
    }

    /**
     * 阻断开关（{@code NL2SQL / cost-block}）：开则命中高危直接拒绝（REJECT）；关闭则高危仅告警（WARN）不阻断。
     * 供灰度上线与排障——先观测误判率再决定是否真拦。默认开。
     */
    public boolean costBlockEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "cost-block", true);
    }

    /** REJECT 扫描行上限（{@code NL2SQL / cost-scan-rows-threshold}）：预估扫描行超过此值即拒绝。默认 5000 万。 */
    public int costScanRowsThreshold() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "cost-scan-rows-threshold", 50_000_000);
        return v <= 0 ? 50_000_000 : v;
    }

    /** WARN 扫描行下限（{@code NL2SQL / cost-warn-rows-threshold}）：预估扫描行超过此值但未超 REJECT 阈值则仅告警。默认 1000 万。 */
    public int costWarnRowsThreshold() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "cost-warn-rows-threshold", 10_000_000);
        return v < 0 ? 10_000_000 : v;
    }

    /** 大表界定（{@code NL2SQL / cost-big-table-rows}）：单表无 WHERE 且行数超过此值即判全表扫描高危。默认 500 万。 */
    public int costBigTableRows() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "cost-big-table-rows", 5_000_000);
        return v <= 0 ? 5_000_000 : v;
    }

    /**
     * WHERE 选择性折扣（{@code NL2SQL / cost-selectivity}，百分比）：单表有 WHERE 时按 {@code 行数 × 该值/100}
     * 估扫描量（10 即 ×0.1）。越小表示假定过滤越强。默认 10，clamp 到 0~100。
     */
    public int costSelectivity() {
        return configService.getInt(TenantContext.tenantId(), CAT_NL2SQL, "cost-selectivity", 10);
    }

    // ==================== 图表推荐 ====================

    /** 饼图最大切片数（类别过多改柱状图）。 */
    public int maxPieSlices() {
        return configService.getInt(TenantContext.tenantId(), CAT_CHART, "max-pie-slices", 8);
    }

    /** 单图最多展示的度量数。 */
    public int maxMeasures() {
        return configService.getInt(TenantContext.tenantId(), CAT_CHART, "max-measures", 5);
    }

    // ---------- 图表标注默认值（租户级）----------
    // 这几项是“渲染时读取”而非“写入每个指标的 chart_spec”：改配置即全租户图表生效，
    // 不会变成改不动的历史快照；指标自带的 style 优先级高于此处的默认值。

    /**
     * 图表数值格式默认值：{@code COMPACT} 万亿紧凑（1500000 → 150万）/ {@code THOUSAND} 千分位 /
     * {@code PLAIN} 原值。窄 widget 下紧凑格式可避免长数字拥挤或被裁切。
     */
    public String chartValueFormat() {
        return configService.getString(TenantContext.tenantId(), CAT_CHART, "value-format", "COMPACT");
    }

    /** 图表图例默认位置：{@code top}/{@code bottom}/{@code left}/{@code right}/{@code hidden}。 */
    public String chartLegendPosition() {
        return configService.getString(TenantContext.tenantId(), CAT_CHART, "legend-position", "bottom");
    }

    /** 图表数值默认小数位；{@code -1} 表示自动（整数原样、小数至多 2 位）。 */
    public int chartValueDecimals() {
        return configService.getInt(TenantContext.tenantId(), CAT_CHART, "value-decimals", -1);
    }

    /** 度量单位自动推断开关：开则按语义层 {@code unit} → 列名关键词推断，关闭后仅用户手配生效。 */
    public boolean chartAutoUnit() {
        return configService.getBool(TenantContext.tenantId(), CAT_CHART, "auto-unit", true);
    }

    // ==================== 问数结果展示 ====================

    /** 结果面板默认视图：chart / table。 */
    public String resultDefaultView() {
        return configService.getString(TenantContext.tenantId(), CAT_RESULT, "default-view", "chart");
    }

    /** 结果面板最多展示行数（超出前端截断提示）。 */
    public int resultMaxDisplayRows() {
        return configService.getInt(TenantContext.tenantId(), CAT_RESULT, "max-display-rows", 500);
    }

    /** 数值格式化策略：raw / thousand（千分位）/ percent 等，前端据此格式化度量列。 */
    public String numberFormat() {
        return configService.getString(TenantContext.tenantId(), CAT_RESULT, "number-format", "thousand");
    }

    // ==================== AI 分析层（V3：图表决策 / 结果解读 / 质量校验） ====================

    /** AI 图表决策开关：开则由 LLM 依"问题 + 结果结构 + 语义列角色"选图，规则引擎兜底与结构校验。 */
    public boolean aiChartAdvisor() {
        return configService.getBool(TenantContext.tenantId(), CAT_AI, "chart-advisor", true);
    }

    /** AI 结果解读开关：开则由 LLM 生成自然语言洞察（结论/要点/异常）随结果返回。 */
    public boolean aiInsight() {
        return configService.getBool(TenantContext.tenantId(), CAT_AI, "insight", true);
    }

    /** AI 质量校验开关：开则由 LLM 复核结果是否回答问题、数值是否异常，并给出置信度。 */
    public boolean aiVerify() {
        return configService.getBool(TenantContext.tenantId(), CAT_AI, "verify", true);
    }

    /**
     * AI 布局与样式顾问开关（{@code AI / layout-advisor}）：开则驾驶舱「AI 智能设计」由大模型
     * 依据各组件的数据形态与语义给出布局 + 视觉样式建议；失败或未配置时自动回退确定性算法方案。
     *
     * <p>关闭场景：未配置大模型、不希望产生额外调用，或希望布局完全由确定性规则决定（结果可预期、零延迟）。
     */
    public boolean aiLayoutAdvisor() {
        return configService.getBool(TenantContext.tenantId(), CAT_AI, "layout-advisor", true);
    }

    /** AI 分析环节使用的模型覆盖（空=复用主问数模型路由）。 */
    public String aiAnalysisModel() {
        return configService.getString(TenantContext.tenantId(), CAT_AI, "analysis-model", "");
    }

    /** 辅助模型交叉校验总开关（V5，提升问数准确率）：开则把候选 SQL 交另一模型复核修正。 */
    public boolean crossCheckEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "cross-check", false);
    }

    /**
     * 辅助交叉校验使用的模型（模型名，须已在 {@code llm_config} 中配置；大小写不敏感包含匹配）。
     * 留空 → 复用 {@link #aiAnalysisModel()}；二者皆空 → 不启用交叉校验（与 {@link #crossCheckEnabled()} 配合）。
     */
    public String crossCheckModel() {
        return configService.getString(TenantContext.tenantId(), CAT_AI, "cross-check-model", "");
    }

    // ==================== 辅助模型交叉复核（V5 提准确率系列，全部配置门控、永不抛错） ====================

    /** B. Schema 选表/列交叉复核开关：开则在生成 SQL 前用辅助模型独立重挑相关表，与主切片取并集（仅含合法表名）。 */
    public boolean schemaCrossCheckEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "schema-cross-check", false);
    }

    /** D. 答非所问结果接地复核开关：开则在结果返回后用辅助模型判定是否真回答问题，判否则驱动一次重生成。 */
    public boolean groundednessCheckEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "groundedness-check", false);
    }

    /** cross-consistency 双生成共识开关：开则主模型与辅助模型各自独立生成 SQL 后归一化比对，一致高置信采纳、不一致裁决择优。 */
    public boolean crossConsistencyEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "cross-consistency", false);
    }

    /** F. 修复二次复核开关：开则每次执行修复后由辅助模型验证错误是否真正解决、仍回答问题，并把方向回喂下一次修复。 */
    public boolean repairCrossCheckEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_NL2SQL, "repair-cross-check", false);
    }

    /** A. 技能路由复核开关：开则用辅助模型独立判定该走哪条技能，与路由分歧时回落更保守的确定性（免 LLM）技能。 */
    public boolean routeCrossCheckEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "route-cross-check", false);
    }

    /** E. 追问改写复核开关：开则用辅助模型校验改写是否真正消解指代、未臆造字段，判否则回落原始提问。 */
    public boolean rewriteCrossCheckEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "rewrite-cross-check", false);
    }

    // ==================== 指标自动分析 + 驾驶舱 AI 自动化大屏 ====================

    /**
     * 指标后台自动分析 Skill 总开关（{@code METRIC / auto-analysis}）：开则由 Skill 扫描库表结构，自动识别
     * 指标 / 维度、生成多级目录并打上 AI 自动识别标识，同时自动建指标；关闭则不在 init 自动跑、入口按钮隐藏。
     */
    public boolean metricAutoAnalysisEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_METRIC, "auto-analysis", true);
    }

    // ==================== 问题沉淀（T11-1：把反复问错/报错/低分的问法聚合成治理清单） ====================

    /**
     * 问题沉淀总开关（{@code METRIC / issue-distill}）：开启后「问数调优中心」展示「待治理问题」tab，
     * 可按时间窗把 {@code query_history} 聚合成「该补哪条指标口径/术语/少样本」的建议清单。
     *
     * <p><b>纯只读、零 LLM</b>：不改库、不碰问数主链路；关闭则返回空报告、tab 空态。默认开。
     */
    public boolean issueDistillEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_METRIC, "issue-distill", true);
    }

    /**
     * 待治理清单「转写」开关（{@code METRIC / issue-distill-convert}）：开则在「待治理问题」tab 每行提供
     * 「转指标 / 转少样本」入口，人工校对 SQL 后走既有 {@code POST /api/metric}、{@code /api/fewshot} 沉淀。
     *
     * <p>仍守「只建议不自动改」红线：写入前强制人工确认 SQL，绝不自动存失败草稿。关闭则仅只读展示。默认开。
     */
    public boolean issueDistillConvertEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_METRIC, "issue-distill-convert", true);
    }

    /**
     * 待治理问题推送开关（{@code METRIC / issue-push}）：开启后定时扫描新增未推送的 OPEN 治理问题，
     * 按租户聚合为一条站内信送达管理角色（复用 {@code notify_message} + 消息中心）。
     *
     * <p>默认<b>关闭</b>（opt-in，避免升级即骚扰）；去重闸由台账 {@code notified_at} 控制。
     */
    public boolean issuePushEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_METRIC, "issue-push", false);
    }

    /** 问题沉淀聚合时间窗（天，{@code METRIC / issue-distill-window-days}）：早于窗口的历史不计入。默认 30。 */
    public int issueDistillWindowDays() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "issue-distill-window-days", 30);
        return v <= 0 ? 30 : v;
    }

    /** S2 低质量分阈值（{@code METRIC / issue-low-score-threshold}，0~100）：OK 但校验分低于此值计入低分簇。默认 60。 */
    public int issueLowScoreThreshold() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "issue-low-score-threshold", 60);
        return (v < 0 || v > 100) ? 60 : v;
    }

    /** 成簇最少出现次数（{@code METRIC / issue-min-occurrences}）：高频失败/低分簇的降噪下限。默认 3。 */
    public int issueMinOccurrences() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "issue-min-occurrences", 3);
        return v <= 0 ? 3 : v;
    }

    /** 治理清单条数上限（{@code METRIC / issue-topn}）：一次分析最多返回多少条建议。默认 50。 */
    public int issueTopN() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "issue-topn", 50);
        return v <= 0 ? 50 : v;
    }

    /**
     * 元数据巡检开关（T11-4，{@code METRIC / meta-patrol}）：开启后「待治理问题」tab 提供「元数据巡检」按钮，
     * 扫语义层与指标资产确定性识别血缘断裂/同名不同口径/注释缺失三类缺陷，命中行进同一台账。
     *
     * <p><b>纯确定性、零 LLM、不查业务表数据</b>（只读元数据+缓存 schema），默认<b>开</b>。
     * 关闭则端点拒用（40301）、按钮隐藏；已入台账的巡检行仍可处置。
     */
    public boolean metaPatrolEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_METRIC, "meta-patrol", true);
    }

    /**
     * 结构变更感知开关（T12-3，{@code METRIC / drift-watch}）：开启后结构对账页提供「感知变更」入口，
     * 重扫 schema 与上轮快照 diff → 删表/删列反查受影响查询模板/指标，入账台账并定向站内信创建人。
     *
     * <p><b>纯确定性、零 LLM</b>（全量直扫不落缓存，同对账 check 口径），默认<b>开</b>。
     * 关闭则端点拒用（40301）；已入台账的变更行仍可处置。
     */
    public boolean driftWatchEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_METRIC, "drift-watch", true);
    }

    /**
     * 成本巡检开关（T12-4，{@code METRIC / cost-patrol}）：开启后「待治理问题」tab 提供「成本巡检」按钮，
     * 对窗口内成功问数历史按归一问法聚簇，确定性识别物化候选/慢查询/重复问法三类成本建议，命中行进同一台账。
     *
     * <p><b>纯确定性、零 LLM、只读自身历史表</b>（不碰用户业务库），默认<b>开</b>。
     * 关闭则端点拒用（40301）、按钮隐藏；已入台账的成本行仍可处置。
     */
    public boolean costPatrolEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_METRIC, "cost-patrol", true);
    }

    /** 成本巡检时间窗（天，{@code METRIC / cost-patrol-window-days}）：只统计窗口内的问数历史。默认 7。 */
    public int costPatrolWindowDays() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "cost-patrol-window-days", 7);
        return v <= 0 ? 7 : v;
    }

    /** 慢查询阈值（毫秒，{@code METRIC / cost-patrol-slow-ms}）：簇均耗时达此值判为慢。默认 5000。 */
    public long costPatrolSlowMs() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "cost-patrol-slow-ms", 5000);
        return v <= 0 ? 5000L : v;
    }

    /** 重复问法门槛（{@code METRIC / cost-patrol-dup-min-occurrences}）：同归一键窗口内出现达此值判重复。默认 5。 */
    public int costPatrolDupMinOccurrences() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "cost-patrol-dup-min-occurrences", 5);
        return v <= 0 ? 5 : v;
    }

    /** 物化候选门槛（{@code METRIC / cost-patrol-materialize-min-occurrences}）：又慢又达此次数→升格物化建议。默认 3。 */
    public int costPatrolMaterializeMinOccurrences() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_METRIC, "cost-patrol-materialize-min-occurrences", 3);
        return v <= 0 ? 3 : v;
    }

    /**
     * 定时报表总开关（T12-1，{@code ASK / schedule}）：开启后提供「我的定时报表」管理页与模板页入口，
     * 已存查询模板可固化为日/周/小时定时任务，确定性重跑（零 LLM）并推送站内信/邮件。
     *
     * <p><b>纯确定性、不占问数配额</b>（执行走 runTemplate 既有内核），默认<b>开</b>（对齐 T11-4 先例）。
     * 关闭则端点拒用（40301）、入口隐藏，且调度扫描逐租户直接跳过（已有任务不删、不开跑）。
     */
    public boolean scheduleEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "schedule", true);
    }

    /**
     * 组织共享查询模板总开关（{@code ASK / shared-templates}）：开启后租户管理员可把自己的查询模板
     * 标记为共享，同租户成员在高级查询页可见可跑可设定时报表（执行仍走成员自身 ACL/脱敏上下文，不升权）。
     *
     * <p><b>纯确定性能力、默认开</b>。关闭只收入口面：列表不再合并他人共享、切换共享拒用；
     * <b>存量已共享模板对成员保持可跑可用、已建共享报表继续可执行</b>（fail-soft，关开关不破坏既有资产；
     * 数据权限本就按使用者自身 ACL/脱敏收敛，门控不是安全边界）。
     */
    public boolean sharedTemplatesEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "shared-templates", true);
    }

    /**
     * 驾驶舱 AI 自动化大屏 Skill 总开关（{@code COCKPIT / ai-dashboard}）：开则由 Skill 基于自动生成的指标
     * 自动编排图表与布局，生成可直接预览的 AI 大屏（先确定性基线再 AI 布局增强，失败回退算法）；关闭则入口隐藏。
     */
    public boolean cockpitAiDashboardEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_COCKPIT, "ai-dashboard", true);
    }

    // ==================== 问数技能（V4 Skill 模式） ====================

    /**
     * 单个问数技能的启停开关（{@code ASK / skill.<code>}）。
     *
     * <p>技能默认全部开启；租户可在配置页按需停用某条确定性能力（如样例库尚未治理干净时先关"样例复用"），
     * 停用后路由自动跳过该技能、回落到兜底的智能生成，问数链路不受影响。
     *
     * @param code 技能编码（如 {@code direct_sql} / {@code metric} / {@code fewshot}）
     * @param def  未配置时的默认值
     */
    /**
     * 多轮追问改写开关（{@code ASK / rewrite}）：开启后，判定为省略式追问（如"那上个月呢""只看华东"）
     * 时先把追问补全成独立完整的问句，再进入技能路由。
     *
     * <p>这个开关直接影响"免 LLM 占比"：追问若带着省略，确定性技能只能看到"只看华东"这种残缺文本，
     * 无法与指标名/样例问法匹配，必然落到大模型；补全后"华东地区月度 GMV 趋势"就能命中指标直达。
     */
    public boolean askRewriteEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "rewrite", true);
    }

    /**
     * 域外护栏开关（{@code ASK / guard}）：开启后对明显与数据无关的寒暄/闲聊快速友好拒绝，
     * 既不消耗问数配额，也避免大模型一本正经地对着"你好"生成一条无关 SQL。
     *
     * <p>关闭场景：把本系统当作开放式对话助手嵌入时（如 OA 侧边栏），可放行给大模型自由发挥。
     */
    public boolean askGuardEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "guard", true);
    }

    /**
     * 业务术语词典开关（{@code ASK / term}）：开启后，路由前把提问里的口语词（如"流水"）
     * 归一为规范名（如"营收"），提升指标直达 / 样例复用 / 数据字典问答等免 LLM 技能的命中率。
     *
     * <p>关闭场景：词典维护成本高于收益、或希望一切同义异词都交给大模型理解时。
     */
    public boolean askTermEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "term", true);
    }

    /**
     * 权限域护栏开关（{@code ASK / permission-guard}）：开启后，问数路由前检测提问是否点名了
     * 当前角色无权查看的敏感字段（依据 {@code semantic_meta.sensitive} + {@code visible_roles}），
     * 命中则前置友好拦截（免配额、免大模型）。与 {@code FieldPermissionService.mask} 的结果层脱敏
     * 形成"前置拦截 + 结果兜底"双保险——前者优化体验与成本，后者是安全硬保证（即便前置漏判也不泄密）。
     *
     * <p>关闭场景：敏感字段未治理（{@code visible_roles} 普遍为空且希望全员可见）、或希望一切问法都交给下游判定时。
     */
    public boolean askPermissionGuardEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "permission-guard", true);
    }

    public boolean askSkillEnabled(String code, boolean def) {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "skill." + code, def);
    }

    // ==================== 多意图拆分（T10-4：一个问题拆成 ≤N 个原子子问，串行作答并聚合） ====================

    /**
     * 多意图拆分总开关（{@code ASK / multi-intent-enabled}）：开启后，识别出一条提问里并列的多个原子取数意图
     * 时，拆成若干子问、在<b>同一段代码里顺序</b>复用单问管线各自作答，聚合成一份多段答案返回。
     *
     * <p><b>默认关闭</b>：本特性给核心问数链路新增编排层，收益（少打几次字）与成本/风险（N× 延迟、并发红线）
     * 最不划算，故 roadmap 排最后、默认关。关闭时 {@code ask} 走现有单意图路径，行为与未接入本特性逐字节等价。
     * 编排严守「单许可内顺序跑子问、批内绝不嵌套护栏」——见 {@code ConcurrencyGuard} 非可重入约束。
     */
    public boolean multiIntentEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "multi-intent-enabled", false);
    }

    /**
     * 是否允许拆分动用轻量大模型（{@code AI / multi-intent-split}）：仅当规则预筛判为「疑似多子句」且本开关开启时，
     * 才发一次小模型调用把复合问法拆成原子问句。
     *
     * <p><b>默认关闭</b>：纯规则能拆的场景有限，要拆分效果好就得花一次轻量调用。关闭时只做规则预筛、命中信号也
     * 原样单意图返回，零额外调用。无论开关与否，拆分调用失败/解析失败/数量越界一律回落单意图（fail-open，绝不让问数报错）。
     */
    public boolean multiIntentSplitLlmEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_AI, "multi-intent-split", false);
    }

    /** 一次最多拆出的子问数（{@code ASK / multi-intent-max-parts}）：串行执行 + 批量超时预算的硬边界。默认 3，越界回落单意图。 */
    public int multiIntentMaxParts() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_ASK, "multi-intent-max-parts", 3);
        return v <= 0 ? 3 : v;
    }

    /**
     * 整批总超时预算（毫秒，{@code ASK / multi-intent-batch-timeout-ms}）：多意图子问串行跑，累计耗时超过此预算时
     * 剩余子问标注「未作答，请单独追问」，绝不把用户挂死。默认 60000（约单问预算×3）。
     */
    public int multiIntentBatchTimeoutMs() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_ASK, "multi-intent-batch-timeout-ms", 60000);
        return v <= 0 ? 60000 : v;
    }

    // ==================== 对话内报告拆题（把"本月销售报告"展开成 ≤N 段原子子问串行作答并聚合） ====================

    /**
     * 报告拆题总开关（{@code ASK / report}）：开启后，识别出"本月销售报告"这类<b>报告型</b>提问时，
     * 由轻量大模型依标准报告角度（总体数值/环比同比/维度 TopN/时间趋势）展开成若干原子子问，
     * 复用 {@code ChatService.askMulti} 串行作答、聚合成一份多段报告。
     *
     * <p><b>默认关闭</b>：涉 LLM 拆题且给核心链路新增一段前置编排，遵 roadmap「涉 LLM 新能力默认 false」红线。
     * 关闭时 {@code ask} 走现有单意图/多意图路径，行为与未接入本特性逐字节等价。拆题器自带四道门与
     * fail-open——非报告/未命中/失败一律回落单元素。
     */
    public boolean reportEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "report", false);
    }

    /**
     * 报告拆题是否允许动用轻量大模型（{@code AI / report-split}）：仅当总开关开且关键词预筛命中「报告型」时，
     * 才发一次小模型调用把报告需求展开成原子子问。
     *
     * <p><b>默认关闭</b>：镜像 {@code AI/multi-intent-split}。关闭时仅预筛、不拆题，零额外调用。
     * 无论开关与否，拆题调用失败/解析失败/数量越界一律回落单元素（fail-open，绝不让问数报错）。
     */
    public boolean reportSplitLlmEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_AI, "report-split", false);
    }

    /**
     * 一次最多展开的报告段落数（{@code ASK / report-max-parts}）：串行执行 + 批量超时预算的硬边界。默认 5
     * （比多意图的 3 略高，报告段落天然更多）；越界回落单元素。
     */
    public int reportMaxParts() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_ASK, "report-max-parts", 5);
        return v <= 0 ? 5 : v;
    }

    // ==================== 口径歧义主动反问（T10-2：一词多口径/近似指标名时低成本反问一轮锁定口径） ====================

    /**
     * 口径歧义反问总开关（{@code ASK / clarify-enabled}）：开启后，单个意图的提问在命中"一词多口径"或
     * "近似指标名"等歧义信号、置信度落到中等区间时，返回一份澄清候选卡（不执行、不扣配额），
     * 用户点选后带 {@code clarified=true} 重发锁定口径。
     *
     * <p><b>默认关闭</b>：本特性在核心问数链路的单意图分支前置一个判定门，误反问会打断一键直达体验，
     * 且候选质量依赖语义层术语/指标的治理程度。关闭时 {@code ask} 行为与未接入本特性逐字节等价
     * （判定门短路，绝不出澄清卡）。与归因侧 {@code ATTRIBUTION / clarify-enabled} 是两回事：后者管"归因结论
     * 要不要多问一轮"，前者管"问数口径要不要反问"，阈值同源（见 {@code ConfidenceGate}）。
     */
    public boolean askClarifyEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "clarify-enabled", false);
    }

    /**
     * 口径反问直出阈值（百分比，{@code ASK / clarify-direct-threshold}）：置信度 ≥ 此值直接放行执行，
     * 不反问。默认 60，与归因 {@code AttributionGate.DIRECT_CONFIDENCE} 同源。作分母为 100 的整数百分比存储。
     */
    public int askClarifyDirectThreshold() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_ASK, "clarify-direct-threshold", 60);
        return (v <= 0 || v > 100) ? 60 : v;
    }

    /**
     * 口径反问下限阈值（百分比，{@code ASK / clarify-min-threshold}）：置信度 &lt; 此值时歧义过甚、
     * 连像样的候选都给不出，放弃反问直接答（问数侧不 BLOCK——"算错不比不算更糟"）。默认 30。
     */
    public int askClarifyMinThreshold() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_ASK, "clarify-min-threshold", 30);
        return (v < 0 || v > 100) ? 30 : v;
    }

    /** 一次澄清最多给出的候选数（{@code ASK / clarify-max-options}）：默认 3，超出截断，避免选择困难。 */
    public int askClarifyMaxOptions() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_ASK, "clarify-max-options", 3);
        return v <= 0 ? 3 : v;
    }

    /**
     * 溯源元数据回传结果体（{@code ASK / lineage-in-result}，T10-3）：开启后每次问数的 {@code ResultMeta}
     * 附带 {@code lineage}——实际执行 SQL 涉及的来源表（含语义层业务名/描述），供结果面板核对"数字从哪来"。
     *
     * <p><b>默认开</b>：纯确定性抽取、零 LLM、零新增 DB 查询（语义层走既有缓存视图）；装配失败或缺表时
     * 静默省略该字段（fail-soft，绝不拖垮问数返回）。与归因侧 {@code ATTRIBUTION / lineage-enabled}
     * （语义管理的指标血缘图）是两个东西：前者管"运行时结果出处"，后者管"管理时血缘展示"。
     */
    public boolean askLineageEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "lineage-in-result", true);
    }

    /**
     * 环比/异常自动标记（{@code ASK / anomaly-marks}，T10-4b）：开启后时间序列结果（恰 1 时间列、
     * 无类别列、行数≥ 3）的相邻周期波动超阈值时，{@code ResultMeta} 附带 {@code marks} 供表格格高亮。
     *
     * <p><b>默认开</b>：纯确定性规则、零 LLM、零新增 DB 查询（列角色走 {@code ColumnRoleClassifier}
     * 同一份语义缓存判定）；不满足形态/装配失败时静默省略（fail-soft，绝不拖垮问数返回）。
     * 与归因侧 {@code ATTRIBUTION / anomaly-*}（管理时维度波动归因阈值）是两个东西：前者管"运行时
     * 结果格标记"，后者管"归因分析基期/本期"。
     */
    public boolean askAnomalyMarksEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "anomaly-marks", true);
    }

    /**
     * 环比波动阈值百分比（{@code ASK / anomaly-threshold-pct}，T10-4b）：相邻周期变化幅度绝对值
     * 达到此百分比即标记。默认 30；超出安全区间 [5, 95] 回退默认值（防噪声/防全盲）。
     */
    public int askAnomalyThresholdPct() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_ASK, "anomaly-threshold-pct", 30);
        return (v < 5 || v > 95) ? 30 : v;
    }

    /**
     * 反馈修正采集（{@code ASK / feedback-correction}，T11-2）：开启后用户「点踩」某条问数结果时，
     * 前端弹出轻量可选表单，采集其"期望正确 SQL / 口径说明 / 错因"，落 {@code query_history} 附列，
     * 供治理侧 {@code THUMBS_DOWN} 行作为转写预填的高价值草稿。
     *
     * <p><b>默认开</b>：纯确定性采集、零 LLM、零新表零新权限（复用既有 feedback 端点）。此开关只门控
     * <b>前端是否弹采集层</b>——后端即使收到 {@code correction} 字段也照常落库（向后兼容、不因开关丢数据），
     * 采集到的建议<b>绝不</b>绕过人审自动进少样本/指标（延续"只建议不自动改"红线）。
     */
    public boolean askFeedbackCorrectionEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ASK, "feedback-correction", true);
    }

    // ==================== 全局操作日志审计（V4.4） ====================

    /**
     * 全局操作日志总开关（{@code AUDIT / operation-log}）：开启后，所有命中 {@code /api/**} 的已认证请求
     * 由 {@code OperationLogInterceptor} 在 {@code afterCompletion} 自动捕获（租户/用户/方法/URI/脱敏体/IP/
     * UA/状态/耗时）并异步落库，无需业务代码改动；关闭则完全不采集。
     *
     * <p>默认开。与 `{@code auth_audit_log}` 的事件审计互补，而非替代：本开关只管"请求级全量留痕"这一种采集。
     */
    public boolean operationLogEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_AUDIT, "operation-log", true);
    }

    /**
     * 审计自然语言查询总开关（{@code AUDIT / nl-query}，T11-3）：开启后组织/平台审计页的
     * 「AI 查」入口可用——一句话抽槽位（时间/用户/动作/关键词）后走既有参数化检索三源融合。
     *
     * <p><b>默认关</b>：解析环节涉 LLM（政企需求才开），遵循 roadmap T11-3「涉 LLM 的新能力默认 false」
     * 红线；关闭时双域 nl-query 端点直接拒用，前端隐藏入口。槽位之外的查询链路全部确定性：
     * LLM 只产 JSON 过滤条件，绝不生成 SQL，实际检索走既有参数化 Mapper。
     */
    public boolean auditNlQueryEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_AUDIT, "nl-query", false);
    }

    /** 审计 NL 槽位解析的指定模型行（{@code AUDIT / nl-query-model}）：空=按 LlmRouter 默认链。 */
    public String auditNlQueryModel() {
        return configService.getString(TenantContext.tenantId(), CAT_AUDIT, "nl-query-model", "");
    }

    // ==================== 归因分析 + 数据血缘（V5 地基：模块启停 + SQL 合规） ====================

    /** 归因分析模块总开关（{@code ATTRIBUTION / enabled}）：关闭后问数链路不触发维度归因技能。 */
    public boolean attributionEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ATTRIBUTION, "enabled", true);
    }

    /** 数据血缘模块开关（{@code ATTRIBUTION / lineage-enabled}）：关闭后语义管理不展示/不计算指标血缘。 */
    public boolean lineageEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ATTRIBUTION, "lineage-enabled", true);
    }

    /**
     * 置信度单轮澄清开关（{@code ATTRIBUTION / clarify-enabled}）：关闭后 30~60% 置信度区间不再弹澄清窗、
     * 直接执行归因（等价于全量一次性返回，保留原有一键体验）。
     *
     * <p><b>消费方</b>：{@code AttributionService.gateOf(...)}——开关关闭时中等区间返回 {@code DIRECT}
     * 而非 {@code CLARIFY}（前端仅在 {@code gate=CLARIFY} 时弹单轮澄清窗），并在解读文案里补上不确定性
     * 提示。★该开关<b>不影响</b>置信度 ＜30% 的 {@code BLOCK} 硬阻断（那与"是否多问一轮"无关）。
     */
    public boolean clarifyEnabled() {
        return configService.getBool(TenantContext.tenantId(), CAT_ATTRIBUTION, "clarify-enabled", true);
    }

    /**
     * 允许查看原始 SQL 的角色码白名单（{@code ATTRIBUTION / sql-viewable-roles}）。
     * 空列表=沿用现有语义层可见性（SQL 仅对有权角色展示）；非空则仅列出的角色可在归因/分享快照中查看 SQL。
     * 落地需求 §4「SQL 查看权限可配置，部分角色禁止查看原始 SQL」——匿名分享与未授权角色据此隐藏 SQL。
     */
    public List<String> sqlViewableRoles() {
        return configService.getStringList(TenantContext.tenantId(), CAT_ATTRIBUTION, "sql-viewable-roles", List.of());
    }

    /**
     * 问数结果内的归因面板展示开关（{@code ATTRIBUTION / result-panel}）。
     *
     * <p><b>与 {@link #attributionEnabled()} 的区别</b>：{@code enabled} 是模块总开关（关掉后归因能力整体
     * 不可用，包括调优页的「维度归因」）；本开关只管<b>展示位置</b>——关闭后归因不再出现在对话结果里，
     * 但问数调优中心的「维度归因」常驻 tab 照常可用。适合"不想让每次问数结果都挂一个归因入口，
     * 需要时去调优页手动分析"的租户。
     *
     * <p><b>消费方是前端</b>：本方法只作为门面的类型化读取点与文档锚点；前端 {@code configStore} 经
     * {@code GET /api/config/effective} 读同名键（category=ATTRIBUTION, key=result-panel）后决定是否渲染
     * {@code ResultPanel} 内的 {@code AttributionPanel}。默认开。
     */
    public boolean attributionResultPanel() {
        return configService.getBool(TenantContext.tenantId(), CAT_ATTRIBUTION, "result-panel", true);
    }

    // ==================== 数据新鲜度（P2：超时标黄） ====================

    /**
     * 数据新鲜度 SLA 阈值（小时，{@code FRESHNESS / sla-hours}）：指标数据最后成功刷新时间距今超过该值
     * 即判定为「过期」，前端在指标列表 / 驾驶舱卡片上标黄提示，避免用户拿陈旧数据做决策。
     *
     * <p>指标可用 {@code metric.freshness_hours} 单独覆盖（如 T+1 离线表设 36、实时表设 4）；
     * 该指标级值为空时回落到本全局阈值。默认 24 小时。
     */
    public int freshnessSlaHours() {
        int v = configService.getInt(TenantContext.tenantId(), CAT_FRESHNESS, "sla-hours", 24);
        return v <= 0 ? 24 : v;
    }
}
