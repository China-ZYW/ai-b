package com.aibi.module.query.builder;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.chat.nl2sql.TableRelation;
import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.query.dto.QueryDtos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 可视化查询构造器 → SQL 生成器（纯静态、无 Spring 依赖，便于脱离容器单测）。
 *
 * <p><b>安全模型：用户交出来的只有"选择"，没有"文本"</b>——构造器不接收任何自由形式的表名 / 列名 / 别名：
 * 前端只能从后端给出的清单里挑，挑中的表与列还要与 {@link TableMeta} 做一次存在性比对后才允许出现在 SQL 里
 * （比对用的是 schema 里的<b>原始拼写</b>，所以用户怎么写都不影响最终文本）；
 * 聚合函数与运算符走白名单；<b>筛选值一律以 {@code ?} 占位符交给 {@code PreparedStatement} 绑定，永不进入 SQL 文本</b>。
 * 因此构造器这条路径上不存在 SQL 注入面——生成结果照例还要过一遍 {@code SqlValidator}，属于第二道闸。
 *
 * <p><b>连接键的方向由服务端决定</b>：前端只回传它从 {@code /api/query/builder/relations} 选中的关系标识，
 * 用哪一对列做 {@code ON}、主表站在哪一侧，都由本类按图谱事实翻转，前端既无从指定也无法伪造。
 *
 * <p><b>聚合与分组</b>：只要有任意一列带聚合函数，就把所有"原始列"计入 {@code GROUP BY}——
 * 这是 SQL 标准的强制约束（非聚合列必须出现在 GROUP BY 里），漏掉会得到"结果不可预期"的静默错误。
 * 同时对 {@code SUM/AVG} 做一次类型族校验，把"对字符串求和"这种必错用法在生成期挡下来。
 */
public final class SqlBuilder {

    /** 聚合函数白名单。 */
    private static final Set<String> AGGS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    /** 运算符白名单。 */
    private static final Set<String> OPS = Set.of(
            "=", "!=", "<>", ">", ">=", "<", "<=",
            "LIKE", "NOT LIKE", "IN", "NOT IN", "BETWEEN", "IS NULL", "IS NOT NULL");

    /** 生成 SQL 的行数上限与偏移量上限（与执行侧 {@code aibi.query.max-rows} 呼应，生成期先夹一道）。 */
    private static final long MAX_LIMIT = 10000L;
    private static final long MAX_OFFSET = 100000L;

    private SqlBuilder() {
    }

    /**
     * 按规格生成一条只读 SQL。
     *
     * @param tables    该数据源的表结构（标识符白名单的唯一来源）
     * @param relations 该数据源的关联图谱（连接键的唯一来源）
     */
    public static Built build(QueryDtos.BuilderSpec spec, List<TableMeta> tables,
                              List<TableRelation> relations) {
        if (spec == null || !hasText(spec.mainTable())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先在构造器里选择主表");
        }
        Map<String, TableMeta> byName = index(tables);
        TableMeta main = byName.get(shortName(spec.mainTable()));
        if (main == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该数据源里没有表：" + spec.mainTable());
        }
        // 表 → 别名。主表恒为 t1；多表时一律用别名限定，避免跨表同名列（id/name）混淆
        Map<String, String> aliasOf = new LinkedHashMap<>();
        aliasOf.put(shortName(main.name()), "t1");

        StringBuilder sb = new StringBuilder();
        // 别名表要先补全再生成 SELECT：否则会出现"SELECT users.city … JOIN users AS t2 ON t2.id"这种
        // 同一张表两种写法的怪形态（不影响正确性，但读起来像 bug，也让预览失去教学价值）
        String joins = joinsSql(spec, byName, aliasOf, relations, shortName(main.name()), main);
        Plan plan = plan(spec, byName, aliasOf, main);

        sb.append("SELECT ");
        sb.append(String.join(", ", plan.items()));
        sb.append(" FROM ").append(main.name()).append(" AS t1").append(joins);

        List<Object> params = new ArrayList<>();
        String where = whereSql(spec, byName, aliasOf, params);
        if (where != null) {
            sb.append(" WHERE ").append(where);
        }
        // 有原始列但没有聚合列时<b>绝不能</b>生成 GROUP BY：那会把明细行按原始列折叠掉，
        // 得到"行数变少、看着像去重"的错结果——静默错数比报错难发现得多
        if (plan.aggregated() && !plan.groups().isEmpty()) {
            sb.append(" GROUP BY ").append(String.join(", ", plan.groups()));
        }
        String orderBy = orderBySql(spec, byName, aliasOf, plan);
        if (orderBy != null) {
            sb.append(" ORDER BY ").append(orderBy);
        }
        sb.append(limitSql(spec));
        return new Built(sb.toString(), params);
    }

