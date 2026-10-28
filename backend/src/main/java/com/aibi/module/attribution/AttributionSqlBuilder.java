package com.aibi.module.attribution;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

import com.aibi.module.datasource.DataSourceType;
import com.aibi.module.datasource.dialect.SqlDialects;

/**
 * 归因 SQL 构建器：把"指标 canonical 口径 + 维度 + 基准期"翻译成三套**独立可审计**的只读 SQL。
 *
 * <p><b>为什么三套 SQL 必须分开生成</b>（需求 §3.1 审计要求）：趋势回答"变成什么样"、
 * 量价回答"是量还是价驱动"、维度归因回答"是哪个业务板块导致的"。三者问题不同、结果结构不同，
 * 若合成一句 SQL 或一张结果表，审计人员将无法单独复算任何一步。分开生成后每一份都可独立查看、复制、重跑。
 *
 * <p><b>口径强制继承</b>：三套 SQL 的度量部分一律取自同一个 {@code measureExpr}（由指标 canonical
 * 表达式 {@code metric.definition} 归一化而来），不存在"趋势按净额、归因按总额"的漂移可能。
 *
 * <p><b>注入防护</b>：表名/列名来自语义层元数据（管理员维护），理论可信但仍做白名单校验——
 * 标识符只认 {@code [A-Za-z0-9_]}，表达式禁止分号、注释与引号。元数据被污染时宁可拒绝生成，
 * 也不能让一条归因请求变成拖库语句。所有 SQL 最终还会经 {@code SqlValidator} 复校（服务层负责）。
 */
@Component
public class AttributionSqlBuilder {

