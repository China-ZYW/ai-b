package com.aibi.module.chat.nl2sql;

import com.aibi.module.datasource.model.TableMeta;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JOIN 静态校验（多表问数准确率的最后一道闸）：在 SQL 落库执行<b>之前</b>解析它的连接结构，
 * 把"能跑通但数值错"的关联错误挑出来交给 {@link SqlRepairer} 修正。
 *
 * <p><b>为什么需要它</b>：错连与笛卡尔积不会报错——{@code orders JOIN order_item ON orders.id = order_item.id}
 * 语法合法、结果非空、行数还挺多，只有数值是错的（fan-out 把金额放大若干倍）。执行期的报错修正回路
 * 与空结果自修复都抓不到它，辅助模型交叉校验能抓但要花一次模型调用且不稳定。本类用图谱做确定性判断，
 * 零模型成本。
 *
 * <p><b>三条规则，全部要求有确凿证据才报</b>（误伤合法 SQL 的代价高于漏判）：
 * <ol>
 *   <li><b>笛卡尔积</b>：逗号连接 / CROSS JOIN / JOIN 无 ON，且 WHERE 里也找不到把该表与其余表连起来的等值条件；</li>
 *   <li><b>连接键与图谱矛盾</b>：SQL 用的键不在图谱里，而图谱里这两张表<b>确实有</b>可用键——
 *       此时才断言"用错了"。图谱里没有这两张表的键时一律放行（图谱可能不全，尤其是关掉命名推断后）；</li>
 *   <li><b>两表用各自的主键 {@code id} 互连</b>：主键只在本表内唯一，这样连几乎必然是错的。</li>
 * </ol>
 *
 * <p><b>永不抛出、宁缺毋滥</b>：解析失败（方言专有语法）、非 {@code PlainSelect}（UNION / CTE 主体）、
 * 含非 ASCII 标识符（JSqlParser 4.6 不接受未加引号的中文标识符，而折叠占位符会让不同中文表名撞成同一串）、
 * FROM 是子查询——一律返回 {@code null} 放行，由后续的执行期修正与辅助模型校验兜底。
 */
public final class JoinGuard {

    private JoinGuard() {
    }