    /** 生成产物：SQL 文本 + 与 {@code ?} 一一对应的绑定值（顺序即出现顺序）。 */
    public record Built(String sql, List<Object> params) {
    }

    /** SELECT 子句的计算结果。 */
    private record Plan(List<String> items, List<String> groups,
                        Map<String, String> exprByKey, boolean aggregated) {
    }

    // ==================== 子句生成 ====================

    /**
     * 一次算出 SELECT 列表、GROUP BY 项与"列 → 可排序表达式"的映射。
     *
     * <p>三者必须共用同一份判断（哪些列带聚合、哪些列是原始的），否则会出现
     * "GROUP BY 里没有它、ORDER BY 却排它"这种只有严格模式的库才报、宽松库却静默给错数的组合。
     */
    private static Plan plan(QueryDtos.BuilderSpec spec, Map<String, TableMeta> byName,
                             Map<String, String> aliasOf, TableMeta main) {
        List<String> items = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        Map<String, String> exprByKey = new LinkedHashMap<>();
        Set<String> usedAlias = new LinkedHashSet<>();
        boolean aggregated = false;

        for (QueryDtos.BuilderSelect s : nullSafe(spec.selects())) {
            if (s == null || !hasText(s.column())) {
                continue;
            }
            String tableKey = shortName(s.table());
            ColumnMeta col = findColumn(byName.get(tableKey), s.column());
            if (col == null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "表 " + tableKey + " 里没有列 " + s.column());
            }
            String qualified = qual(tableKey, aliasOf) + "." + col.name();
            String agg = upper(s.agg());
            boolean isAgg = hasText(agg);
            String alias = uniqueAlias(usedAlias, isAgg ? lower(agg) + "_" + col.name() : col.name());
            if (isAgg) {
                if (!AGGS.contains(agg)) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "聚合函数只能是 COUNT / SUM / AVG / MIN / MAX，收到：" + agg);
                }
                if (("SUM".equals(agg) || "AVG".equals(agg)) && !isNumeric(col.type())) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "列 " + tableKey + "." + col.name() + " 不是数值类型，不能求" + agg + "值");
                }
                items.add(agg + "(" + qualified + ") AS " + alias);
                exprByKey.put(key(tableKey, col.name()), alias);
                aggregated = true;
            } else {
                items.add(qualified + " AS " + alias);
                groups.add(qualified);
                exprByKey.put(key(tableKey, col.name()), qualified);
            }
        }
        if (items.isEmpty()) {
            // 一列都没选的查询没有意义：给个兜底选择器，避免构造器存出一条必错的 SQL
            String pk = firstColumn(main);
            if (pk == null) {
                items.add("*");
            } else {
                items.add(qual(shortName(main.name()), aliasOf) + "." + pk + " AS " + pk);
            }
        }
        return new Plan(items, groups, exprByKey, aggregated);
    }

    private static String joinsSql(QueryDtos.BuilderSpec spec, Map<String, TableMeta> byName,
                                   Map<String, String> aliasOf,
                                   List<TableRelation> relations, String mainKey, TableMeta main) {
        Map<String, TableRelation> byKey = new LinkedHashMap<>();
        if (relations != null) {
            for (TableRelation r : relations) {
                if (r != null) {
                    byKey.putIfAbsent(r.pairKey(), r);
                }
            }
        }
        Set<String> used = new LinkedHashSet<>();
        used.add(mainKey);
        List<String> out = new ArrayList<>();
        int seq = 1;   // t1 已给主表
        for (QueryDtos.BuilderJoin j : nullSafe(spec.joins())) {
            if (j == null || !hasText(j.relationKey())) {
                continue;
            }
            TableRelation r = byKey.get(j.relationKey().trim());
            if (r == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "所选关联不可用（可能已被调整），请重新选择");
            }
            String lt = shortName(r.leftTable());
            String rt = shortName(r.rightTable());
            if (lt.equalsIgnoreCase(rt)) {
                continue;   // 自关联不生成
            }
            String other;
            String selfCol;
            String otherCol;
            if (lt.equalsIgnoreCase(mainKey)) {
                other = rt;
                selfCol = r.leftColumn();
                otherCol = r.rightColumn();
            } else if (rt.equalsIgnoreCase(mainKey)) {
                // 主表站在"一"的一侧：把连接方向翻转过来，SQL 仍然读作 主表.键 = 维表.键
                other = lt;
                selfCol = r.rightColumn();
                otherCol = r.leftColumn();
            } else {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "所选关联（" + lt + " = " + rt + "）与主表 " + mainKey + " 无关");
            }
            if (!used.add(other)) {
                continue;   // 同一张表被多条关联重复引入，保留先来的那条
            }
            // 被关联的表必须真的在 schema 里（图谱可能来自人工登记，以 schema 里的写法为准）
            TableMeta meta = byName.get(other);
            if (meta == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "关联表在数据源结构里不存在：" + other);
            }
            String alias = "t" + (++seq);
            aliasOf.put(other, alias);
            out.add(" JOIN " + meta.name() + " AS " + alias
                    + " ON t1." + selfCol + " = " + alias + "." + otherCol);
        }
        return String.join("", out);
    }

    /** WHERE：值全部走占位符，绑定值按列类型强转后进 {@code params}。 */
    private static String whereSql(QueryDtos.BuilderSpec spec, Map<String, TableMeta> byName,
                                   Map<String, String> aliasOf, List<Object> params) {
        List<String> out = new ArrayList<>();
        for (QueryDtos.BuilderFilter f : nullSafe(spec.filters())) {
            if (f == null || !hasText(f.column()) || !hasText(f.op())) {
                continue;
            }
            String tableKey = shortName(f.table());
            ColumnMeta col = findColumn(byName.get(tableKey), f.column());
            if (col == null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "表 " + tableKey + " 里没有列 " + f.column());
            }
            String op = upper(f.op());
            if (!OPS.contains(op)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "不支持的筛选运算符：" + f.op());
            }
            String expr = qual(tableKey, aliasOf) + "." + col.name();
            if ("IS NULL".equals(op) || "IS NOT NULL".equals(op)) {
                out.add(expr + " " + op);
                continue;
            }
            List<String> values = nullSafe(f.values());
            if (values.isEmpty() || values.stream().allMatch(String::isBlank)) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "筛选条件 " + tableKey + "." + col.name() + " " + op + " 缺少取值");
            }
            if ("BETWEEN".equals(op)) {
                if (values.size() < 2) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "BETWEEN 需要填上限与下限两个取值");
                }
                // 两个边界各占一个 ?，取值顺序与占位符顺序严格一致
                params.add(coerce(col, values.get(0)));
                params.add(coerce(col, values.get(1)));
                out.add(expr + " BETWEEN ? AND ?");
                continue;
            }
            if ("IN".equals(op) || "NOT IN".equals(op)) {
                StringBuilder ph = new StringBuilder("(");
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        ph.append(", ");
                    }
                    ph.append("?");
                    params.add(coerce(col, values.get(i)));
                }
                out.add(expr + " " + op + " " + ph + ")");
                continue;
            }
            params.add(coerce(col, values.get(0)));
            out.add(expr + " " + op + " ?");
        }
        return out.isEmpty() ? null : String.join(" AND ", out);
    }

    /**
     * ORDER BY：优先复用 SELECT 里那一列的表达式（聚合列用别名，原始列用限定名），
     * 两者都不匹配时——<b>分组查询下直接拒绝</b>——因为严格模式（PostgreSQL / MySQL ONLY_FULL_GROUP_BY）
     * 会报错，宽松模式却会静默地按未分组的列排序，得到一个"看起来对、其实乱序"的结果。
     */
    private static String orderBySql(QueryDtos.BuilderSpec spec, Map<String, TableMeta> byName,
                                     Map<String, String> aliasOf, Plan plan) {
        List<String> out = new ArrayList<>();
        for (QueryDtos.BuilderSort s : nullSafe(spec.sorts())) {
            if (s == null || !hasText(s.column())) {
                continue;
            }
            String tableKey = shortName(s.table());
            ColumnMeta col = findColumn(byName.get(tableKey), s.column());
            if (col == null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "表 " + tableKey + " 里没有列 " + s.column());
            }
            String expr = plan.exprByKey().get(key(tableKey, col.name()));
            if (expr == null) {
                if (!plan.groups().isEmpty()) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "分组查询只能按输出列排序：" + tableKey + "." + col.name() + " 不在输出列里，"
                                    + "（把它加进「输出列」后再排序）");
                }
                expr = qual(tableKey, aliasOf) + "." + col.name();
            }
            out.add(expr + (Boolean.TRUE.equals(s.desc()) ? " DESC" : " ASC"));
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    /** LIMIT/OFFSET：生成期先夹到合理区间，避免构造器造出 {@code LIMIT 0} 或天文数字。 */
    private static String limitSql(QueryDtos.BuilderSpec spec) {
        long limit = spec.limit() == null || spec.limit() <= 0 ? 1000 : Math.min(spec.limit(), MAX_LIMIT);
        long offset = spec.offset() == null || spec.offset() < 0 ? 0 : Math.min(spec.offset(), MAX_OFFSET);
        return offset <= 0 ? " LIMIT " + limit : " LIMIT " + limit + " OFFSET " + offset;
    }

    // ==================== 标识符解析与取值强转 ====================

    /**
     * 限定前缀：有别名必须用别名。
     *
     * <p><b>为什么不能"单表时退回表名"</b>：FROM 恒定输出 {@code AS t1}（多表才有 t2…），
     * 而 MySQL / PostgreSQL 一旦给了表别名，原表名在整条语句里就不可再用——
     * 单表时生成 {@code SELECT ad_placement.id … FROM ad_placement AS t1} 会直接报
     * {@code Unknown column 'ad_placement.id' in 'field list'}（09-27 真机事故）。
     * aliasOf 里的表必有别名，没有登记的表（理论上不存在）才退回表名。
     */
    private static String qual(String tableKey, Map<String, String> aliasOf) {
        String alias = aliasOf.get(tableKey);
        return alias == null ? tableKey : alias;
    }

    private static String key(String table, String column) {
        return table + "|" + column;
    }

    /** 把"用户选的 表.列"解析成 schema 里的原始拼写（未匹配视为选错了，直接拒）。 */
    private static ColumnMeta findColumn(TableMeta t, String column) {
        if (t == null || t.columns() == null || column == null) {
            return null;
        }
        String want = column.trim().toLowerCase(Locale.ROOT);
        for (ColumnMeta c : t.columns()) {
            if (c != null && c.name() != null && c.name().trim().toLowerCase(Locale.ROOT).equals(want)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isNumeric(String type) {
        String t = lower(type);
        return t.contains("int") || t.contains("decimal") || t.contains("numeric") || t.contains("float")
                || t.contains("double") || t.contains("real") || t.contains("number") || t.contains("money");
    }

    /**
     * 按列类型把筛选值强转成绑定类型。
     *
     * <p><b>为什么必须强转</b>：表单里拿到的永远是字符串，直接 {@code setObject("100")} 会让
     * {@code WHERE amount > ?} 在严格类型的库上（PostgreSQL 等）报"操作符不存在"，
     * 而在宽松的库上又可能因为隐式转换丢索引。强转后行为在两种库上一致。
     */
    private static Object coerce(ColumnMeta col, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (isNumeric(col.type())) {
            try {
                boolean decimal = v.indexOf('.') >= 0 || v.indexOf('e') >= 0 || v.indexOf('E') >= 0;
                return decimal ? (Object) Double.valueOf(v) : (Object) Long.valueOf(v);
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "筛选值「" + raw + "」不是数字，但列 " + col.name() + " 是数值类型");
            }
        }
        if (lower(col.type()).contains("bool")) {
            if ("true".equalsIgnoreCase(v) || "1".equals(v)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
                return Boolean.FALSE;
            }
        }
        return v;
    }

    /** 输出别名：剥掉非字母数字字符、数字开头补前缀、按出现次数去重，保证一定能安全拼进 SQL。 */
    private static String uniqueAlias(Set<String> used, String prefer) {
        StringBuilder sb = new StringBuilder();
        if (prefer != null) {
            for (int i = 0; i < prefer.length() && sb.length() < 32; i++) {
                char c = prefer.charAt(i);
                sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
            }
        }
        if (sb.length() == 0 || !Character.isLetter(sb.charAt(0))) {
            sb.insert(0, "c_");
        }
        String alias = sb.toString();
        if (!used.add(alias)) {
            int n = 2;
            while (!used.add(alias + "_" + n)) {
                n++;
            }
            alias = alias + "_" + n;
        }
        return alias;
    }

    // ==================== 小工具 ====================

    private static Map<String, TableMeta> index(List<TableMeta> tables) {
        Map<String, TableMeta> idx = new LinkedHashMap<>();
        if (tables == null) {
            return idx;
        }
        for (TableMeta t : tables) {
            if (t != null && t.name() != null) {
                idx.putIfAbsent(shortName(t.name()), t);
            }
        }
        return idx;
    }

    private static String firstColumn(TableMeta t) {
        if (t != null && t.columns() != null) {
            for (ColumnMeta c : t.columns()) {
                if (c != null && c.name() != null && !c.name().isBlank()) {
                    return c.name();
                }
            }
        }
        return null;
    }

    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