    /** 合法标识符（表名/列名）：字母、数字、下划线。 */
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_]+");

    /** 表达式里出现的危险片段：语句分隔、注释、引号（阻断注入与意外截断）。 */
    private static final Pattern DANGEROUS = Pattern.compile("(?s).*(;|--|/\\*|\\*/|'|\"|`).*");

    /** 仍留在原子表达式里的聚合函数（AVG/COUNT(DISTINCT) 等）：无法再被外层 SUM 包裹，判为不可加。 */
    private static final Pattern AGGREGATE = Pattern.compile("(?i)\\b(sum|avg|min|max|count)\\s*\\(");

    /** 日期格式（期间边界按日比较，统一用 yyyy-MM-dd 字符串，跨库字符串比较均成立）。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 月份格式（时间列为月粒度字符串如 {@code 2026-09} 时的边界写法）。 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 时间列的存储精度，决定期间边界渲染成哪种字符串。
     *
     * <p><b>为什么必须区分</b>：时间列常见两种形态——① 月粒度字符串 {@code '2026-09'}；
     * ② 日粒度 {@code '2026-09-19'}（varchar/DATE/DATETIME 皆可）。若一律按 {@code yyyy-MM-01}
     * 渲染边界，在形态①下字符串比较会把当月整段排除（{@code '2026-09' < '2026-09-01'}），
     * 并把上一个月误判为基期——表现为"本期恒为 0、下降 100%"这类**错位一期**的荒谬结论。
     * 形态②下用 {@code yyyy-MM} 作边界同样成立（{@code '2026-09-19' >= '2026-09' 且 < '2026-10'}），
     * 故判定的唯一依据是列的真实精度，而非聚合粒度。
     */
    public enum TimePrecision {
        /** 月粒度：边界渲染为 {@code yyyy-MM}。 */
        MONTH,
        /** 日粒度：边界渲染为 {@code yyyy-MM-dd}。 */
        DAY
    }

    /** 趋势 SQL 默认回看的期数。 */
    private static final int DEFAULT_TREND_POINTS = 12;

    /**
     * 归一化 canonical 度量表达式，得到**可加的原子表达式**。
     *
     * <p>管理员可能把口径写成 {@code amount}（推荐），也可能写成 {@code SUM(amount)}（习惯）。
     * 聚合方式由本类统一决定（恒为 SUM），故此处需剥离外层聚合，避免生成 {@code SUM(SUM(amount))}。
     * 只剥离最外层常见聚合，嵌套聚合（{@code SUM(a)/SUM(b)}）**原样保留**并由 {@link #isAdditive} 判定为不可加。
     *
     * @param definition 指标 canonical 口径表达式（如 {@code amount} / {@code SUM(amount)} / {@code qty*price}）
     * @return 剥离外层聚合后的原子表达式；入参为空返回 null
     */
    public String atomicExpr(String definition) {
        if (definition == null || definition.isBlank()) {
            return null;
        }
        String expr = definition.trim();
        // 反复剥离外层"数值格式化"外壳（ROUND/TRUNCATE/FORMAT）与最外层 SUM(...)：
        // 口径可能是 ROUND(SUM(amount),2)（历史 SQL 原样沉淀），须一路剥到原子列 amount，
        // 否则本类外包的 SUM() 会变成 SUM(ROUND(SUM(amount),2)) —— 嵌套聚合直接报错。
        boolean changed = true;
        while (changed) {
            changed = false;
            String unwrapped = stripScalarWrapper(expr);
            if (unwrapped != null) {
                expr = unwrapped;
                changed = true;
                continue;
            }
            if (expr.length() > 5 && expr.regionMatches(true, 0, "SUM(", 0, 4) && expr.endsWith(")")) {
                String inner = expr.substring(4, expr.length() - 1).trim();
                if (isBalanced(inner)) {
                    expr = inner;
                    changed = true;
                }
            }
        }
        // COUNT(*) → 1：行计数本身可加（SUM(1) 沿维度求和即总行数）
        if (expr.matches("(?is)^COUNT\\(\\s*\\*\\s*\\)$")) {
            return "1";
        }
        return expr;
    }

    /** 剥离 {@code ROUND(x, n)} / {@code TRUNCATE(x, n)} / {@code FORMAT(x, n)} 外壳，返回首参数；不匹配返回 null。 */
    private String stripScalarWrapper(String expr) {
        for (String fn : new String[]{"ROUND", "TRUNCATE", "FORMAT"}) {
            int head = fn.length() + 1;
            if (expr.length() <= head || !expr.regionMatches(true, 0, fn + "(", 0, head) || !expr.endsWith(")")) {
                continue;
            }
            String inner = expr.substring(head, expr.length() - 1);
            if (!isBalanced(inner)) {
                continue;
            }
            int cut = topLevelComma(inner);
            return (cut < 0 ? inner : inner.substring(0, cut)).trim();
        }
        return null;
    }

    /** 返回字符串内首个处于括号深度 0 的逗号下标；无则 -1。 */
    private int topLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 可加性判定：度量是否可沿维度无损拆分。
     *
     * <p><b>为什么必须判</b>：对可加度量（金额、订单数），Σ各成员 Δ 恒等于总 Δ，单维归因成立；
     * 对比率/复合度量（毛利率、客单价 = 金额/量），朴素求和完全不成立——各成员"毛利率 Δ"相加
     * 没有任何业务含义。此类指标必须先经量价拆解为可加的量与额，或明确拒绝归因。
     *
     * <p>判定依据（任一命中即判不可加）：① 含除法 {@code /}；② 仍含聚合函数
     * （{@code AVG}/{@code COUNT(DISTINCT)} 等——它们无法再被外层 {@code SUM} 包裹，且语义上不可加）；
     * ③ 含 {@code DISTINCT}。这是保守但安全的启发式：宁可拒绝一个可加的边缘写法，
     * 也不能放行一个会产出错误结论、或直接生成非法嵌套聚合 SQL 的指标。
     */
    public boolean isAdditive(String atomicExpression) {
        if (atomicExpression == null || atomicExpression.isBlank()) {
            return false;
        }
        if (atomicExpression.contains("/")) {
            return false;
        }
        if (AGGREGATE.matcher(atomicExpression).find()) {
            return false;
        }
        return !atomicExpression.toLowerCase(Locale.ROOT).contains("distinct");
    }

    /**
     * 期间范围（左闭右开，避免 BETWEEN 在 DATETIME 上漏掉当日 23:59:59 之后的记录）。
     *
     * @param start 起始日（含）
     * @param end   结束日（不含）
     */
    public record PeriodRange(LocalDate start, LocalDate end, TimePrecision precision) {

        /** 兼容构造：默认按日粒度渲染边界。 */
        public PeriodRange(LocalDate start, LocalDate end) {
            this(start, end, TimePrecision.DAY);
        }

        public String startText() {
            return render(start);
        }

        public String endText() {
            return render(end);
        }

        /** 按时间列精度渲染边界文本：月粒度取 {@code yyyy-MM}，日粒度取 {@code yyyy-MM-dd}。 */
        private String render(LocalDate day) {
            if (day == null) {
                return null;
            }
            return precision == TimePrecision.MONTH ? MONTH.format(day) : DATE.format(day);
        }
    }

    /**
     * 由"本期 + 基准类型 + 粒度"推算本期与基期范围。
     *
     * @param period      本期（month 粒度 {@code 2026-08}；day 粒度 {@code 2026-08-15}）
     * @param granularity {@code month} / {@code day}
     * @return 长度为 2 的数组：[本期范围, 基期范围]；PEAK 的基期需先查出峰值期后另算，见 {@link #rangeOf}
     */
    public PeriodRange[] ranges(String period, String granularity, String baseline) {
        PeriodRange current = currentRange(period, granularity);
        if (current == null) {
            return null;
        }
        PeriodRange base = baseRange(current, granularity, baseline);
        return new PeriodRange[]{current, base};
    }

    /** 本期范围（日粒度边界）；period 为空或不可解析时返回 null（由调用方决定回落策略）。 */
    public PeriodRange currentRange(String period, String granularity) {
        return currentRange(period, granularity, TimePrecision.DAY);
    }

    /**
     * 本期范围，按时间列精度渲染边界。
     *
     * @param precision 时间列精度（{@link TimePrecision}），由服务层探测真实数据得出
     */
    public PeriodRange currentRange(String period, String granularity, TimePrecision precision) {
        try {
            TimePrecision p = precision == null ? TimePrecision.DAY : precision;
            if ("day".equalsIgnoreCase(granularity)) {
                LocalDate d = LocalDate.parse(period.trim(), DATE);
                return new PeriodRange(d, d.plusDays(1), p);
            }
            YearMonth ym = YearMonth.parse(period.trim());
            return new PeriodRange(ym.atDay(1), ym.plusMonths(1).atDay(1), p);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    /** 基期范围（MOM=上期、YOY=去年同期）；PEAK 返回 null，需先查峰值期。 */
    public PeriodRange baseRange(PeriodRange current, String granularity, String baseline) {
        if (current == null) {
            return null;
        }
        if ("PEAK".equalsIgnoreCase(baseline)) {
            return null;
        }
        // 基期沿用本期的边界精度，否则两条区间一个按日一个按月，比较结果仍然错位
        TimePrecision p = current.precision() == null ? TimePrecision.DAY : current.precision();
        if ("day".equalsIgnoreCase(granularity)) {
            LocalDate base = "YOY".equalsIgnoreCase(baseline)
                    ? current.start().minusYears(1)
                    : current.start().minusDays(1);
            return new PeriodRange(base, base.plusDays(1), p);
        }
        YearMonth ym = YearMonth.from(current.start());
        YearMonth baseYm = "YOY".equalsIgnoreCase(baseline) ? ym.minusYears(1) : ym.minusMonths(1);
        return new PeriodRange(baseYm.atDay(1), baseYm.plusMonths(1).atDay(1), p);
    }

    /** 按给定期间构造范围（PEAK 场景：用查出的峰值期构造基期）；精度沿用日粒度默认值。 */
    public PeriodRange rangeOf(String period, String granularity) {
        return currentRange(period, granularity);
    }

    /** 按给定期间与精度构造范围（PEAK 场景）。 */
    public PeriodRange rangeOf(String period, String granularity, TimePrecision precision) {
        return currentRange(period, granularity, precision);
    }

    /**
     * 维度归因 SQL：一次查出每个维度成员的本期值与基期值，贡献在 Java 侧相减得出。
     *
     * <p>用条件聚合（CASE WHEN + SUM）而非两条 SQL 再 join：① 只扫一遍表，大维度下省一半 IO；
     * ② 天然对齐成员——新出现/消失的成员不会因 join 丢失而被算成"贡献 0"之外的错值。
     */
    public String contributionSql(String table, String dimCol, String timeCol,
                                  String measureExpr, PeriodRange current, PeriodRange base,
                                  DataSourceType type) {
        String t = ident(table, type);
        String d = ident(dimCol, type);
        String tc = ident(timeCol, type);
        String m = expr(measureExpr);
        // WHERE 覆盖本期与基期的并集：两段分别 OR，兼容 MOM（相邻）与 YOY/PEAK（不相邻）
        return "SELECT " + d + " AS dim_member, "
                + "SUM(CASE WHEN " + tc + " >= '" + current.startText() + "' AND " + tc + " < '" + current.endText()
                + "' THEN " + m + " ELSE 0 END) AS cur_val, "
                + "SUM(CASE WHEN " + tc + " >= '" + base.startText() + "' AND " + tc + " < '" + base.endText()
                + "' THEN " + m + " ELSE 0 END) AS base_val "
                + "FROM " + t + " "
                + "WHERE (" + tc + " >= '" + base.startText() + "' AND " + tc + " < '" + base.endText() + "') "
                + "OR (" + tc + " >= '" + current.startText() + "' AND " + tc + " < '" + current.endText() + "') "
                + "GROUP BY " + d;
    }

    /**
     * 量价因子 SQL：一条语句同时取本期与基期的"总额"与"总量"，供两因素分解。
     *
     * <p>单价不落 SQL——由 {@code 总额/总量} 在 Java 侧推导，保证"价"与"额"同源，
     * 也免去语义层必须维护单价列的成本（业务库往往只有金额与数量）。
     */
    public String priceVolumeSql(String table, String timeCol, String measureExpr, String volumeExpr,
                                 PeriodRange current, PeriodRange base, DataSourceType type) {
        String t = ident(table, type);
        String tc = ident(timeCol, type);
        String m = expr(measureExpr);
        String v = expr(volumeExpr);
        return "SELECT "
                + "SUM(CASE WHEN " + tc + " >= '" + current.startText() + "' AND " + tc + " < '" + current.endText()
                + "' THEN " + m + " ELSE 0 END) AS cur_metric, "
                + "SUM(CASE WHEN " + tc + " >= '" + current.startText() + "' AND " + tc + " < '" + current.endText()
                + "' THEN " + v + " ELSE 0 END) AS cur_volume, "
                + "SUM(CASE WHEN " + tc + " >= '" + base.startText() + "' AND " + tc + " < '" + base.endText()
                + "' THEN " + m + " ELSE 0 END) AS base_metric, "
                + "SUM(CASE WHEN " + tc + " >= '" + base.startText() + "' AND " + tc + " < '" + base.endText()
                + "' THEN " + v + " ELSE 0 END) AS base_volume "
                + "FROM " + t + " "
                + "WHERE (" + tc + " >= '" + base.startText() + "' AND " + tc + " < '" + base.endText() + "') "
                + "OR (" + tc + " >= '" + current.startText() + "' AND " + tc + " < '" + current.endText() + "')";
    }

    /**
     * 趋势 SQL：按期聚合度量，回看最近 {@code points} 期（内层倒序取最近、外层正序便于画图）。
     */
    public String trendSql(String table, String timeCol, String measureExpr, int points, DataSourceType type) {
        String t = ident(table, type);
        String tc = ident(timeCol, type);
        String m = expr(measureExpr);
        int n = points <= 0 ? DEFAULT_TREND_POINTS : Math.min(points, 60);
        return "SELECT period, metric_value FROM ("
                + "SELECT " + tc + " AS period, SUM(" + m + ") AS metric_value "
                + "FROM " + t + " GROUP BY " + tc + " ORDER BY period DESC LIMIT " + n
                + ") latest ORDER BY period ASC";
    }

    /**
     * 峰值期查询 SQL（PEAK 基准用）：找出历史总度量最高的那一期。
     *
     * <p>PEAK 必须两步：先定位"哪一期的总量最高"，再取该期各维度分布。
     * 不能简单地在维度分组里取每个成员的历史最大——那样各成员峰值期不同，加总后不构成任何真实期间，
     * 归因结论会指向一个历史上从未发生过的组合。
     */
    public String peakPeriodSql(String table, String timeCol, String measureExpr, DataSourceType type) {
        String t = ident(table, type);
        String tc = ident(timeCol, type);
        String m = expr(measureExpr);
        return "SELECT " + tc + " AS period, SUM(" + m + ") AS metric_value "
                + "FROM " + t + " GROUP BY " + tc + " ORDER BY metric_value DESC LIMIT 1";
    }

    /** 本期总量 SQL（period 为空、需推断"最新一期"时使用）。 */
    public String latestPeriodSql(String table, String timeCol, DataSourceType type) {
        String t = ident(table, type);
        String tc = ident(timeCol, type);
        return "SELECT " + tc + " AS period FROM " + t + " ORDER BY " + tc + " DESC LIMIT 1";
    }

    /** 标识符白名单校验 + 按数据源方言包裹引用符（防元数据被污染后的注入）。 */
    private String ident(String name, DataSourceType type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("归因 SQL 标识符不能为空");
        }
        String trimmed = name.trim().replace("`", "");
        if (!IDENT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("非法的标识符（只允许字母/数字/下划线）：" + trimmed);
        }
        // 引用符按数据源方言取（MySQL/ClickHouse 用 `、PostgreSQL/SQLite 用 "、SQL Server 用 []），
        // 不再硬编码反引号，避免非 MySQL 业务库报"syntax error near '`'"。标识符已通过白名单校验，
        // 方言 quote 只是安全地包裹一个可信标识符，不会引入注入面。
        return SqlDialects.of(type).quote(trimmed);
    }

    /** 表达式危险字符校验。 */
    private String expr(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("归因度量表达式不能为空");
        }
        // MySQL 标识符引用符（反引号）视为合法引用，先行剥离：AI 指标自动分析生成的口径形如
        // SUM(`amount`)，若直接送进注入防护会被误判为危险字符并抛 IllegalArgumentException → 500
        // （2026-09-23 事故）。剥离后仍对其余字符校验 ; -- ' "，注入面不因此扩大。
        String trimmed = expression.replace("`", "").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("归因度量表达式不能为空");
        }
        if (DANGEROUS.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("度量表达式含不允许的字符（; -- /* */ ' \" ）：" + trimmed);
        }
        return trimmed;
    }

    /** 括号是否配对（用于判断 SUM(...) 的右括号是否确实是最外层）。 */
    private boolean isBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }
}
