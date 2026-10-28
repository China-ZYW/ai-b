package com.aibi.module.chat.nl2sql;

import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.dialect.SqlDialects;
import com.aibi.module.datasource.model.DataSourceConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Prompt 组装器（§3.6）：把 相关表结构 + 意图指引 + 方言提示 + 少样本 + 业务规则 拼成"只输出 SQL"的提示词。
 *
 * <p>从 {@code ChatService} 内联抽出为独立组件，便于按意图/方言/少样本分别演进与单测，
 * 并提供执行报错后的"修正 Prompt"给 {@link SqlRepairer} 复用。
 *
 * <p>配置化升级 V2："严格要求"中的业务规则条款改读 {@link AibiConfig#promptRules()}（sys_config
 * NL2SQL/prompt.rules，DB 可增删改）；语义层段落（别名/口径/枚举）由 {@code semanticSection} 注入。
 *
 * <p>跨库联合分析：数据源配了 {@code extraCatalogs} 时，表结构里会混有带「库名.」前缀的表，
 * 三个 Prompt（生成 / 报错修正 / 空结果修正）均追加 {@link #crossDbHint} 要求原样保留前缀。
 *
 * <p>多表问数：同样这三个 Prompt 都追加 {@link #joinHint} —— 把切片内推导出的连接键与
 * 一对多基数明写出来（人工登记 / 库中外键 / 命名推断三路，见 {@link JoinGraph}），
 * 避免 LLM 自造关联键或关联后直接求和造成静默错数。
 * 切片内一条关联也推导不出时不输出任何内容，Prompt 与升级前逐字一致。
 */
@Component
public class PromptBuilder {

    private final AibiConfig aibiConfig;

    public PromptBuilder(AibiConfig aibiConfig) {
        this.aibiConfig = aibiConfig;
    }

    /** 组装首次生成 SQL 的 Prompt（无语义段落的兼容入口）。 */
    public String build(DataSourceConfig config, SchemaSlice slice, Intent intent,
                        List<FewShot> shots, List<ConversationTurn> turns, String question) {
        return build(config, slice, intent, shots, turns, question, null);
    }

    /**
     * 组装首次生成 SQL 的 Prompt（含可选的多轮对话上下文与语义层段落）。
     *
     * @param semanticSection 业务语义段落（{@code SemanticService.buildPromptSection} 产物，可空）
     */
    public String build(DataSourceConfig config, SchemaSlice slice, Intent intent,
                        List<FewShot> shots, List<ConversationTurn> turns, String question,
                        String semanticSection) {
        return build(config, slice, intent, shots, turns, question, semanticSection, null);
    }

    /**
     * 组装首次生成 SQL 的 Prompt（含可选的多轮对话上下文、语义层段落与校验反馈）。
     *
     * @param semanticSection 业务语义段落（{@code SemanticService.buildPromptSection} 产物，可空）
     * @param retryHint       质量校验反馈（校验闭环重试时回喂的问题点/建议/上一轮 SQL，可空）
     */
    public String build(DataSourceConfig config, SchemaSlice slice, Intent intent,
                        List<FewShot> shots, List<ConversationTurn> turns, String question,
                        String semanticSection, String retryHint) {
        return build(config, slice, intent, shots, turns, question, semanticSection, retryHint, null);
    }

    /**
     * 组装首次生成 SQL 的 Prompt（含可选的多轮上下文、语义层段落、校验反馈与呈现要求）。
     *
     * @param semanticSection 业务语义段落（{@code SemanticService.buildPromptSection} 产物，可空）
     * @param retryHint       质量校验反馈（校验闭环重试时回喂的问题点/建议/上一轮 SQL，可空）
     * @param requestedChart  用户显式要求的图表展示名（如"热力图"，可空），约束 SELECT 列满足图型数据需要
     */
    public String build(DataSourceConfig config, SchemaSlice slice, Intent intent,
                        List<FewShot> shots, List<ConversationTurn> turns, String question,
                        String semanticSection, String retryHint, String requestedChart) {
        String dialectHint = SqlDialects.of(config.type()).llmHint();
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个资深数据分析师和 SQL 专家。请根据下面的表结构，把用户的自然语言问题翻译成一条只读查询 SQL。\n");
        sb.append("目标数据库类型：").append(config.type()).append("。").append(dialectHint).append("。\n");
        sb.append("意图类型：").append(intent.name()).append(" —— ").append(intent.hint()).append("。\n");
        sb.append("严格要求：\n");
        sb.append("1. 只输出一条 SELECT 语句，不要任何解释文字，不要 markdown 代码块。\n");
        sb.append("2. 只能使用下面列出的表和字段，不要臆造。\n");
        // 业务规则条款配置化（sys_config NL2SQL/prompt.rules），代码默认兜底
        int no = 3;
        for (String rule : aibiConfig.promptRules()) {
            if (rule != null && !rule.isBlank()) {
                sb.append(no++).append(". ").append(rule.trim()).append("\n");
            }
        }
        sb.append(no).append(". 无法回答时只输出 UNKNOWN。\n");
        if (turns != null && !turns.isEmpty()) {
            sb.append("\n【多轮对话】以下是同一会话此前的问答，用于理解当前追问中的指代与省略：\n");
            for (ConversationTurn t : turns) {
                sb.append("上一问：").append(t.question()).append("\n对应 SQL：").append(t.sql()).append("\n");
            }
            sb.append("若当前问题是对上述的追问/修改，请在其基础上生成一条完整、可独立执行的新 SQL。\n");
        }
        if (shots != null && !shots.isEmpty()) {
            sb.append("\n参考示例（问法 → SQL）：\n");
            for (FewShot fs : shots) {
                sb.append("问：").append(fs.question()).append("\nSQL：").append(fs.sql()).append("\n");
            }
        }
        sb.append("\n表结构：\n").append(slice.toPromptText()).append("\n");
        sb.append(crossDbHint(config));
        sb.append(joinHint(slice));
        if (semanticSection != null && !semanticSection.isBlank()) {
            sb.append("\n【业务语义】以下是表/列的业务含义与口径，请优先据此理解用户问法：\n")
                    .append(semanticSection).append("\n");
        }
        if (retryHint != null && !retryHint.isBlank()) {
            sb.append("\n【质量校验反馈】你上一轮生成的 SQL 经质量审核判定未能回答用户问题，审核意见：\n")
                    .append(retryHint)
                    .append("\n请严格依据审核意见重新选表选列：先确认用户问题真正要查的表与字段再输出 SQL，不得沿用上一轮口径。\n");
        }
        if (requestedChart != null && !requestedChart.isBlank()) {
            sb.append("\n【呈现要求】用户要求以「").append(requestedChart)
                    .append("」呈现结果，SELECT 的列必须满足该图型的数据需要（如：热力图需要经度与纬度坐标列；")
                    .append("饼图/柱状图需要分类列与数值列；趋势图需要时间列与数值列；明细表需要问题涉及的原始字段）。\n");
        }
        sb.append(timeAnchor());
        sb.append("\n用户问题：").append(question).append("\nSQL：\n");
        return sb.toString();
    }

    /**
     * 时间锚点行：{@code ASK / time-anchor} 开启时返回「【当前日期】YYYY-MM-DD（周X）…」引导段，关闭返回空串。
     *
     * <p>日期取服务器时区当日（{@link LocalDate#now()}），星期用中文；组装异常一律静默返回空串
     * （绝不因锚点问题拖垮 Prompt 组装）。用于纠正 LLM 不知道"今天几号"、把"本月/上周/最近N天"瞎生成。
     */
    private String timeAnchor() {
        if (!aibiConfig.timeAnchorEnabled()) {
            return "";
        }
        try {
            LocalDate today = LocalDate.now();
            String[] weeks = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            String week = weeks[today.getDayOfWeek().getValue() - 1];
            return "\n【当前日期】" + today.format(DateTimeFormatter.ISO_DATE) + "（" + week + "）。"
                    + "凡涉及\"今天/昨天/本周/上周/本月/上月/本季度/今年/最近N天\"等相对时间，"
                    + "请以此基准换算成绝对日期写入 WHERE 条件，不要臆测年份、不要依赖方言日期函数。\n";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 跨库联合分析提示；未配额外库时返回空串（Prompt 与升级前逐字一致）。
     *
     * <p>额外库的表名带「库名.」前缀（见 {@code JdbcDataSourceAdapter#display}），而 LLM 的惯例
     * 是只写表名，一旦丢掉前缀就直接报“表不存在”；修正 Prompt 里也得重申，
     * 否则“报表不存在 → 修一次 → 又把前缀丢了”会白耗一轮重试。
     */
    private static String crossDbHint(DataSourceConfig config) {
        if (!config.multiCatalog()) {
            return "";
        }
        return "【跨库联合分析】上面的表来自同一服务器上的多个库/模式：不带前缀的表在主库，"
                + "带「库名.」前缀的表在另一个库。写 SQL 时必须原样保留这些前缀（只写表名会报表不存在），"
                + "带前缀的表与主库的表可以直接 JOIN；若问题只涉及主库，则不必使用带前缀的表。\n";
    }

    /**
     * 关联关系提示（多表问数准确率）：把切片内推导出的连接键与基数明写在 Prompt 里。
     * 切片内一条关联也推导不出时返回空串（单表问数，Prompt 与升级前逐字一致）。
     *
     * <p>为什么必需：LLM 只知道“可以 JOIN”，并不知道“该用哪个键 JOIN”。缺了它就会拿同名列硬凑
     * （orders.id = order_item.id）、或直接逗号连接产生笛卡尔积；而一对多关联后直接 SUM 会造成
     * 金额翻倍这类“SQL 跑通、结果非空、但数值错”的静默错数，比报错更难发现。
     *
     * <p>关联有三个来源（人工登记 / 库中外键 / 命名推断），<b>措辞必须区分</b>：前两者是确定信息（必须用），
     * 推断只是参考——把猜测当硬约束会让 LLM 在推错时仍硬着错连，反而不如不给。
     * 逐行的来源标注由 {@link JoinGraph} 渲染。
     */
    private static String joinHint(SchemaSlice slice) {
        String relations = slice.toRelationsText();
        if (relations.isBlank()) {
            return "";
        }
        return "【关联关系】下面是这些表之间可用的连接键（来自人工登记、库中外键约束，或按命名约定推断；"
                + "行末标注为“按命名推断”的仅供参考，与业务不符时请忽略）。多表查询时优先使用其中的一对作为 JOIN 条件，"
                + "不得自造连接键、不得用逗号连接或产生笛卡尔积；若确实没有可用连接键，宁可不关联也不要硬凑：\n"
                + relations + "\n"
                + "注意粒度：一对多关联后明细行会翻倍，汇总金额/计数前请先在子查询里按主表粒度聚合，"
                + "或改用 COUNT(DISTINCT 主键)，避免统计值被放大。\n";
    }

    /** 组装执行报错后的修正 Prompt（供 {@link SqlRepairer} 使用）。 */
    public String buildRepair(DataSourceConfig config, SchemaSlice slice,
                              String question, String failedSql, String dbError) {
        String dialectHint = SqlDialects.of(config.type()).llmHint();
        return """
                你之前生成的 SQL 在 %s 上执行报错，请修正。%s。
                严格要求：只输出一条修正后的 SELECT，不要任何解释，不要 markdown 代码块；只能使用下面列出的表和字段。

                表结构：
                %s
                %s%s
                用户问题：%s
                上一条 SQL：
                %s

                数据库报错：%s
                修正后的 SQL：
                """.formatted(config.type(), dialectHint, slice.toPromptText(), crossDbHint(config), joinHint(slice),
                question, failedSql, dbError);
    }

    /**
     * 组装 JOIN 静态校验后的修正 Prompt（多表问数准确率，供 {@link SqlRepairer} 使用）。
     *
     * <p>与 {@link #buildRepair} 分开而不复用：那条的措辞是"在库上执行报错"，而本条的 SQL <b>并没有报错</b>
     * ——它跑通、结果非空、只有数值是错的（fan-out 造成的静默错数）。把静态分析结论塞进"数据库报错"槽位
     * 会让模型以为是语法问题而大改结构；这里明说是连接键问题，并要求只改 JOIN、其余保持不变。
     */
    public String buildJoinRepair(DataSourceConfig config, SchemaSlice slice,
                                  String question, String sql, String issue) {
        String dialectHint = SqlDialects.of(config.type()).llmHint();
        return """
                你之前生成的 SQL 能正常执行，但 JOIN 的连接方式有问题，会让统计值算错。%s。
                严格要求：只输出一条修正后的 SELECT，不要任何解释，不要 markdown 代码块；只能使用下面列出的表和字段；
                只修正下面指出的连接键问题，选表、过滤条件、聚合方式、字段与别名一律保持不变。

                表结构：
                %s
                %s%s
                用户问题：%s
                上一条 SQL：
                %s

                %s
                修正后的 SQL：
                """.formatted(dialectHint, slice.toPromptText(), crossDbHint(config), joinHint(slice),
                question, sql, issue);
    }

    /**
     * 组装"空结果自修复" Prompt（配置化升级 V2）：SQL 执行成功但 0 行时，
     * 引导 LLM 放宽过滤条件/核对枚举取值后重试，降低"无数据"类误判（如大小写/全半角/日期范围写错）。
     */
    public String buildEmptyResultRepair(DataSourceConfig config, SchemaSlice slice,
                                         String question, String emptySql, String semanticSection) {
        String dialectHint = SqlDialects.of(config.type()).llmHint();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你之前生成的 SQL 在 %s 上执行成功但返回 0 行数据，可能过滤条件与实际数据不匹配。%s。
                请检查并修正：
                1. 过滤值的大小写/全半角/前后空格是否与库内实际取值一致，必要时改用 LIKE 或 LOWER() 放宽匹配；
                2. 日期/数值范围是否过窄，可适当放宽边界；
                3. 多表 JOIN 条件是否过严导致无交集（先核对关联键是否用对，再考虑去掉确实不必要的关联；
                   回答问题必需的关联不得删除，否则统计值会因缺少过滤而错算）；
                4. 非必要的过滤条件可移除，优先保证能回答用户问题。
                严格要求：只输出一条修正后的 SELECT，不要任何解释，不要 markdown 代码块；只能使用下面列出的表和字段；若确认无法放宽则原样输出上一条 SQL。

                表结构：
                %s
                """.formatted(config.type(), dialectHint, slice.toPromptText()));
        sb.append(crossDbHint(config));
        sb.append(joinHint(slice));
        if (semanticSection != null && !semanticSection.isBlank()) {
            sb.append("\n【业务语义】\n").append(semanticSection).append("\n");
        }
        sb.append("\n用户问题：").append(question)
                .append("\n上一条 SQL（返回 0 行）：\n").append(emptySql)
                .append("\n修正后的 SQL：\n");
        return sb.toString();
    }
}