    /**
     * 校验一条 SQL 的 JOIN 结构。
     *
     * @param sql       已通过 {@link SqlValidator} 安全校验的候选 SQL
     * @param tables    本次进入 Prompt 的表（用于查主键，判断是否"两表同名主键互连"）
     * @param relations 与选表、Prompt 完全同源的关联图谱（{@link SchemaSlice#relations()}）
     * @return 修正意见（喂给 {@link SqlRepairer}），没有问题或无法判定时返回 {@code null}
     */
    public static String inspect(String sql, List<TableMeta> tables, List<TableRelation> relations) {
        if (isBlank(sql) || tables == null || tables.size() < 2) {
            return null;   // 单表查询没有连接可言
        }
        if (!isAscii(sql)) {
            return null;
        }
        PlainSelect select;
        try {
            Statement statement = CCJSqlParserUtil.parse(sql.trim());
            if (!(statement instanceof Select)) {
                return null;
            }
            SelectBody body = ((Select) statement).getSelectBody();
            if (!(body instanceof PlainSelect)) {
                return null;   // UNION / 集合操作：各分支结构不同，不在这里判
            }
            select = (PlainSelect) body;
        } catch (Exception e) {
            return null;       // 方言专有语法解析不了（JSQLParserException）→ 放行，安全校验已由 SqlValidator 兜底
        }

        // 别名 / 表名 → 库中真实表名（短名小写）；子查询与派生表不登记，引用它们的等值条件自然被忽略
        Map<String, String> alias = new LinkedHashMap<>();
        registerFrom(select.getFromItem(), alias);

        List<SqlKey> used = new ArrayList<>();
        List<String> unlinked = new ArrayList<>();
        for (Join join : nullSafe(select.getJoins())) {
            String right = registerFrom(join.getRightItem(), alias);
            Collection<Expression> ons = join.getOnExpressions();
            boolean hasCondition = (ons != null && !ons.isEmpty())
                    || (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty())
                    || join.isNatural();
            if (!hasCondition) {
                if (right != null) {
                    unlinked.add(right);
                }
                continue;
            }
            for (Expression on : ons) {
                collectKeys(on, alias, used);
            }
        }
        // 老式逗号连接把条件写在 WHERE，故 WHERE 里的等值同样算"已连接"
        collectKeys(select.getWhere(), alias, used);

        List<String> problems = new ArrayList<>();
        for (String table : unlinked) {
            if (!linksAny(table, used)) {
                problems.add("表 " + table + " 与查询里其他表之间没有任何连接条件（逗号连接或 CROSS JOIN），"
                        + "会产生笛卡尔积、把统计值成倍放大；请补上正确的 JOIN 条件，"
                        + "若确实没有可用连接键则去掉这张表，不要硬凑。");
            }
        }
        problems.addAll(checkKeys(used, tables, relations));
        if (problems.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("SQL 的 JOIN 结构存在以下问题：\n");
        for (String p : problems) {
            sb.append("- ").append(p).append('\n');
        }
        return sb.append("请只修正上述连接键问题，其余部分（选表、过滤条件、聚合方式、字段与别名）保持不变；"
                + "一对多关联后直接汇总会重复计数，必要时先在子查询里按主表粒度聚合。").toString();
    }

    // ==================== 连接键校验 ====================

    /** 逐条比对 SQL 实际用到的连接键与图谱（规则 ②③）。 */
    private static List<String> checkKeys(List<SqlKey> used, List<TableMeta> tables, List<TableRelation> relations) {
        List<String> problems = new ArrayList<>();
        if (used.isEmpty()) {
            return problems;
        }
        Map<String, List<TableRelation>> byPair = new HashMap<>();
        for (TableRelation r : nullSafe(relations)) {
            if (r == null || isBlank(r.leftTable()) || isBlank(r.rightTable())) {
                continue;
            }
            byPair.computeIfAbsent(pairOf(r.leftTable(), r.rightTable()), k -> new ArrayList<>()).add(r);
        }
        Map<String, TableMeta> byName = new HashMap<>();
        for (TableMeta t : tables) {
            if (t != null && t.name() != null) {
                byName.put(shortLower(t.name()), t);
            }
        }
        Set<String> reported = new HashSet<>();
        for (SqlKey k : used) {
            if (!reported.add(k.pairKey())) {
                continue;   // 同一对键在 ON 与 WHERE 里重复出现只报一次
            }
            List<TableRelation> known = byPair.get(pairOf(k.leftTable(), k.rightTable()));
            if (known == null || known.isEmpty()) {
                // 图谱里没有这两张表的键：可能是图谱不全，也可能是合法的复合键/业务键，不判错；
                // 但“两表各自的主键 id 互连”是无关图谱就能确定的硬错误，仍然要报
                problems.addAll(samePkProblem(k, byName));
                continue;
            }
            if (matchesAny(k, known)) {
                continue;   // 用的正是图谱里的键，正确
            }
            problems.add(k.leftTable() + "." + k.leftColumn() + " = " + k.rightTable() + "." + k.rightColumn()
                    + " 不是这两张表之间的可用连接键；已知可用的连接键是：" + render(known)
                    + "，请改用它。");
        }
        return problems;
    }

    /** SQL 用的键是否命中图谱里这对表的任一条关联。 */
    private static boolean matchesAny(SqlKey k, List<TableRelation> known) {
        for (TableRelation r : known) {
            if (r.pairKey().equals(k.pairKey())) {
                return true;
            }
        }
        return false;
    }

    /** 规则③：两表用各自的主键 {@code id} 互连（主键只在本表内唯一，这样连几乎必然错）。 */
    private static List<String> samePkProblem(SqlKey k, Map<String, TableMeta> byName) {
        if (!k.leftColumn().equals(k.rightColumn()) || !"id".equals(k.leftColumn())) {
            return List.of();
        }
        TableMeta left = byName.get(k.leftTable());
        TableMeta right = byName.get(k.rightTable());
        if (isPrimaryKey(left, "id") && isPrimaryKey(right, "id")) {
            return List.of(k.leftTable() + ".id = " + k.rightTable() + ".id 用两张表各自的主键互连，"
                    + "主键只在本表内唯一，这样关联会把不相关的行凑在一起；请改用真正持有外键的那一列。");
        }
        return List.of();
    }

    private static String render(List<TableRelation> known) {
        List<String> out = new ArrayList<>();
        for (TableRelation r : known) {
            out.add(r.leftTable() + "." + r.leftColumn() + " = " + r.rightTable() + "." + r.rightColumn());
        }
        return String.join("；", out);
    }

    // ==================== 表达式遍历 ====================

    /**
     * 收集表达式里所有"两张不同表之间的等值比较"。
     *
     * <p>只递归 {@link BinaryExpression}（覆盖 AND / OR / 各类比较）与 {@link Parenthesis}：
     * OR 里的等值同样算"把两张表连起来了"，否则 {@code ON (a.x=b.x OR a.y=b.y)} 会被误判成笛卡尔积。
     */
    private static void collectKeys(Expression e, Map<String, String> alias, List<SqlKey> out) {
        if (e instanceof EqualsTo eq) {
            if (eq.getLeftExpression() instanceof Column lc && eq.getRightExpression() instanceof Column rc) {
                String lt = tableOf(lc, alias);
                String rt = tableOf(rc, alias);
                if (lt != null && rt != null && !lt.equals(rt)) {
                    out.add(new SqlKey(lt, unquote(lc.getColumnName()), rt, unquote(rc.getColumnName())));
                }
            }
            return;
        }
        if (e instanceof Parenthesis p) {
            collectKeys(p.getExpression(), alias, out);
            return;
        }
        if (e instanceof BinaryExpression b) {
            collectKeys(b.getLeftExpression(), alias, out);
            collectKeys(b.getRightExpression(), alias, out);
        }
    }

    /** 列所属的表（归一为库中真实短名小写）；无表限定、或限定名不是本次查询里的表（如 CTE）时返回 null。 */
    private static String tableOf(Column column, Map<String, String> alias) {
        Table t = column.getTable();
        if (t == null || isBlank(t.getName())) {
            return null;
        }
        return alias.get(lower(unquote(t.getName())));
    }

    /** 登记 FROM / JOIN 右侧的表，返回其归一名；子查询与派生表返回 null（不参与校验）。 */
    private static String registerFrom(FromItem item, Map<String, String> alias) {
        if (!(item instanceof Table t)) {
            return null;
        }
        String real = shortLower(unquote(t.getName()));
        if (real.isEmpty()) {
            return null;
        }
        if (t.getAlias() != null && !isBlank(t.getAlias().getName())) {
            alias.put(lower(unquote(t.getAlias().getName())), real);
        }
        alias.putIfAbsent(real, real);
        return real;
    }

    private static boolean linksAny(String table, List<SqlKey> used) {
        for (SqlKey k : used) {
            if (k.leftTable().equals(table) || k.rightTable().equals(table)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 小工具 ====================

    /**
     * SQL 里实际用到的一条连接键（表名与列名均已归一为小写，表名取限定名末段）。
     * {@link #pairKey()} 与 {@link TableRelation#pairKey()} 同构，可直接比对。
     */
    private record SqlKey(String leftTable, String leftColumn, String rightTable, String rightColumn) {
        SqlKey {
            leftTable = lower(leftTable);
            rightTable = lower(rightTable);
            leftColumn = lower(leftColumn);
            rightColumn = lower(rightColumn);
        }

        String pairKey() {
            String a = leftTable + "." + leftColumn;
            String b = rightTable + "." + rightColumn;
            return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
        }
    }

    private static String pairOf(String a, String b) {
        String x = shortLower(a);
        String y = shortLower(b);
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    private static boolean isPrimaryKey(TableMeta t, String column) {
        if (t == null || t.primaryKeys() == null) {
            return false;
        }
        String c = lower(column);
        for (String pk : t.primaryKeys()) {
            if (lower(pk).equals(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否纯 ASCII：含中文标识符时 JSqlParser 4.6 解析会失败，而"折叠成占位符"的做法会让
     * 不同中文表名撞成同一串（{@code 订单} 与 {@code 用户} 都变成 {@code nn}）从而误报，故直接跳过。
     */
    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
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

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
