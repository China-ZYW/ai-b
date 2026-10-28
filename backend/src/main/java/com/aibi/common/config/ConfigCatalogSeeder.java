package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.aibi.common.config.SeederSupport.jsonArray;

/**
 * 配置中心「配置目录」播种（T8.1 拆分自 {@link DataSeeder}）：
 * 全部 sys_config 键连同完整中文说明的<b>单一事实源</b>，由 {@link #seed()} 幂等落地。
 *
 * <p>必须在 {@link SysConfigSeeder} 之后执行（order 70 > 60）：既给全新库补上目录里尚未播种的键，
 * 也给老库把简短说明升级为完整说明。
 */
@Component
public class ConfigCatalogSeeder implements Seeder {

    private static final Logger log = LoggerFactory.getLogger(ConfigCatalogSeeder.class);

    private final JdbcTemplate metaJdbc;
    private final SysConfigSeeder sysConfigSeeder;

    public ConfigCatalogSeeder(JdbcTemplate metaJdbc, SysConfigSeeder sysConfigSeeder) {
        this.metaJdbc = metaJdbc;
        this.sysConfigSeeder = sysConfigSeeder;
    }

    @Override
    public String name() {
        return "config-catalog";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public void seed() {
        ensureConfigCatalog();
    }

    /**
     * 配置目录条目：一条配置项 = 分类 + 键 + 平台默认值 + 中文说明（+ 旧的简短说明，见 {@link #ensureConfigCatalog()}）。
     */
    private record CfgDoc(String category, String key, String valueJson, String desc, String legacyDesc) {}

    private static CfgDoc cfg(String category, String key, String valueJson, String desc, String legacyDesc) {
        return new CfgDoc(category, key, valueJson, desc, legacyDesc);
    }

    private static CfgDoc cfg(String category, String key, String valueJson, String desc) {
        return cfg(category, key, valueJson, desc, "");
    }

    /**
     * 配置中心「配置目录」的<b>单一事实源</b>：一条记录 = 分类 + 键 + 平台默认值 + 完整中文说明 + 旧说明。
     *
     * <p><b>为什么集中在这里</b>：早期各批配置分散在 {@code SysConfigSeeder} 的三个 seed 方法里分批播种，
     * 说明越写越短（"少样本注入条数"这种），而 {@code AibiConfig} 里的大段中文注释又永远传不到用户眼前。
     * 此处把全部键连同<b>完整中文说明</b>收成一张表，由 {@link #ensureConfigCatalog()} 幂等落地：
     * 缺失的补、说明是旧文案的升级、人工改过的不动。
     *
     * <p><b>默认值必须与 {@code AibiConfig} 的类型化默认值保持一致</b>——否则"补种"会悄悄改变线上行为。
     * <b>说明上限 255 字</b>（{@code sys_config.description VARCHAR(255)}），每条控制在一段话内。
     *
     * @param legacyDesc 旧的简短说明：用于识别"尚未被人工改写"的行并安全升级；新键填空串
     */
    private List<CfgDoc> buildConfigCatalog() {
        List<CfgDoc> c = new ArrayList<>();
        // ---------- NL2SQL 引擎策略 ----------
        c.add(cfg("NL2SQL", "prompt.rules",
                jsonArray("\"销售额/金额\"通常用金额字段或 单价*数量；涉及聚合必须写 GROUP BY。",
                        "涉及\"已支付/有效订单\"等语义时用对应状态字段过滤（如 status='paid'）。",
                        "多表查询必须用明确的关联键 JOIN，禁止逗号连接与笛卡尔积；"
                                + "一对多关联后要先去重或先按主表粒度在子查询里聚合，避免金额/计数被放大。"),
                "生成 SQL 时注入 Prompt 的业务规则清单（JSON 字符串数组）。影响：条目越多、约束越细，SQL 越贴合业务口径，"
                        + "但 Prompt 变长、生成变慢。建议保留 2~5 条高价值规则；多表问数多的库建议保留关联键与粒度条款，"
                        + "并把自己库里常用的表关联写成“A.x = B.y”形式追加进来。",
                "Prompt 严格要求业务规则清单"));
        c.add(cfg("NL2SQL", "few-shot-limit", "3",
                "注入 Prompt 的相似样例 SQL 条数。影响：条数多通常准确率更高，但 Prompt 更长、耗时与成本上升；设为 0 则不注入样例。建议 3~5。",
                "少样本注入条数"));
        c.add(cfg("NL2SQL", "max-repair", "2",
                "SQL 执行报错后交大模型自动修正的最大重试次数。影响：提高一次问数的成功率，但增加耗时；过多会拖长失败反馈。建议 2~3；设 0 则报错即返回。",
                "SQL 执行报错自动修正最大次数"));
        c.add(cfg("NL2SQL", "conversation-turns", "3",
                "多轮追问时注入 Prompt 的最近历史轮数。影响：轮数多才能理解\"那上个月呢\"这类省略式追问，但 Prompt 变长。建议 3~5；设 0 只看当前问句。",
                "多轮对话注入历史轮数"));
        c.add(cfg("NL2SQL", "empty-result-repair", "true",
                "查到 0 行时让大模型按\"放宽过滤条件 / 核对枚举值\"自动改写并重跑一次。影响：显著减少\"查出来是空的\"，代价是多一次生成；关闭则直接返回空结果。",
                "空结果自修复开关"));
        c.add(cfg("NL2SQL", "relation-infer", "true",
                "关联命名推断（多表问数准确率）：库中没有外键约束时，按 order_id → orders.id 这类命名与类型约定推断表间连接键，"
                        + "用于把必需的维度表补进上下文，并在 Prompt 里渲染【关联关系】段。影响：业务库普遍不建外键，关掉后多表问数几乎拿不到连接键；"
                        + "推断结果已标注为「未必准确」，仅库命名极不规范时建议关闭。",
                ""));
        c.add(cfg("NL2SQL", "join-guard", "true",
                "JOIN 静态校验（多表问数准确率）：执行前解析 SQL 的连接结构，发现笛卡尔积、连接键与关联图谱矛盾、"
                        + "两表用同名主键互连时，回喂大模型修正一次。影响：错连不会报错（SQL 跑通、结果非空、只有数值错），"
                        + "执行期报错修正与空结果自修复都抓不到；本校验不花模型调用、只在有确凿证据时介入，解析不了则直接放行。建议保持开启。",
                ""));
        c.add(cfg("NL2SQL", "self-consistency", "true",
                "多路生成候选 SQL 后按结构多数投票择优。影响：明显提升复杂问法的准确率，代价是 LLM 调用次数与耗时上升。重准确率建议开，重速度 / 降本可关。",
                "self-consistency 多路生成择优开关"));
        c.add(cfg("NL2SQL", "consistency-samples", "3",
                "self-consistency 的候选 SQL 路数（仅在该开关开启时生效）。影响：路数越多投票越稳，成本近似线性上升。建议 3；≤1 等价于不投票。",
                "self-consistency 候选 SQL 生成路数"));
        c.add(cfg("NL2SQL", "cross-check", "false",
                "辅助模型交叉校验 SQL（提升问数准确率）：开启后把已生成的候选 SQL 交另一个不同的模型复核，"
                        + "模型认为有误则采纳其修正版。影响：跨模型交叉可显著减少单模型盲区导致的答非所问，代价是多一次 LLM 调用；"
                        + "需配合 AI 分类下的 cross-check-model 指定具体辅助模型（留空则复用 analysis-model）。",
                "辅助模型交叉校验 SQL 开关（开启后交另一模型复核修正候选 SQL）"));
        c.add(cfg("NL2SQL", "schema-cross-check", "false",
                "辅助模型选表交叉复核（提升问数准确率）：开启后在生成 SQL 前用另一模型独立重挑相关表，"
                        + "与主链路选出的表取并集（只保留库中真实存在的表名），从源头减少「只看错表→生成错 SQL」。"
                        + "影响：大库/中文问法下选表更稳，代价是多一次轻量 LLM 调用；需配合 AI 分类下的 cross-check-model。",
                "辅助模型选表交叉复核开关（生成前用另一模型独立重挑相关表）"));
        c.add(cfg("NL2SQL", "groundedness-check", "false",
                "答非所问结果接地复核（提升问数准确率）：开启后在结果返回后用另一模型判定「结果是否真正回答了问题」，"
                        + "判否则把修正方向回喂、驱动一次重生成。影响：可兜住 SQL 跑通但语义答非所问的盲区（仅对可被 AI 重写的技能生效），"
                        + "代价是多一次 LLM 调用；需配合 AI 分类下的 cross-check-model。",
                "答非所问结果接地复核开关（结果返回后由另一模型判定是否真回答问题）"));
        c.add(cfg("NL2SQL", "cross-consistency", "false",
                "双生成共识（cross-consistency，提升问数准确率）：开启后主模型与辅助模型各自独立生成一条 SQL，"
                        + "归一化比对——一致则高置信采纳，不一致则由辅助模型当裁判选更合理者。影响：跨模型独立视角显著降低单模型盲区，"
                        + "代价是额外一次完整生成 + 可能一次裁决调用；与 cross-check 互斥（开启本项后优先走双生成，不再做单模型复核）。",
                "双生成共识开关（主/辅模型各生成一条 SQL 后比对择优）"));
        c.add(cfg("NL2SQL", "repair-cross-check", "false",
                "修复二次复核（提升问数准确率）：开启后每次 SQL 执行报错、自动修复后，由另一模型复核错误是否真正解决、"
                        + "是否仍回答问题，并把修正方向回喂下一次修复。影响：减少「修了但没修对」的无效重试，代价是每次修复多一次 LLM 调用；"
                        + "需配合 AI 分类下的 cross-check-model。",
                "修复二次复核开关（每次执行修复后由另一模型复核是否真正解决）"));
        c.add(cfg("NL2SQL", "fewshot-intent-boost", "true",
                "少样本意图对齐微调（提升问数准确率，Phase-1）：开启后运行时识别的意图与样例库标注的意图双向对齐——"
                        + "同意图样例提权、异意图样例降权（仅为微调，不替代词面重合度主信号）。影响：在不增加 LLM 调用的前提下，"
                        + "让意图相符的样例更易注入、把词面巧合但意图不符的样例压下去，提升首次命中率；旧样例（未标意图）不受影响。",
                ""));
        c.add(cfg("NL2SQL", "fewshot-intent-weight", "4",
                "少样本意图对齐强度（仅在该开关开启时生效）：同意图样例的加分值、异意图样例减分值（= 该值一半）。"
                        + "影响：值越大意图对齐对排序影响越强；建议 2~6，设 0 等同关闭对齐。",
                ""));

        // ---------- 执行前代价预估熔断（T10-1） ----------
        c.add(cfg("NL2SQL", "cost-guard", "true",
                "执行前代价预估总开关：开启后所有问数 SQL 在下发数据库之前先做一次静态代价预估，对「大概率扫全表 / 笛卡尔积 / "
                        + "预估扫描行超预算」的查询主动拒绝并给拆分建议。影响：与现有运行时兜底（限返回行数/限时）互补，"
                        + "本开关管「扫得起吗」；纯规则、零大模型，解析不了或行数取不到一律放行不误伤；关闭则完全恢复现状行为。"));
        c.add(cfg("NL2SQL", "cost-block", "true",
                "代价熔断阻断开关：开则命中高危直接拒绝；关闭则高危仅记告警、仍照常执行。影响：供灰度上线/排障用——"
                        + "先只告警观测误判率，确认不伤合法大查询后再开启真拦。"));
        c.add(cfg("NL2SQL", "cost-scan-rows-threshold", "50000000",
                "拒绝阈值（预估扫描行数上限）：单表全表扫描 / 多表最大表估算超过此行数即拒绝。影响：阈值越低拦截越严（防 OOM），"
                        + "越高越宽松。建议按实例内存与常见大表规模设置，默认 5000 万行。"));
        c.add(cfg("NL2SQL", "cost-warn-rows-threshold", "10000000",
                "告警阈值（预估扫描行数下限）：预估扫描量落在本值与拒绝阈值之间时仅告警不阻断。应低于拒绝阈值。默认 1000 万行。"));
        c.add(cfg("NL2SQL", "cost-big-table-rows", "5000000",
                "大表界定（行数）：单表无 WHERE 过滤且表行数超过此值，即判为全表扫描高危并拒绝。默认 500 万行。"));
        c.add(cfg("NL2SQL", "cost-selectivity", "10",
                "WHERE 选择性折扣（百分比）：单表有 WHERE 时按「表行数 × 本值/100」估算扫描量（10 即假定过滤后只剩 1/10）。"
                        + "影响：值越小假定过滤越强、越不易误拦；库过滤条件普遍选择性差时可调大。建议 5~20，默认 10。"));

        // ---------- 图表推荐与标注 ----------
        c.add(cfg("CHART", "max-pie-slices", "8",
                "允许画饼图的最大类别数，超出自动改用柱状图。影响：避免几十个切片挤成看不清的色块。建议 6~10。",
                "饼图最大切片数"));
        c.add(cfg("CHART", "max-measures", "5",
                "单张图表最多展示的度量（数值列）个数，超出截断。影响：防止多度量同轴导致量纲混乱、图不可读。建议 3~5。",
                "单图最多度量数"));
        c.add(cfg("CHART", "value-format", "\"COMPACT\"",
                "图表数值默认格式：COMPACT 万亿紧凑（1500000→150万，窄卡片可防爆版）、THOUSAND 千分位、PLAIN 原值。影响：仅改变展示，不影响数据。",
                "图表数值格式默认 COMPACT/THOUSAND/PLAIN"));
        c.add(cfg("CHART", "legend-position", "\"bottom\"",
                "图例默认位置：top / bottom / left / right / hidden。影响：hidden 能把空间让给图形（类别少时适用）。各指标可在图表标注里单独覆盖此默认值。",
                "图表图例默认位置 top/bottom/left/right/hidden"));
        c.add(cfg("CHART", "value-decimals", "-1",
                "图表数值默认小数位；-1 表示自动（整数原样、小数至多 2 位）。影响：设 0 会把 12.34 显示成 12，金额类指标慎用。",
                "图表数值默认小数位（-1=自动）"));
        c.add(cfg("CHART", "auto-unit", "true",
                "按语义层 unit → 列名关键词（如 amount / 金额）自动推断单位并追加在数值后。影响：关闭后只有用户手配的单位才显示；开启时请保证语义层 unit 准确。",
                "度量单位自动推断（语义层 unit → 列名关键词）"));

        // ---------- 问数结果展示 ----------
        c.add(cfg("RESULT", "default-view", "\"chart\"",
                "问数结果默认展示视图：chart 图 / table 明细表。影响：偏看明细的团队可设为 table；用户仍可在结果页手动切换。",
                "结果面板默认视图 chart/table"));
        c.add(cfg("RESULT", "max-display-rows", "500",
                "结果面板最多渲染的行数，超出部分前端截断（数据库实际查出的行数不变）。影响：值过大会拖慢页面渲染。建议 200~1000。",
                "结果面板最多展示行数"));
        c.add(cfg("RESULT", "number-format", "\"thousand\"",
                "度量列的数值格式化策略：raw 原值 / thousand 千分位。影响：仅影响展示；需要精确复制数值做二次计算时可选 raw。",
                "数值格式化策略 raw/thousand"));

        // ---------- AI 分析层 ----------
        c.add(cfg("AI", "chart-advisor", "true",
                "由大模型依据问题与结果结构选择图表类型，规则引擎兜底并做结构校验。影响：复杂问法选图更贴切；关闭则完全走确定性规则（零额外调用、结果更可预期）。",
                "AI 图表决策开关（LLM 选图，规则兜底与结构校验）"));
        c.add(cfg("AI", "insight", "true",
                "返回数据时附上大模型生成的自然语言解读（结论 / 要点 / 异常）。影响：显著提升可读性，代价是每次问数多一次 LLM 调用。",
                "AI 结果解读开关（自然语言洞察：结论/要点/异常）"));
        c.add(cfg("AI", "verify", "true",
                "由大模型复核结果是否真正回答了问题、数值有无异常，并给出置信度。影响：可在答非所问时提示用户；关闭则不再产生该次调用。",
                "AI 质量校验开关（是否回答问题 + 置信度）"));
        c.add(cfg("AI", "analysis-model", "\"\"",
                "AI 分析环节（选图 / 解读 / 校验）使用的模型标识，留空表示复用主问数模型。影响：可把轻量分析任务指向更便宜或更快的模型以降本。",
                "AI 分析环节模型覆盖（空=复用主问数模型）"));
        c.add(cfg("AI", "cross-check-model", "\"\"",
                "辅助交叉校验使用的模型标识（模型名，须已在 llm_config 中配置，大小写不敏感包含匹配）。留空 = 复用 analysis-model；"
                        + "二者皆空则不启用交叉校验。影响：指定一个与主问数模型不同的模型，才能产生真正的跨模型视角（如同主模型 DeepSeek + 辅助 Qwen）。",
                "辅助交叉校验模型覆盖（空=复用 analysis-model，再空=不交叉）"));
        c.add(cfg("AI", "layout-advisor", "true",
                "驾驶舱「AI 智能设计」：由大模型依据各组件的数据形态与语义给出布局与视觉样式建议；模型不可用或失败时自动回退确定性算法方案。"
                        + "影响：未配置大模型、或不希望产生额外调用时可关闭。"));
        c.add(cfg("AI", "multi-intent-split", "false",
                "多意图拆分是否允许动用轻量大模型（仅当「多意图总开关」开启且规则预筛命中「疑似多子句」时生效）："
                        + "开启后发一次小模型调用把复合问法拆成原子问句。影响：纯规则能拆的场景很有限，要拆分效果好才需开；"
                        + "关闭时只做规则预筛、命中信号也原样单意图返回（零额外调用）。无论开关，拆分失败/非法/越界一律回落单意图、绝不报错。"));

        // ---------- 问数技能（V4 Skill 模式） ----------
        c.add(cfg("ASK", "rewrite", "true",
                "多轮追问改写：判定为省略式追问（如\"那上个月呢\"\"只看华东\"）时先补全为独立完整问句，再进入技能路由。"
                        + "影响：关闭后省略式追问很难命中免 LLM 技能，会更多回落到大模型。"));
        c.add(cfg("ASK", "guard", "true",
                "域外护栏：对明显与数据无关的寒暄 / 闲聊快速友好拒绝，不消耗问数配额、不会对着\"你好\"生成一条无关 SQL。"
                        + "影响：把本系统当开放式对话助手嵌入时可关闭。"));
        c.add(cfg("ASK", "term", "true",
                "业务术语词典：路由前把口语词（如\"流水\"）归一为规范名（如\"营收\"），提升指标直达 / 样例复用等免 LLM 技能的命中率。"
                        + "影响：关闭后同义异词全交给大模型理解。"));
        c.add(cfg("ASK", "permission-guard", "true",
                "权限域护栏：问数前检测提问是否点名了当前角色无权查看的敏感字段，命中即前置友好拦截（免配额、免大模型）。"
                        + "★关闭不等于放行——结果层脱敏是安全硬保证，只是会浪费配额。"));
        c.add(cfg("ASK", "skill.direct_sql", "true",
                "「直连 SQL」技能：提问与已登记的直通 SQL 精确匹配时直接复用，完全不调用大模型。影响：关闭会增加调用与耗时；样例治理期可临时关闭。"));
        c.add(cfg("ASK", "skill.metric", "true",
                "「指标直达」技能：命中指标库里已定义口径的指标时直接按口径取数，免大模型且不依赖样例。影响：关闭会让这类问题回落通用生成，成本与耗时上升。"));
        c.add(cfg("ASK", "skill.fewshot", "true",
                "「样例复用」技能：命中人工沉淀的问法-SQL 样例时直接复用，零 LLM 调用。影响：样例库尚未治理干净时建议先关，避免复用到过时样例。"));
        c.add(cfg("ASK", "skill.schema_qa", "true",
                "「数据字典问答」技能：关于有哪些表 / 字段 / 口径的解释型提问直接查语义层回答，免大模型。影响：关闭后这类问题也会走通用生成。"));
        c.add(cfg("ASK", "route-cross-check", "false",
                "技能路由交叉复核（提升问数准确率）：开启后用另一模型独立判定「该走哪条技能」，若与路由分歧、"
                        + "且辅助模型选的是更保守的免 LLM 确定性技能（如指标直达 / 样例复用 / 直连 SQL），则回落该技能，"
                        + "避免本可免大模型的确定性问法被路由到大模型而答错或烧配额。影响：多一次轻量 LLM 调用；需配合 AI 分类下的 cross-check-model。",
                "技能路由交叉复核开关（用另一模型独立判定该走哪条技能）"));
        c.add(cfg("ASK", "rewrite-cross-check", "false",
                "追问改写交叉复核（提升问数准确率）：开启后校验追问改写是否正确消解指代、未臆造不存在的字段或条件，"
                        + "判否则回落原始提问（由通用生成结合多轮上下文处理）。影响：减少「改写跑偏导致整条链路答错」的风险，代价是多一次 LLM 调用；"
                        + "需配合 AI 分类下的 cross-check-model。",
                "追问改写交叉复核开关（校验改写是否真正消解指代）"));

        // ---------- 多意图拆分（T10-4：复合问法拆成 ≤N 个原子子问串行作答并聚合） ----------
        c.add(cfg("ASK", "multi-intent-enabled", "false",
                "多意图拆分总开关：开启后识别一条提问里并列的多个原子取数意图，拆成子问串行复用单问管线各自作答、"
                        + "聚合成一份多段答案。影响：本特性给核心链路新增编排层，收益与成本/风险最不划算，故 roadmap 排最后、"
                        + "默认关。关闭时问数走现有单意图路径、行为逐字节等价；编排严守单许可内串行跑子问、批内绝不嵌套护栏（避免并发死锁）。"));
        c.add(cfg("ASK", "multi-intent-max-parts", "3",
                "一次最多拆出的子问数：多意图串行执行，子问越多总耗时越长（≈ N×单问），故硬封顶。影响：超过此数的拆分结果丢弃、"
                        + "回落单意图。建议 2~4；配合批量超时预算一起控制最坏耗时。"));
        c.add(cfg("ASK", "multi-intent-batch-timeout-ms", "60000",
                "整批总超时预算（毫秒）：多意图子问串行跑，累计耗时超此预算时剩余子问标注「未作答、请单独追问」，"
                        + "绝不把用户挂死。默认 60000（约单问预算×3）。"));
        c.add(cfg("ASK", "time-anchor", "true",
                "NL2SQL 当前日期锚点：开启后在生成 SQL 的 Prompt 里注入「【当前日期】YYYY-MM-DD（周X）」一行，"
                        + "引导大模型把“今天/昨天/本周/本月/上季度/最近N天”等相对时间换算成绝对日期写入 WHERE，"
                        + "而非臆测年份或依赖方言日期函数。影响：纯文本几 token、不增任何模型调用，对全体带时间问数"
                        + "（也是“本月销售报告”可用的硬前提）都是通用正确性修复。仅库内以相对日期函数为准时关闭。默认开。"));

        // ---------- 对话内报告拆题（把"本月销售报告"展开成 ≤N 段原子子问串行作答并聚合） ----------
        c.add(cfg("ASK", "report", "false",
                "报告拆题总开关：开启后识别“本月销售报告”这类报告型提问，由轻量大模型依标准报告角度"
                        + "（总体数值/环比同比/维度 TopN/时间趋势）展开成若干原子子问，复用多段编排串行作答、"
                        + "聚合成一份对话内多段报告。影响：涉 LLM 拆题且给核心链路新增一段前置编排，默认关；"
                        + "关闭时问数走现有单意图/多意图路径、行为逐字节等价。拆题失败/未命中/越界一律回落原问。"));
        c.add(cfg("AI", "report-split", "false",
                "报告拆题是否允许动用轻量大模型（仅当「报告拆题总开关」开启且关键词预筛命中「报告型」时生效）："
                        + "开启后发一次小模型调用把报告需求展开成原子子问。影响：纯关键词无法展开出报告段落，要拆题效果好才需开；"
                        + "关闭时仅预筛、不拆题（零额外调用）。无论开关，拆题失败/非法/越界一律回落单元素、绝不报错。"));
        c.add(cfg("ASK", "report-max-parts", "5",
                "一次最多展开的报告段落数：报告串行执行，段落越多总耗时越长（≈ N×单问），故硬封顶。"
                        + "影响：超过此数的展开结果丢弃、回落单元素。默认 5（比多意图的 3 略高，报告段落天然更多）。"));
        c.add(cfg("ASK", "schedule", "true",
                "定时报表总开关（T12-1）：开启后提供「我的定时报表」管理页，已存查询模板可固化为日/周/小时定时任务，"
                        + "到期确定性重跑（零大模型、不占问配额）并把前 20 行结果推送站内信/邮件。"
                        + "关闭则入口隐藏、端点拒用、扫描跳过（已有任务不删、不开跑）。纯确定性能力，默认开。"));
        c.add(cfg("ASK", "shared-templates", "true",
                "组织共享查询模板：开启后租户管理员（OWNER/ADMIN）可把自己的查询模板标记为共享，"
                        + "同租户成员在高级查询页可见可跑可设定时报表；执行仍走成员自身的数据权限与脱敏上下文，共享 SQL 不升权。"
                        + "影响：关闭只收入口面（列表不合并他人共享、切换共享拒用），存量已共享模板仍可跑、已建共享报表继续可执行。纯确定性能力，默认开。"));

        // ---------- 口径歧义主动反问（T10-2：一词多口径/近似指标名时低成本反问一轮锁定口径） ----------
        c.add(cfg("ASK", "clarify-enabled", "false",
                "口径歧义反问总开关：开启后单个意图的提问命中「一词多口径」或「近似指标名」等歧义信号、"
                        + "置信度落到中等区间时，先返回一份澄清候选卡（不执行、不扣配额），用户点选后重发锁定口径。"
                        + "影响：默认关；开启后误反问会打断一键直达体验，且候选质量依赖语义层术语/指标治理程度。"
                        + "关闭时问数行为逐字节等价未接入本特性。★与归因侧 ATTRIBUTION/clarify-enabled 无关（后者管归因结论、前者管问数口径）。"));
        c.add(cfg("ASK", "clarify-direct-threshold", "60",
                "口径反问直出阈值（百分比）：置信度 ≥ 此值直接放行执行、不反问。默认 60，与归因直出阈值同源。"
                        + "影响：调高会更频繁触发反问（更多置信度区间落入澄清带），调低则更少打扰。"));
        c.add(cfg("ASK", "clarify-min-threshold", "30",
                "口径反问下限阈值（百分比）：置信度 &lt; 此值时歧义过甚、连像样候选都给不出，放弃反问直接答"
                        + "（问数侧不硬阻断——「算错不比不算更糟」）。默认 30。"));
        c.add(cfg("ASK", "clarify-max-options", "3",
                "一次澄清最多给出的候选数：超出截断，避免选择困难。默认 3，建议 2~4。"));
        c.add(cfg("ASK", "lineage-in-result", "true",
                "溯源回传结果体（T10-3）：开启后每次问数的结果元信息附带来源表清单（含语义层业务名与描述）与执行 SQL，"
                        + "供结果面板「数据来源」块核对数字出处。纯确定性抽取、零新增查询；关闭则返回体不含溯源信息。"));
        c.add(cfg("ASK", "anomaly-marks", "true",
                "环比/异常自动标记（T10-4b）：开启后时间序列结果（恰一个时间列、无其它维度列、行数≥ 3）的"
                        + "相邻周期波动达阈值时，结果元信息附带波动标记，明细表升红降绿高亮并给一句事实结论"
                        + "（只报数字与方向，不做业务归因）。纯确定性规则、零 LLM；关闭或结果非时间序列则不含标记。"));
        c.add(cfg("ASK", "anomaly-threshold-pct", "30",
                "环比波动阈值百分比（T10-4b）：相邻周期变化幅度绝对值达到此值即高亮标记。默认 30，"
                        + "安全区间 5~95（超出回退默认，防噪声/防全盲）。"));
        c.add(cfg("ASK", "feedback-correction", "true",
                "反馈修正采集（T11-2）：开启后用户「点踩」某条问数结果时，前端弹出轻量可选表单，"
                        + "采集其期望正确 SQL / 口径说明 / 错因，落问数历史附列，供治理「待处理点踩」行作为转写预填草稿。"
                        + "纯确定性采集、零 LLM、零新表零新权限；采集的建议绝不自动进少样本/指标，必经人工复核。"
                        + "关闭则点踩退回一键直踩不弹层（后端收到建议仍照常落库，向后兼容）。"));

        // ---------- 全局操作日志审计 ----------
        c.add(cfg("AUDIT", "operation-log", "true",
                "全局操作日志：开启后所有已认证 /api/** 请求由拦截器自动留痕（租户 / 用户 / URI / 脱敏请求体 / IP / 状态 / 耗时）并异步落库，业务代码零改动。"
                        + "影响：关闭则完全不采集；表增长快的库注意归档。"));

        // ---------- 审计自然语言查询（T11-3：涉 LLM，政企需求才开） ----------
        c.add(cfg("AUDIT", "nl-query", "false",
                "审计页「AI 查」：一句话（如“今天谁查了订单数据”）由 LLM 抽成结构化过滤条件后走既有参数化检索，"
                        + "绝不生成 SQL；解析失败静默降级回人工筛选。解析涉 LLM，默认关，政企需求手动开启。"));
        c.add(cfg("AUDIT", "nl-query-model", "\"\"",
                "审计 NL 槽位解析的指定模型行（空=按默认模型链），照 analysis-model 先例；仅 nl-query 开启时生效。"));

        // ---------- 归因分析 + 数据血缘 ----------
        c.add(cfg("ATTRIBUTION", "enabled", "true",
                "归因分析模块总开关。影响：关闭后归因能力整体不可用（含问数调优页的「维度归因」）；只想去掉结果里的入口请改 result-panel。",
                "归因分析模块总开关（关闭后归因能力整体不可用，含调优页「维度归因」）"));
        c.add(cfg("ATTRIBUTION", "result-panel", "true",
                "是否在问数结果里挂载「维度归因」面板。影响：关闭后不再每次问数都出现归因入口，但调优页「维度归因」常驻 tab 仍可手动分析。",
                "问数结果内展示「维度归因」面板（关闭后仅问数调优页「维度归因」可用）"));
        c.add(cfg("ATTRIBUTION", "lineage-enabled", "true",
                "数据血缘开关。影响：关闭后语义管理不展示也不计算指标血缘，上游溯源能力不可用。",
                "数据血缘模块开关（关闭后语义管理不展示/不计算指标血缘）"));
        c.add(cfg("ATTRIBUTION", "clarify-enabled", "true",
                "置信度 30~60% 且存在多种口径时先弹一轮澄清让用户确认。影响：关闭则直接选最可能口径执行（更快，但可能答非所想）。"
                        + "★不影响置信度 ＜30% 的硬阻断。",
                "归因置信度单轮澄清开关（关闭后 30~60% 区间不弹澄清窗，直接执行）"));
        c.add(cfg("ATTRIBUTION", "sql-viewable-roles", "[]",
                "允许查看归因原始 SQL 的角色码白名单（JSON 数组）。影响：留空 = 沿用语义层可见性；填入角色码后仅这些角色可见，用于给部分角色禁止查看原始 SQL。",
                "允许查看归因原始 SQL 的角色码白名单（空数组=沿用语义层可见性）"));

        // ---------- 数据新鲜度（P2） ----------
        c.add(cfg("FRESHNESS", "sla-hours", "24",
                "数据新鲜度 SLA（小时）：数据最后成功刷新距今超过该值即判定过期，在指标列表 / 驾驶舱卡片标黄，避免拿陈旧数据做决策。"
                        + "影响：单个指标可用 freshnessHours 覆盖（如 T+1 离线表设 36、实时表设 4）。",
                "数据新鲜度 SLA 阈值（小时）：指标数据超过该时长未刷新即标黄；指标可单独覆盖"));

        // ---------- 指标后台自动分析（Skill） ----------
        c.add(cfg("METRIC", "auto-analysis", "true",
                "指标后台自动分析 Skill 总开关：开启后系统扫描库表结构，自动识别指标 / 维度、生成多级目录并打上 AI 自动识别标识，"
                        + "同时自动建指标；可在语义管理页手动触发，或 init 时按开关自动跑。关闭则入口隐藏、init 不再自动分析。",
                "指标后台自动分析 Skill 总开关（扫描库表自动识别指标/维度并建指标）"));

        // ---------- 问题沉淀（T11-1：只读聚合 query_history 出「待治理清单」） ----------
        c.add(cfg("METRIC", "issue-distill", "true",
                "问题沉淀总开关：开启后「问数调优中心」展示「待治理问题」tab，按时间窗把历史问数聚合成「哪些问法反复出错/低分/被踩/被熔断」"
                        + "的治理建议清单，告诉数据管理员该补哪条指标口径、术语映射或少样本。纯只读、零大模型，不改库不碰问数主链路；"
                        + "关闭则返回空报告、tab 空态。建议 30 天窗口。"));
        c.add(cfg("METRIC", "issue-distill-window-days", "30",
                "问题沉淀聚合的时间窗（天）：只统计该窗口内的问数历史。窗口越大越能暴露长尾问题，但扫描行数与耗时上升。建议 30~90。"));
        c.add(cfg("METRIC", "issue-low-score-threshold", "60",
                "低质量分判定阈值（0~100）：问数执行成功但 AI 质量分校验分低于此值的问法，计入「低质量分」治理簇。"
                        + "与校验重试阈值保持一致（默认 60），调高会让更多问法被判为待改进。"));
        c.add(cfg("METRIC", "issue-min-occurrences", "3",
                "成簇最少出现次数：同一归一化问法至少出现这么多次才计为高频失败/低分簇（降噪）。"
                        + "点踩与代价熔断类不受此限，逐条即出。调高可降噪、调低可捕更多长尾。"));
        c.add(cfg("METRIC", "issue-topn", "50",
                "治理清单条数上限：一次分析最多返回多少条建议（按信号优先级 + 出现次数排序后截断）。建议 30~100。"));
        c.add(cfg("METRIC", "issue-distill-convert", "true",
                "待治理清单「转写」开关：开启后「待治理问题」tab 每行提供「转指标 / 转少样本」入口，"
                        + "人工校对 SQL 后走既有创建接口沉淀。仍守「只建议不自动改」红线：写入前强制人工确认，绝不自动存失败草稿。关闭则仅只读展示。"));
        c.add(cfg("METRIC", "issue-push", "false",
                "待治理问题推送开关：开启后定时扫描新增未推送的 OPEN 治理问题，"
                        + "按租户聚合为一条站内信送达管理角色（OWNER/ADMIN/DATA_STEWARD）。"
                        + "去重：同一未处置问题在一个周期内只推一次。默认关闭，升级不自动开启。"));
        c.add(cfg("METRIC", "meta-patrol", "true",
                "元数据巡检开关（T11-4）：开启后「待治理问题」tab 提供「元数据巡检」按钮，"
                        + "扫语义层与指标资产，确定性识别血缘断裂（幽灵语义行/指标引用已不存在的表）、"
                        + "同名不同口径（同一中文别名映射多个列、同名指标口径不一）、注释缺失（裸列名）三类缺陷，"
                        + "命中行进同一治理台账可一键跳转修复。纯规则零大模型，不查业务表数据；关闭则入口隐藏。默认开。"));
        c.add(cfg("METRIC", "drift-watch", "true",
                "结构变更感知开关（T12-3）：开启后结构对账页提供「感知变更」按钮，"
                        + "重扫 schema 与上轮快照 diff，仅删表/删列才反查受影响查询模板与指标（宁缺勿滥），"
                        + "命中入台账并定向站内信资产创建人；首轮只建基准不告警。纯确定性零大模型；关闭则端点拒用。默认开。"));
        c.add(cfg("METRIC", "cost-patrol", "true",
                "成本巡检开关（T12-4）：开启后「待治理问题」tab 提供「成本巡检」按钮，"
                        + "对窗口内成功问数历史按归一问法聚簇，确定性识别物化候选（又慢又高频）/慢查询/重复问法"
                        + "三类成本建议，命中行进同一治理台账，转指标即物化出口。纯规则零大模型，只读自身历史表；关闭则入口隐藏。默认开。"));
        c.add(cfg("METRIC", "cost-patrol-window-days", "7",
                "成本巡检时间窗（天）：只统计窗口内的问数耗时与频次。窗口越大越易聚出重复簇，也越难反映近期变化。默认 7。"));
        c.add(cfg("METRIC", "cost-patrol-slow-ms", "5000",
                "慢查询阈值（毫秒）：同一问法簇的平均执行耗时达到此值判为慢（均耗时达阈值且次数达物化门槛升格为物化候选）。默认 5000。"));
        c.add(cfg("METRIC", "cost-patrol-dup-min-occurrences", "5",
                "重复问法门槛（次）：同归一问法在窗口内出现达此次数且均耗时快→建议转指标/少样本/收藏固化。默认 5。"));
        c.add(cfg("METRIC", "cost-patrol-materialize-min-occurrences", "3",
                "物化候选门槛（次）：同归一问法既慢（均耗时达慢阈值）又出现达此次数→优先物化/预聚合建议。应≤重复门槛。默认 3。"));

        // ---------- 驾驶舱 AI 自动化大屏（Skill） ----------
        c.add(cfg("COCKPIT", "ai-dashboard", "true",
                "驾驶舱 AI 自动化大屏 Skill 总开关：开启后基于自动生成的指标，由 Skill 自动编排图表与布局，生成可直接预览的 AI 大屏"
                        + "（先确定性基线再 AI 布局增强，失败回退算法方案）；可在驾驶舱页手动触发。关闭则入口隐藏。",
                "驾驶舱 AI 自动化大屏 Skill 总开关（基于指标自动编排大屏）"));

        // ---------- 大屏/驾驶舱默认主题（系统主题切换·平台级） ----------
        c.add(cfg("THEME", "default-dashboard-theme", "\"dark\"",
                "新建驾驶舱 / 大屏时默认采用的主题 code（对应 theme_config.code，内置预设含 "
                        + "dark/light/tech/emerald/sunset/purple/cyber/nebula，租户自定义主题亦可填其 code）。"
                        + "影响：只作用于<b>新建</b>——已有大屏的 dashboard.theme 不会被改写，用户仍可在编辑器里逐个换。"
                        + "租户可在「配置中心 → THEME」覆盖本默认值（未被平台管控时）。填了库中不存在的 code 时前端回落到内置深色，不会白屏。",
                ""));
        return c;
    }

    /**
     * 按 {@link #buildConfigCatalog()} 幂等落地：缺的行补、说明是旧文案的升级、租户空说明补齐。
     *
     * <p><b>幂等契约</b>：①行不存在 → 按目录的默认值与说明插入；②说明为空、或等于本键的旧简短说明 →
     * 升级为完整说明；③说明已被人工改写（既非空、也不是旧文案）→ <b>原样保留</b>，绝不覆盖人的表达。
     *
     * <p><b>只改说明，不动 value_json 与 version</b>——文档微调不算配置变更，不该把乐观版本号顶上去；
     * 因此重复启动不会把 {@code sys_config.version} 刷高，也符合"无变化不写库"的既有约定。
     */
    private void ensureConfigCatalog() {
        int added = 0;
        int upgraded = 0;
        for (CfgDoc d : buildConfigCatalog()) {
            Integer n = metaJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_config WHERE scope_type = 'GLOBAL' AND category = ? AND config_key = ?",
                    Integer.class, d.category(), d.key());
            if (n == null || n == 0) {
                sysConfigSeeder.insertGlobal(d.category(), d.key(), d.valueJson(), d.desc());
                added++;
                continue;
            }
            String cur = metaJdbc.queryForObject(
                    "SELECT IFNULL(description, '') FROM sys_config WHERE scope_type = 'GLOBAL' "
                            + "AND category = ? AND config_key = ?",
                    String.class, d.category(), d.key());
            String curTrim = cur == null ? "" : cur.trim();
            boolean blank = curTrim.isEmpty();
            boolean isLegacy = !d.legacyDesc().isEmpty() && curTrim.equals(d.legacyDesc());
            if (blank || isLegacy) {
                metaJdbc.update(
                        "UPDATE sys_config SET description = ? WHERE scope_type = 'GLOBAL' "
                                + "AND category = ? AND config_key = ?",
                        d.desc(), d.category(), d.key());
                upgraded++;
            }
        }
        // 租户自建的覆盖行常常没有说明（UI 新建时可留空）：仅在其说明为空时补齐，人工写过的保留
        for (CfgDoc d : buildConfigCatalog()) {
            metaJdbc.update(
                    "UPDATE sys_config SET description = ? WHERE scope_type = 'TENANT' AND category = ? "
                            + "AND config_key = ? AND (description IS NULL OR TRIM(description) = '')",
                    d.desc(), d.category(), d.key());
        }
        if (added > 0 || upgraded > 0) {
            log.info("配置中心目录已对齐：补种 {} 项、升级说明 {} 项", added, upgraded);
        }
    }
}
