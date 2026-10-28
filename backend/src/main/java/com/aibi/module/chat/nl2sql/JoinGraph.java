package com.aibi.module.chat.nl2sql;

import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.datasource.model.TableMeta.FkMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表关联图谱（多表问数准确率的地基）：把「哪两张表能用哪对键 JOIN」推导成 {@link TableRelation} 列表，
 * 再产出两类可用产物——
 * <ul>
 *   <li><b>关系扩散</b>（{@link #oneHopNeighbours}）：供 {@code SchemaLinker} 把「问法里一个词都没出现、
 *       但必须 join 才能回答」的维度表 / 桥接表补进切片。这类表按命中度打分恒为 0，
 *       在超过 {@code MAX_TABLES_IN_PROMPT} 的大库里必被裁掉，LLM 手里没有可 join 的表，
 *       只能自造关联键或直接漏掉维度；</li>
 *   <li><b>关联关系文本</b>（{@link #relationsText}）：供 {@code PromptBuilder} 渲染【关联关系】段，
 *       把连接键与基数（一对多）明写在 Prompt 里，减少错连与 fan-out 放大。</li>
 * </ul>
 *
 * <p>关联有三个来源，可信度依次递减（见 {@link TableRelation.Source}）：人工登记 &gt; 库中外键 &gt; 命名推断。
 * <b>分析型库与多数业务库都没有建 FOREIGN KEY</b>，只靠外键时本类会整体空转，故提供命名推断
 * （{@code order_item.order_id → orders.id}）作为无约束库的主力来源；推断结果在 Prompt 里单独措辞，
 * 明确告知 LLM「未必准确、与业务不符请忽略」，避免把猜测当成硬约束。
 *
 * <p>纯静态工具、只读表结构元数据，不做任何 IO。表名匹配对「库.表」限定前缀与大小写不敏感
 * （跨库联合分析下外键回传的表名可能与提示词里的呈现形式不完全一致）。
 */
public final class JoinGraph {

    /** 【关联关系】段最多渲染的条数：关系再多也不该把提示词撑爆，超出部分靠 schema 行里的「外键→」标记兜底。 */
    private static final int MAX_RELATIONS = 30;

    /** 命名推断的总条数上限：推断是启发式，宁少勿滥。 */
    private static final int MAX_INFERRED = 60;

    /** 单张表最多推断出几条关联：防宽表（几十个 xxx_id）把额度吃光。 */
    private static final int MAX_INFERRED_PER_TABLE = 6;

    /** 表名规范化时剥掉的分层前缀（数仓分层与建表习惯），剥掉后 {@code dim_product} 与 {@code product} 等价。 */
    private static final String[] TABLE_PREFIXES = {
            "dwd_", "dws_", "ads_", "ods_", "dim_", "fact_", "tbl_", "tb_", "dw_", "t_"};

    /** 表名规范化时剥掉的无信息后缀。 */
    private static final String[] TABLE_SUFFIXES = {"_info", "_tbl", "_table", "_t"};

    private JoinGraph() {
    }

    // ==================== 关系推导 ====================

    /** 只用库中外键约束推导关联（不做命名推断）。 */
    public static List<TableRelation> relations(List<TableMeta> tables) {
        return relations(tables, false, List.of());
    }

    /** 外键 + 可选命名推断。 */
    public static List<TableRelation> relations(List<TableMeta> tables, boolean infer) {
        return relations(tables, infer, List.of());
    }

    /**
     * 推导 {@code tables} 内部的全部可用关联：人工登记（{@code manual}）+ 库中外键 + 可选命名推断。
     *
     * <p>三路结果按 {@link TableRelation#pairKey()} 去重，同一对连接键被多个来源命中时
     * <b>只保留最可信的那条</b>——人工登记的口径不该被推断结果覆盖，外键也不该被推断降级。
     * 两端不全在 {@code tables} 里的关系会被丢弃（LLM 用不上，写进 Prompt 只会诱导它臆造表名）。
     *
     * @param manual 人工登记的关联（可空；表名写法与 {@code tables} 不一致时按短名容错匹配）
     */
    public static List<TableRelation> relations(List<TableMeta> tables, boolean infer, List<TableRelation> manual) {
        if (tables == null || tables.size() < 2) {
            return List.of();
        }
        Map<String, TableMeta> index = index(tables);
        Map<String, TableRelation> merged = new LinkedHashMap<>();
        // 顺序即可信度：先人工、再外键、最后推断；后来的低可信来源不会覆盖已占位的同一对键
        merge(merged, manual, index);
        merge(merged, foreignKeys(tables), index);
        if (infer) {
            merge(merged, inferRelations(tables), index);
        }
        return new ArrayList<>(merged.values());
    }

    /** 库中外键约束 → 关联（引用了本次表集合之外的表也会被保留，由 {@link #merge} 统一过滤）。 */
    private static List<TableRelation> foreignKeys(List<TableMeta> tables) {
        List<TableRelation> out = new ArrayList<>();
        for (TableMeta t : tables) {
            if (t == null || t.name() == null) {
                continue;
            }
            for (FkMeta fk : safeFks(t)) {
                if (fk == null || isBlank(fk.column()) || isBlank(fk.refTable()) || isBlank(fk.refColumn())) {
                    continue;
                }
                out.add(new TableRelation(t.name(), fk.column(), fk.refTable(), fk.refColumn(),
                        TableRelation.Source.FOREIGN_KEY));
            }
        }
        return out;
    }

    /** 过滤 + 归一 + 按可信度去重地并入 {@code merged}。 */
    private static void merge(Map<String, TableRelation> merged, List<TableRelation> incoming,
                              Map<String, TableMeta> index) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        for (TableRelation r : incoming) {
            if (r == null || isBlank(r.leftTable()) || isBlank(r.rightTable())
                    || isBlank(r.leftColumn()) || isBlank(r.rightColumn())) {
                continue;
            }
            TableMeta left = resolve(r.leftTable(), index);
            TableMeta right = resolve(r.rightTable(), index);
            if (left == null || right == null || left == right) {
                continue;   // 集合外的表 / 自引用（父子层级表）都不作为可用连接键输出
            }
            // 表名统一改用集合内的真实呈现形式（跨库时带「库.」前缀），避免 Prompt 里出现库中不存在的写法
            TableRelation norm = new TableRelation(left.name(), r.leftColumn(), right.name(), r.rightColumn(),
                    r.source() == null ? TableRelation.Source.INFERRED : r.source(), r.safeCardinality());
            String key = norm.pairKey();
            TableRelation old = merged.get(key);
            if (old == null || old.confidence() < norm.confidence()) {
                merged.put(key, norm);
            }
        }
    }

    // ==================== 命名推断（无外键库的主力来源） ====================

    /**
     * 按命名与类型约定推断关联，两条保守规则：
     * <ol>
     *   <li><b>{@code xxx_id} 指向 xxx 表</b>：列名去掉尾部 {@code id} 后能唯一命中一张表
     *       （{@code order_id → order/orders/t_order/dim_order} 皆可，表名规范化见 {@link #normTableName}），
     *       连接列取该表的 {@code id} 列或单列主键；</li>
     *   <li><b>列名恰等于另一张表的单列主键名</b>：如 {@code orders.customer_no = customer.customer_no}，
     *       要求该主键名在全库唯一对应一张表（{@code id}、{@code code} 这类到处都有的名字天然被排除）。</li>
     * </ol>
     *
     * <p>命中 0 张或 &gt;1 张表（歧义）、类型族不兼容、列本身是本表主键、自引用，一律跳过——
     * 推断宁可漏，不可错：错连造成的静默错数比漏连更难发现。
     */
    private static List<TableRelation> inferRelations(List<TableMeta> tables) {
        Map<String, List<TableMeta>> byName = nameIndex(tables);
        Map<String, List<TableMeta>> pkOwners = pkOwners(tables);
        List<TableRelation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TableMeta t : tables) {
            if (t == null || t.name() == null || out.size() >= MAX_INFERRED) {
                continue;
            }
            Set<String> ownPk = lowerSet(t.primaryKeys());
            int added = 0;
            for (ColumnMeta c : safeColumns(t)) {
                if (added >= MAX_INFERRED_PER_TABLE || out.size() >= MAX_INFERRED) {
                    break;
                }
                if (c == null || isBlank(c.name()) || ownPk.contains(lower(c.name()))) {
                    continue;   // 本表主键是"一"方，不作为连接键的持有方去推断
                }
                TableRelation r = inferByIdSuffix(t, c, byName);
                if (r == null) {
                    r = inferByPkName(t, c, pkOwners);
                }
                if (r != null && seen.add(r.pairKey())) {
                    out.add(r);
                    added++;
                }
            }
        }
        return out;
    }

    /** 规则①：{@code order_id / orderid → orders.id}。 */
    private static TableRelation inferByIdSuffix(TableMeta t, ColumnMeta c, Map<String, List<TableMeta>> byName) {
        String base = stripIdSuffix(c.name());
        if (base == null) {
            return null;
        }
        List<TableMeta> cands = byName.get(base);
        if (cands == null || cands.size() != 1) {
            return null;   // 没有同名表，或多张表规范化后同名（歧义）→ 不猜
        }
        TableMeta ref = cands.get(0);
        if (ref == t) {
            return null;
        }
        String refCol = idColumnOf(ref);
        if (refCol == null || !typeCompatible(c.type(), columnType(ref, refCol))) {
            return null;
        }
        return new TableRelation(t.name(), c.name(), ref.name(), refCol, TableRelation.Source.INFERRED);
    }

    /** 规则②：列名恰等于另一张表的单列主键名（{@code orders.customer_no = customer.customer_no}）。 */
    private static TableRelation inferByPkName(TableMeta t, ColumnMeta c, Map<String, List<TableMeta>> pkOwners) {
        List<TableMeta> owners = pkOwners.get(lower(c.name()));
        if (owners == null || owners.size() != 1) {
            return null;   // 主键名不唯一 → 无从判断指向哪张表
        }
        TableMeta ref = owners.get(0);
        if (ref == t || !typeCompatible(c.type(), columnType(ref, c.name()))) {
            return null;
        }
        return new TableRelation(t.name(), c.name(), ref.name(), c.name(), TableRelation.Source.INFERRED);
    }

    /**
     * 剥掉列名尾部的 {@code id}：{@code order_id → order}、{@code orderid → order}。
     *
     * <p>无下划线且剥完不足 3 个字符时返回 null——否则 {@code paid / uuid / valid} 这类普通列
     * 会被拆成 {@code pa / uu / va} 去撞表名，纯属噪声。
     */
    private static String stripIdSuffix(String column) {
        String c = lower(column).replace('-', '_');
        if (c.length() <= 2 || !c.endsWith("id")) {
            return null;
        }
        String base = c.substring(0, c.length() - 2);
        boolean underscored = base.endsWith("_");
        while (base.endsWith("_")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.length() < 2 || (!underscored && base.length() < 3)) {
            return null;
        }
        return base;
    }

    /** 被引用方的连接列：优先 {@code id}，其次单列主键；都没有则不推断（不拿业务列当"一"方）。 */
    private static String idColumnOf(TableMeta t) {
        for (ColumnMeta c : safeColumns(t)) {
            if (c != null && "id".equals(lower(c.name()))) {
                return c.name();
            }
        }
        List<String> pks = nullSafeList(t.primaryKeys());
        return pks.size() == 1 ? pks.get(0) : null;
    }

    /** 表名 → 表 的多键索引：为每张表登记规范化名及其单复数变体，供 {@code xxx_id} 反查。 */
    private static Map<String, List<TableMeta>> nameIndex(List<TableMeta> tables) {
        Map<String, List<TableMeta>> idx = new HashMap<>();
        for (TableMeta t : tables) {
            if (t == null || t.name() == null) {
                continue;
            }
            String raw = lower(shortName(t.name())).replace('-', '_');
            String norm = normTableName(t.name());
            Set<String> keys = nameKeys(norm);
            if (raw.length() >= 2) {
                keys.add(raw);
            }
            for (String key : keys) {
                List<TableMeta> list = idx.computeIfAbsent(key, k -> new ArrayList<>());
                if (!list.contains(t)) {
                    list.add(t);
                }
            }
        }
        return idx;
    }

    /** 规范化名的匹配键集合：原形 + 复数 + 单数（{@code order/orders}、{@code category/categories} 互认）。 */
    private static Set<String> nameKeys(String norm) {
        Set<String> keys = new LinkedHashSet<>();
        if (norm.length() >= 2) {
            keys.add(norm);
            keys.add(norm + "s");
            keys.add(norm + "es");
            String sing = singular(norm);
            if (sing != null && sing.length() >= 2) {
                keys.add(sing);
            }
        }
        return keys;
    }

    private static String singular(String s) {
        if (s.endsWith("ies") && s.length() > 4) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.endsWith("es") && s.length() > 4) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 3) {
            return s.substring(0, s.length() - 1);
        }
        return null;
    }

    /** 表名规范化：取限定名末段 → 小写 → 剥分层前缀 → 剥无信息后缀。 */
    private static String normTableName(String name) {
        String s = lower(shortName(name)).replace('-', '_');
        for (String p : TABLE_PREFIXES) {
            if (s.length() > p.length() + 1 && s.startsWith(p)) {
                s = s.substring(p.length());
                break;
            }
        }
        for (String f : TABLE_SUFFIXES) {
            if (s.length() > f.length() + 1 && s.endsWith(f)) {
                s = s.substring(0, s.length() - f.length());
                break;
            }
        }
        return s;
    }

    /** 单列主键名 → 持有该主键的表（{@code id} 与过短的名字无区分度，不登记）。 */
    private static Map<String, List<TableMeta>> pkOwners(List<TableMeta> tables) {
        Map<String, List<TableMeta>> idx = new HashMap<>();
        for (TableMeta t : tables) {
            if (t == null || t.name() == null) {
                continue;
            }
            List<String> pks = nullSafeList(t.primaryKeys());
            if (pks.size() != 1) {
                continue;   // 复合主键只匹配其中一列并不可靠，跳过
            }
            String k = lower(pks.get(0));
            if (k.length() < 3 || k.equals("id")) {
                continue;
            }
            List<TableMeta> list = idx.computeIfAbsent(k, x -> new ArrayList<>());
            if (!list.contains(t)) {
                list.add(t);
            }
        }
        return idx;
    }

    /** 类型族是否兼容：数值 / 字符 / 时间三族，任一侧类型缺失（采不到）时放行。 */
    private static boolean typeCompatible(String a, String b) {
        int fa = family(a);
        int fb = family(b);
        return fa == 0 || fb == 0 || fa == fb;
    }

    /** 0=未知 1=数值 2=字符 3=时间。 */
    private static int family(String type) {
        String t = lower(type);
        if (t.isEmpty()) {
            return 0;
        }
        if (t.contains("int") || t.contains("decimal") || t.contains("numeric") || t.contains("float")
                || t.contains("double") || t.contains("real") || t.contains("number") || t.contains("money")) {
            return 1;
        }
        if (t.contains("char") || t.contains("text") || t.contains("string") || t.contains("uuid")
                || t.contains("json") || t.contains("enum")) {
            return 2;
        }
        if (t.contains("date") || t.contains("time")) {
            return 3;
        }
        return 0;
    }

    // ==================== 产物一：关联关系文本 ====================

    /** 渲染集合内可用的连接键（只用外键来源），无任何关联时返回空串。 */
    public static String relationsText(List<TableMeta> tables) {
        return relationsText(tables, relations(tables));
    }

    /**
     * 渲染给定关联中<b>两端都落在 {@code tables} 里</b>的那些（每行一条），无可渲染项时返回空串。
     *
     * <p>{@code relations} 可以是在更大的表集合上推导出来的（如全库），这里按切片再过滤一次，
     * 因此切片渲染与关系扩散可以共用同一份推导结果。
     */
    public static String relationsText(List<TableMeta> tables, List<TableRelation> relations) {
        List<String> lines = renderLines(tables, relations);
        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    /** 关联关系明细行（见 {@link #relationsText}）。 */
    public static List<String> relationLines(List<TableMeta> tables, List<TableRelation> relations) {
        return renderLines(tables, relations);
    }

    private static List<String> renderLines(List<TableMeta> tables, List<TableRelation> relations) {
        if (tables == null || tables.isEmpty() || relations == null || relations.isEmpty()) {
            return List.of();
        }
        Map<String, TableMeta> index = index(tables);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TableRelation r : relations) {
            if (out.size() >= MAX_RELATIONS) {
                break;
            }
            if (r == null) {
                continue;
            }
            TableMeta left = resolve(r.leftTable(), index);
            TableMeta right = resolve(r.rightTable(), index);
            if (left == null || right == null || left == right || !seen.add(r.pairKey())) {
                continue;
            }
            out.add(renderLine(left, r, right));
        }
        return out;
    }

    /**
     * 单行渲染：连接键 + 基数提醒；<b>推断来源额外标注“未必准确”</b>，让 LLM 知道这条键是猜的、可以推翻。
     */
    private static String renderLine(TableMeta left, TableRelation r, TableMeta right) {
        StringBuilder sb = new StringBuilder("- ")
                .append(left.name()).append(".").append(r.leftColumn())
                .append(" = ")
                .append(right.name()).append(".").append(r.rightColumn());
        TableRelation.Cardinality card = r.safeCardinality();
        if (card == TableRelation.Cardinality.UNKNOWN && isPrimaryKey(right, r.rightColumn())) {
            // 未声明基数但连接列是对方主键 → 确定的多对一
            card = TableRelation.Cardinality.MANY_TO_ONE;
        }
        sb.append("（").append(cardinalityNote(card, left, right));
        // fan-out 提醒不能因为“来源是推断”就省掉：推断错顶多白关联一次，
        // 而一对多关联后直接求和造成的金额翻倍是“SQL 跑通、结果非空、数值错”的静默错数
        if (r.source() == TableRelation.Source.INFERRED) {
            sb.append("；本条按命名推断，未必准确，与业务不符时请忽略");
        }
        return sb.append("）").toString();
    }

    /** 基数提醒文案（不含推断标注与括号）。 */
    private static String cardinalityNote(TableRelation.Cardinality card, TableMeta left, TableMeta right) {
        return switch (card) {
            case ONE_TO_ONE -> "一对一关联，不会放大行数";
            case MANY_TO_ONE -> "多对一：" + left.name() + " 多行对应 " + right.name() + " 一行；按 "
                    + right.name() + " 统计 " + left.name() + " 的度量会因关联而重复计数";
            case MANY_TO_MANY -> "多对多：关联后两侧行数都会翻倍，汇总度量前必须先去重或先在子查询里按统计粒度聚合，"
                    + "否则数值会被严重放大";
            case UNKNOWN -> "关联键，基数未知，聚合前请核对是否会重复计数";
        };
    }

    // ==================== 产物二：关系扩散 ====================

    /** {@code seed} 的一跳关联表（只用外键来源推导关系）。 */
    public static List<TableMeta> oneHopNeighbours(TableMeta seed, List<TableMeta> all) {
        return oneHopNeighbours(seed, all, relations(all));
    }

    /**
     * {@code seed} 的一跳关联表（双向：它引用的表 + 引用它的表），从 {@code all} 里取原始对象，
     * 排除自身与重复项；结果顺序沿用 {@code relations} 的顺序，即<b>人工登记与外键的邻居排在推断之前</b>。
     *
     * @param relations 已推导好的关联（通常在全量表上算一次后复用，避免每张种子表重复推导）
     */
    public static List<TableMeta> oneHopNeighbours(TableMeta seed, List<TableMeta> all,
                                                   List<TableRelation> relations) {
        if (seed == null || all == null || all.size() < 2 || relations == null || relations.isEmpty()) {
            return List.of();
        }
        String self = lower(shortName(seed.name()));
        if (self.isEmpty()) {
            return List.of();
        }
        Map<String, TableMeta> index = index(all);
        List<TableMeta> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (TableRelation r : relations) {
            if (r == null) {
                continue;
            }
            TableMeta left = resolve(r.leftTable(), index);
            TableMeta right = resolve(r.rightTable(), index);
            if (left == null || right == null) {
                continue;
            }
            TableMeta nb;
            if (self.equals(lower(shortName(left.name())))) {
                nb = right;
            } else if (self.equals(lower(shortName(right.name())))) {
                // 入边：明细表往往才是问题真正要统计的对象，同样必须带进来
                nb = left;
            } else {
                continue;
            }
            String key = lower(shortName(nb.name()));
            if (key.equals(self) || !added.add(key)) {
                continue;
            }
            out.add(nb);
        }
        return out;
    }

    /** 表集合内是否存在任何可用关联（供调用方决定要不要输出【关联关系】段）。 */
    public static boolean hasRelations(List<TableMeta> tables) {
        return !relations(tables).isEmpty();
    }

    // ==================== 通用小工具 ====================

    private static boolean isPrimaryKey(TableMeta t, String column) {
        if (t == null || column == null) {
            return false;
        }
        String c = lower(column);
        for (String pk : nullSafeList(t.primaryKeys())) {
            if (lower(pk).equals(c)) {
                return true;
            }
        }
        return false;
    }

    private static String columnType(TableMeta t, String column) {
        String c = lower(column);
        for (ColumnMeta col : safeColumns(t)) {
            if (col != null && lower(col.name()).equals(c)) {
                return col.type();
            }
        }
        return null;
    }

    private static TableMeta resolve(String name, Map<String, TableMeta> index) {
        if (isBlank(name)) {
            return null;
        }
        TableMeta hit = index.get(lower(name));
        return hit != null ? hit : index.get(lower(shortName(name)));
    }

    /** 建表名索引（全名优先，短名兜底且不覆盖已有短名，避免跨库同名表张冠李戴）。 */
    private static Map<String, TableMeta> index(List<TableMeta> tables) {
        Map<String, TableMeta> idx = new HashMap<>();
        for (TableMeta t : tables) {
            if (t == null || t.name() == null) {
                continue;
            }
            idx.put(lower(t.name()), t);
            idx.putIfAbsent(lower(shortName(t.name())), t);
        }
        return idx;
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String v : nullSafeList(values)) {
            String s = lower(v);
            if (!s.isEmpty()) {
                set.add(s);
            }
        }
        return set;
    }

    private static List<String> nullSafeList(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static List<ColumnMeta> safeColumns(TableMeta t) {
        return t == null || t.columns() == null ? List.of() : t.columns();
    }

    private static List<FkMeta> safeFks(TableMeta t) {
        return t == null || t.foreignKeys() == null ? List.of() : t.foreignKeys();
    }

    /** 取限定名的末段：「库.表」/「库.模式.表」→「表」。 */
    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        int last = name.lastIndexOf('.');
        return last < 0 ? name : name.substring(last + 1);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
