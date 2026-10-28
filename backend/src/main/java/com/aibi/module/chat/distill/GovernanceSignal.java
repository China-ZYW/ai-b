package com.aibi.module.chat.distill;

/**
 * 问题沉淀（T11-1）治理信号。
 *
 * <p><b>声明顺序即优先级</b>（{@link #priority()} 越小越该先看）：用户明确点踩 &gt; 代价熔断 &gt; 高频失败
 * &gt; 低质量分 &gt; 反复歧义。设计依据见 {@code docs/03-development/T11-1问题沉淀_设计稿.md} §3。
 *
 * <ul>
 *   <li>{@link #THUMBS_DOWN}（S3）：{@code feedback=-1}，最强、最直接的"答错了"信号；</li>
 *   <li>{@link #COST_BLOCKED}（S5）：被代价预估（T10-1）拒绝的问法——用户确有需求但代价过高，多半缺预聚合指标；</li>
 *   <li>{@link #FAILED_HIGH}（S1）：同一归一化问法反复执行失败；</li>
 *   <li>{@link #LOW_SCORE}（S2）：执行成功但 AI 质量分校验分数偏低；</li>
 *   <li>{@link #AMBIGUOUS}（S4）：同一会话内同一意图被反复澄清/改写。</li>
 * </ul>
 *
 * <p><b>T11-4 元数据巡检三信号</b>（{@code META_*}，纯确定性判定，由 {@code semantic.patrol.MetaPatrolScanner} 产出）：
 * 整体追加在问数信号之后——巡检行是资产缺陷而非「用户问题没答好」，紧急度一律让位于线上问数反馈；
 * 三者内部优先级按声明序 BROKEN &gt; DIFFDEF &gt; NO_COMMENT。设计依据见
 * {@code docs/03-development/设计文档/T11-4_元数据巡检_子设计稿_2026Q4.md} §4。
 *
 * <ul>
 *   <li>{@link #META_LINEAGE_BROKEN}（S-M1）：幽灵语义行 / 指标 SQL 引用表不在当前 schema；</li>
 *   <li>{@link #META_SAME_NAME_DIFF_DEF}（S-M2）：同一名叫映射两个不同物理列，或两条同名指标口径不同；</li>
 *   <li>{@link #META_NO_COMMENT}（S-M3）：列既无中文别名又无注释（裸列名），按表聚簇上报。</li>
 * </ul>
 *
 * <p><b>T12-3 结构变更感知</b>（{@link #META_SCHEMA_CHANGED}）：重扫 schema 与上轮快照 diff 出删表/删列
 * 且反查命中受影响资产时入账，归 META_* 系读侧自动透出。
 *
 * <p><b>T12-4 成本巡检三信号</b>（{@code COST_PATROL_*}，由 {@code CostPatrolAnalyzer} 对窗口内成功
 * 问数历史按归一问法聚簇后三分类产出）：整体追加在全部信号之后——成本建议是「值得优化」
 * 而非「出了故障」，紧急度一律让位于所有问题信号；三者内部优先级按声明序 MATERIALIZE &gt; SLOW &gt; DUP
 * （一簇只出一行，又慢又高频升格为物化候选）。
 */
public enum GovernanceSignal {

    THUMBS_DOWN("用户点踩"),
    COST_BLOCKED("代价熔断"),
    FAILED_HIGH("高频失败"),
    LOW_SCORE("低质量分"),
    AMBIGUOUS("反复歧义澄清"),

    // T11-4 元数据巡检（声明序=内部优先级：血缘断裂 > 同名不同口径 > 注释缺失；整体排在问数信号之后）
    META_LINEAGE_BROKEN("血缘断裂"),
    META_SAME_NAME_DIFF_DEF("同名不同口径"),
    META_NO_COMMENT("注释缺失"),

    // T12-3 结构变更感知：重扫 schema 与上轮快照 diff 出删表/删列且反查命中受影响资产时入账
    META_SCHEMA_CHANGED("结构变更"),

    // T12-4 成本巡检（声明序=内部优先级：物化候选 > 慢查询 > 重复问法；整体排最后，建议不及故障紧急）
    COST_PATROL_MATERIALIZE("物化候选"),
    COST_PATROL_SLOW_SQL("慢查询"),
    COST_PATROL_DUP_QUERY("重复问法");

    private final String label;

    GovernanceSignal(String label) {
        this.label = label;
    }

    /** 中文标签（前端展示"这是什么问题"）。 */
    public String label() {
        return label;
    }

    /** 优先级：枚举声明序，越小越紧急。 */
    public int priority() {
        return ordinal();
    }
}
