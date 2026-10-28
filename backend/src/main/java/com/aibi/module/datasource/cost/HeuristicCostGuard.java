package com.aibi.module.datasource.cost;

import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.model.DataSourceConfig;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一期启发式代价预估（T10-1 落地实现，零 LLM、纯规则）。
 *
 * <p>不依赖各库 {@code EXPLAIN} 输出差异，纯靠「JSqlParser 解析 SQL 结构 + 系统视图近似行数」估扫描量：
 * <ol>
 *   <li><b>结构解析</b>：取 FROM/JOIN 引用的基表集合与连接结构（同 {@code JoinGuard} 的解析纪律）；</li>
 *   <li><b>笛卡尔判定</b>：基表 ≥ 2 且存在某张表未被任何跨表等值条件（ON 或老式逗号连接的 WHERE）连上
 *       → 判笛卡尔，直接 REJECT（这是与行数无关的结构性高危）；</li>
 *   <li><b>行数估算</b>：单表有 WHERE → {@code 行数 × 选择性折扣}；单表无 WHERE → 全表行数；
 *       多表等值连接 → {@code MAX(各表行数)}（近似，不做乘积）；</li>
 *   <li><b>阈值判定</b>：估算超 {@code cost-scan-rows-threshold} 或「无 WHERE 全表扫 {@code cost-big-table-rows}
 *       级以上大表」→ REJECT；落在 {@code cost-warn-rows-threshold} 与 reject 之间 → WARN。</li>
 * </ol>
 *
 * <p><b>fail-open 纪律（宁漏勿误杀）</b>：总开关关闭、解析失败、非 {@code PlainSelect}（UNION/CTE）、
 * 含非 ASCII 标识符、FROM 是子查询、行数取不到（UNKNOWN）——一律放行或最多 WARN，绝不因估不准而拒绝合法 SQL。
 * 仅对「确凿的结构性高危（笛卡尔）」与「确凿超预算」REJECT。{@code cost-block=false} 时所有 REJECT 降级为 WARN。
 */
@Component
public class HeuristicCostGuard implements CostGuard {

    private final AibiConfig aibiConfig;
    private final CostStatsProvider statsProvider;

    public HeuristicCostGuard(AibiConfig aibiConfig, CostStatsProvider statsProvider) {
        this.aibiConfig = aibiConfig;
        this.statsProvider = statsProvider;
    }

    @Override
    public CostVerdict assess(DataSourceConfig ds, String sql) {
        // 总开关关闭：恒放行，adapter 行为与未接入本特性逐字节等价
        if (!aibiConfig.costGuardEnabled()) {
            return CostVerdict.pass();
        }
        if (isBlank(sql) || !isAscii(sql)) {
            return CostVerdict.pass();   // 含中文标识符 JSqlParser 4.6 解析会失败，保守放行
        }
        PlainSelect select;
        try {
            Statement statement = CCJSqlParserUtil.parse(sql.trim());
            if (!(statement instanceof Select)) {
                return CostVerdict.pass();
            }
            SelectBody body = ((Select) statement).getSelectBody();
            if (!(body instanceof PlainSelect)) {
                return CostVerdict.pass();   // UNION / 集合操作：结构多样，不在这里判
            }
            select = (PlainSelect) body;
        } catch (Exception e) {
            return CostVerdict.pass();       // 方言专有语法解析不了 → 放行（安全校验已由 SqlValidator 兜底）
        }

        // 别名 / 表名 → 真实短名（小写）；子查询与派生表不登记
        Map<String, String> alias = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        registerFrom(select.getFromItem(), alias, tables);
        for (Join join : nullSafe(select.getJoins())) {
            registerFrom(join.getRightItem(), alias, tables);
        }
        if (tables.isEmpty()) {
            return CostVerdict.pass();   // FROM 是子查询 / 无基表：无法估行数，放行
        }

        boolean hasWhere = select.getWhere() != null;

        // 跨表等值条件涉及的表（ON + WHERE），用于笛卡尔连通性判定
        Set<String> linked = new LinkedHashSet<>();
        List<Expression> conditions = new ArrayList<>();
        for (Join join : nullSafe(select.getJoins())) {
            Collection<Expression> ons = join.getOnExpressions();
            if (ons != null) {
                conditions.addAll(ons);
            }
        }
        if (hasWhere) {
            conditions.add(select.getWhere());
        }
        for (Expression cond : conditions) {
            collectLinkedTables(cond, alias, linked);
        }

        // ① 笛卡尔：多表且存在未与任何其他表建立等值连接的基表（结构性高危，与行数无关）
        if (tables.size() >= 2) {
            for (String t : tables) {
                if (!linked.contains(t)) {
                    return decide(CostVerdict.Level.REJECT, CostVerdict.UNKNOWN_ROWS,
                            "笛卡尔积风险：表 " + t + " 与其他表之间无有效连接键",
                            "检测到笛卡尔积风险（表 " + t + " 缺少有效连接键），"
                                    + "两表全组合会瞬间产生海量中间行。请补上正确的 JOIN 条件；"
                                    + "若确无可用连接键，请去掉这张表。");
                }
            }
        }

        // ② 行数估算
        int selectivity = clamp(aibiConfig.costSelectivity(), 0, 100);
        long scanThreshold = aibiConfig.costScanRowsThreshold();
        long warnThreshold = aibiConfig.costWarnRowsThreshold();
        long bigTableRows = aibiConfig.costBigTableRows();

        long est;
        boolean anyUnknown = false;
        if (tables.size() == 1) {
            long rows = statsProvider.estimateRowCount(ds, tables.get(0));
            if (rows == CostVerdict.UNKNOWN_ROWS) {
                anyUnknown = true;
                est = CostVerdict.UNKNOWN_ROWS;
            } else {
                est = hasWhere ? rows * selectivity / 100L : rows;
                // 无 WHERE 且是大表 → 全表扫描高危
                if (!hasWhere && rows > bigTableRows) {
                    return decide(CostVerdict.Level.REJECT, rows,
                            "全表扫描：表 " + tables.get(0) + " 约 " + rows + " 行且无过滤条件",
                            "该查询将全表扫描 " + tables.get(0) + "（约 " + formatRows(rows)
                                    + " 行）且没有任何过滤条件。请至少增加一个过滤维度（时间 / 地区 / 状态等）。");
                }
            }
        } else {
            // 多表等值连接：取各表行数的最大值近似（只计已知表）
            long max = 0;
            boolean anyKnown = false;
            for (String t : tables) {
                long rows = statsProvider.estimateRowCount(ds, t);
                if (rows == CostVerdict.UNKNOWN_ROWS) {
                    anyUnknown = true;
                } else {
                    anyKnown = true;
                    max = Math.max(max, rows);
                }
            }
            est = anyKnown ? max : CostVerdict.UNKNOWN_ROWS;
        }

        // ③ 阈值判定（UNKNOWN 不拒绝；仅可 WARN）
        if (est == CostVerdict.UNKNOWN_ROWS) {
            // 行数取不到：有过滤则放行，无过滤保守 WARN（不误伤）
            if (hasWhere) {
                return CostVerdict.pass();
            }
            return new CostVerdict(CostVerdict.Level.WARN, CostVerdict.UNKNOWN_ROWS,
                    "行数未知且无过滤，保守放行", null);
        }
        if (est > scanThreshold) {
            return decide(CostVerdict.Level.REJECT, est,
                    "预估扫描约 " + est + " 行，超过安全上限 " + scanThreshold,
                    "预计需扫描约 " + formatRows(est) + " 行，超过安全上限（"
                            + formatRows(scanThreshold) + " 行）。请增加时间/维度过滤，或按时间段分片查询。");
        }
        if (est > warnThreshold) {
            return new CostVerdict(CostVerdict.Level.WARN, est,
                    "预估扫描 " + est + " 行，偏大但未超上限", null);
        }
        // 已知但估算偏低：即便存在 UNKNOWN 表也放行（UNKNOWN≠危险）
        return new CostVerdict(CostVerdict.Level.PASS, est, anyUnknown ? "部分表行数未知" : "", null);
    }

