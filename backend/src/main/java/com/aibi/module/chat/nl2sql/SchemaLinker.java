package com.aibi.module.chat.nl2sql;

import com.aibi.module.datasource.model.TableMeta;
import com.aibi.module.datasource.model.TableMeta.ColumnMeta;
import com.aibi.module.semantic.entity.SemanticMeta;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相关表裁剪（§3.6 SchemaLinker）：大库场景下，按"问题 ↔ 表名/表注释/列名/列注释"的命中度给每张表打分，
 * 只把最相关的若干张表放进 Prompt——既省 token、又降低 LLM 选错表的概率。
 *
 * <p>骨架版用子串命中近似（中文问题主要靠中文注释命中，英文靠表/列名命中）；生产版可叠加同义词典、
 * 历史频次与向量检索。表数不超过 {@link #MAX_TABLES_IN_PROMPT} 时直接全量，避免无谓裁剪丢上下文。
 *
 * <p><b>关系扩散</b>（多表问数准确率）：命中度打分只看“问题里出现了什么词”，而多表查询必需的
 * 维度表 / 桥接表往往一个词都不出现（问“各品类销售额”时 product 表很可能不命中），按分数裁剪必被丢弃。
 * 故每选中一张命中表就沿关联键补它的一跳邻居（见 {@link JoinGraph}），让 LLM 手里确实有可 join 的表。
 * 关联键有三个来源：人工登记、库中外键、命名推断（由 {@link RelationHints} 决定启用哪些）；
 * 三者都拿不到时不产生任何扩散，选表结果与升级前完全一致。
 */
@Component
public class SchemaLinker {

    /** 进入 Prompt 的最大表数；超过则按相关性裁剪。 */
    public static final int MAX_TABLES_IN_PROMPT = 12;

    /** 参与命中的最短 token 长度，过滤 "id" 之类过短噪声。 */
    private static final int MIN_TOKEN_LEN = 2;

    /** 每张命中表最多随带几张关联表：防被大量表引用的枢纽维度表把 Prompt 名额占满。 */
    private static final int MAX_NEIGHBOURS_PER_SEED = 3;

    /** 把全量表结构裁剪为与问题最相关的切片（无语义层信息、不推断关联）。 */
    public SchemaSlice link(List<TableMeta> tables, String question) {
        return link(tables, question, null, RelationHints.none());
    }

    /**
     * 把全量表结构裁剪为与问题最相关的切片（配置化升级 V2）：叠加语义层的
     * 表/列别名与同义词命中计分，提升中文业务问法下的选表选列准确率。
     *
     * @param semantic 语义视图（可空；空时无行为差异）
     */
    public SchemaSlice link(List<TableMeta> tables, String question, SemanticView semantic) {
        return link(tables, question, semantic, RelationHints.none());
    }

    /**
     * 同上，并指定关联推导策略：切片会带上这份策略，供 Prompt 渲染【关联关系】段与后续静态校验复用。
     *
     * @param hints 关联推导策略（可空，空同 {@link RelationHints#none()}）
     */
    public SchemaSlice link(List<TableMeta> tables, String question, SemanticView semantic, RelationHints hints) {
        RelationHints rh = hints == null ? RelationHints.none() : hints;
        // 关联在全量表上只推导一次：既给下面的关系扩散用，也比每张种子表各推一遍省得多
        List<TableRelation> relations = rh.relationsOf(tables);
        int total = tables.size();
        if (total <= MAX_TABLES_IN_PROMPT) {
            return slice(tables, total, false, false, rh);
        }
        String q = question == null ? "" : question.toLowerCase();
        List<Scored> scored = new ArrayList<>(total);
        boolean anyHit = false;
        for (TableMeta t : tables) {
            int s = score(t, q, semantic);
            if (s > 0) {
                anyHit = true;
            }
            scored.add(new Scored(t, s));
        }
        if (!anyHit) {
            // 无任何命中：退化为取前 N 张（标注 degraded），避免 Prompt 表结构为空；
            // 调用方（引擎）可据此回落 LLM 目录选表，避免中文问法大库场景只看错表
            return slice(new ArrayList<>(tables.subList(0, MAX_TABLES_IN_PROMPT)), total, true, true, rh);
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());

        List<TableMeta> picked = new ArrayList<>();
        Set<String> names = new HashSet<>();
        // ① 命中表按分数降序入选；每入选一张，就沿关联键把它的直接关联表一并带上（关系扩散）。
        //     维度表 / 桥接表（product、region、order_item）在问法里往往一个业务词都不出现，纯命中度打分
        //     恒为 0 分；若等命中表选完再补位，名额常常已被占满，所以必须“随命中随扩散”。
        //     一条关联都推导不出来时不产生任何扩散，选表结果与升级前逐字一致。
        for (Scored s : scored) {
            if (picked.size() >= MAX_TABLES_IN_PROMPT) {
                break;
            }
            if (s.score() > 0 && names.add(s.table().name())) {
                picked.add(s.table());
                addNeighbours(picked, names, s.table(), tables, relations);
            }
        }
        // ② 命中表不足 N 张时，用其余表补齐，尽量保留上下文
        for (Scored s : scored) {
            if (picked.size() >= MAX_TABLES_IN_PROMPT) {
                break;
            }
            if (names.add(s.table().name())) {
                picked.add(s.table());
            }
        }
        return slice(picked, total, true, false, rh);
    }

    /** 造切片：把关联推导策略一并带下去，Prompt 与静态校验才能拿到同一份连接键。 */
    private SchemaSlice slice(List<TableMeta> tables, int total, boolean truncated, boolean degraded,
                              RelationHints hints) {
        return new SchemaSlice(tables, total, truncated, degraded, hints.infer(), hints.manual());
    }

    /**
     * 沿关联键把 {@code seed} 的一跳关联表补进切片（就地修改 {@code picked} 与 {@code names}）：
     * 每张种子最多补 {@link #MAX_NEIGHBOURS_PER_SEED} 张，总数不超过 {@link #MAX_TABLES_IN_PROMPT}。
     *
     * <p>只扩散一跳：多跳会把半个库拽进来，反而稀释 Prompt 里的有效信息；一跳已能覆盖
     * 「事实表 + 维度表」与「主表 + 明细表」这两类绝大多数多表问法。
     *
     * <p>每张种子设上限是为了防“枢纽表”挤占名额：一张被几十张表引用的公共维度表一旦命中，
     * 无上限扩散会把 Prompt 塞满它的邻居，把其它命中表全挤出去。
     */
    private void addNeighbours(List<TableMeta> picked, Set<String> names, TableMeta seed,
                              List<TableMeta> tables, List<TableRelation> relations) {
        int added = 0;
        for (TableMeta nb : JoinGraph.oneHopNeighbours(seed, tables, relations)) {
            if (picked.size() >= MAX_TABLES_IN_PROMPT || added >= MAX_NEIGHBOURS_PER_SEED) {
                return;
            }
            if (names.add(nb.name())) {
                picked.add(nb);
                added++;
            }
        }
    }

    private int score(TableMeta t, String q, SemanticView semantic) {
        int s = 0;
        s += scoreTableName(q, t.name());
        if (hit(q, t.comment())) {
            s += 3;
        }
        Map<String, SemanticMeta> cols = semantic == null ? Map.of()
                : semantic.columns().getOrDefault(t.name(), Map.of());
        SemanticMeta tm = semantic == null ? null : semantic.tables().get(t.name());
        if (tm != null) {
            // 业务别名与同义词是中文问法的主要桥梁，权重不低于表注释
            if (hit(q, tm.getAliasCn())) {
                s += 3;
            }
            s += 2 * hitSynonyms(q, tm.getSynonyms());
        }
        for (ColumnMeta c : t.columns()) {
            if (hit(q, c.name())) {
                s += 1;
            }
            if (hit(q, c.comment())) {
                s += 2; // 中文列注释是中文问题命中的主要桥梁
            }
            SemanticMeta cm = cols.get(c.name());
            if (cm != null) {
                if (hit(q, cm.getAliasCn())) {
                    s += 2;
                }
                s += hitSynonyms(q, cm.getSynonyms());
            }
        }
        return s;
    }

    /**
     * 表名命中计分。跨库联合分析下额外库的表名形如「库.表」（甚至「库.模式.表」，
     * 见 {@code JdbcDataSourceAdapter#display}），整串几乎不可能原样出现在问法里，
     * 直接拿全名命中会让这些表恒为 0 分、被挤出 {@link #MAX_TABLES_IN_PROMPT} 名额，
     * 于是「配了额外库却问不到额外库的表」。故拆开计分：
     * 末段是真正的表名，按表名权重计；首段是库名，只给弱信号——用户点名某个库时
     * 该库的表应当更容易入选，但库名往往很短（如 oa、tt），权重高了会让无关表搭便车。
     */
    private int scoreTableName(String q, String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        if (hit(q, name)) {
            return 3;
        }
        int first = name.indexOf('.');
        if (first < 0) {
            return 0;   // 非限定名：全名不中即不中
        }
        int last = name.lastIndexOf('.');
        int s = 0;
        if (hit(q, name.substring(last + 1))) {
            s += 3;
        }
        if (hit(q, name.substring(0, first))) {
            s += 1;
        }
        return s;
    }

    /** 同义词串（逗号/顿号/分号分隔）中命中的个数。 */
    private int hitSynonyms(String q, String synonyms) {
        if (synonyms == null || synonyms.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String syn : synonyms.split("[,\uff0c;\uff1b\u3001/]")) {
            if (hit(q, syn)) {
                n++;
            }
        }
        return n;
    }

    private boolean hit(String q, String token) {
        if (token == null) {
            return false;
        }
        String tk = token.trim().toLowerCase();
        return tk.length() >= MIN_TOKEN_LEN && q.contains(tk);
    }

    private record Scored(TableMeta table, int score) {
    }
}