    /**
     * 统一出口：{@code cost-block=false} 时把 REJECT 降级为 WARN（灰度/排障用，仅告警不阻断）。
     */
    private CostVerdict decide(CostVerdict.Level level, long est, String reason, String advice) {
        if (level == CostVerdict.Level.REJECT && !aibiConfig.costBlockEnabled()) {
            return new CostVerdict(CostVerdict.Level.WARN, est, "[未阻断] " + reason, advice);
        }
        return new CostVerdict(level, est, reason, advice);
    }

    // ==================== JSqlParser 解析小工具（与 JoinGuard 同源纪律） ====================

    /** 登记 FROM / JOIN 右侧基表：加入 {@code tables} 并登记别名映射；子查询/派生表跳过。 */
    private static void registerFrom(FromItem item, Map<String, String> alias, List<String> tables) {
        if (!(item instanceof Table t)) {
            return;
        }
        String real = shortLower(unquote(t.getName()));
        if (real.isEmpty()) {
            return;
        }
        if (t.getAlias() != null && !isBlank(t.getAlias().getName())) {
            alias.put(lower(unquote(t.getAlias().getName())), real);
        }
        alias.putIfAbsent(real, real);
        if (!tables.contains(real)) {
            tables.add(real);
        }
    }

    /** 收集表达式里“两张不同表之间的等值比较”涉及的两表短名（登记为已连接）。 */
    private static void collectLinkedTables(Expression e, Map<String, String> alias, Set<String> linked) {
        if (e instanceof EqualsTo eq) {
            if (eq.getLeftExpression() instanceof Column lc && eq.getRightExpression() instanceof Column rc) {
                String lt = tableOf(lc, alias);
                String rt = tableOf(rc, alias);
                if (lt != null && rt != null && !lt.equals(rt)) {
                    linked.add(lt);
                    linked.add(rt);
                }
            }
            return;
        }
        if (e instanceof Parenthesis p) {
            collectLinkedTables(p.getExpression(), alias, linked);
            return;
        }
        if (e instanceof BinaryExpression b) {
            collectLinkedTables(b.getLeftExpression(), alias, linked);
            collectLinkedTables(b.getRightExpression(), alias, linked);
        }
    }

    /** 列所属表短名（经别名映射归一）；无表限定或限定名不在本查询表集合内（如 CTE）返回 null。 */
    private static String tableOf(Column column, Map<String, String> alias) {
        Table t = column.getTable();
        if (t == null || isBlank(t.getName())) {
            return null;
        }
        return alias.get(lower(unquote(t.getName())));
    }

    // ==================== 通用小工具 ====================

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 行数可读化：整万/整亿用中文数量级，避免用户看一长串 0。 */
    private static String formatRows(long rows) {
        if (rows >= 100_000_000L) {
            return String.format(Locale.ROOT, "%.1f 亿", rows / 100_000_000.0);
        }
        if (rows >= 10_000L) {
            return String.format(Locale.ROOT, "%.1f 万", rows / 10_000.0);
        }
        return String.valueOf(rows);
    }

    private static String unquote(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                v = v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    private static String shortLower(String name) {
        String v = lower(name);
        int last = v.lastIndexOf('.');
        return last < 0 ? v : v.substring(last + 1);
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
