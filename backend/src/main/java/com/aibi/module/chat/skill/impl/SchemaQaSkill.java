package com.aibi.module.chat.skill.impl;

import com.aibi.module.chat.nl2sql.SchemaProvider;
import com.aibi.module.chat.skill.AskContext;
import com.aibi.module.chat.skill.AskSkill;
import com.aibi.module.chat.skill.QuestionMatcher;
import com.aibi.module.chat.skill.SkillPlan;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.datasource.adapter.DataSourceAdapter;
import com.aibi.module.datasource.adapter.DataSourceAdapterRegistry;
import com.aibi.module.datasource.model.QueryResult;
import com.aibi.module.datasource.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 数据字典问答技能（Schema QA）：回答"这个数据源里有什么""某张表有哪些字段""某个字段是什么意思"。
 *
 * <p><b>为什么单列一个技能</b>：这是标准 ChatBI 的基础能力（ThoughtSpot 的 "what data do I have"、
 * Power BI Copilot 的元数据问答），但很多 NL2SQL 系统把它混在生成链路里——用户问"有哪些表"，
 * 系统却硬要去生成一条 SQL 与之对应，结果要么输出 UNKNOWN，要么对着 information_schema 拼一条方言相关的
 * 查询。这些都是浪费：答案本来就在<b>已经缓存好的 schema 元数据</b>里，查字典不需要查数据。
 *
 * <p><b>价值</b>：① 免 LLM、免配额、零延迟；② 答案是确定性的，不会两次问出两个结果；
 * ③ 它是"降低用户提问门槛"的关键——新用户先问系统有什么，才会问得出正确的问题。
 *
 * <p><b>三类问答</b>：
 * <ol>
 *   <li>表清单：提问未指定具体表（"这个数据源有哪些表"）；</li>
 *   <li>字段清单：提问点名某张表（"orders 表有哪些字段"）；</li>
 *   <li>字段释义：提问在问某个字段/业务词的含义（"gmv 是什么意思"），按列名与列注释检索。</li>
 * </ol>
 *
 * <p><b>命中保守性</b>：只有出现明确的元数据用词才判定命中，且表/字段的提取都基于<b>真实存在的元数据</b>
 * （把提问里出现的最长表名作为关注表，而不是让文本里的随便一个词去匹配）。判定失败即交兜底技能，
 * 绝不用错误的表结构冒充答案。
 *
 * <p><b>成本护栏</b>：只有当关键词先命中时才会去取 schema；而 schema 本身有 {@code SchemaCache} 缓存，
 * 所以普通问数完全不受本技能影响，命中后的重复问答也几乎零成本。
 */
@Component
public class SchemaQaSkill implements AskSkill {

    private static final Logger log = LoggerFactory.getLogger(SchemaQaSkill.class);

    public static final String CODE = "schema_qa";

    /** 元数据类提问标志词（归一化后匹配）。 */
    private static final String[] META_HINTS = {
            "有哪些表", "有多少张表", "有什么表", "包含哪些表", "表清单", "表列表", "数据源里有什么",
            "有哪些字段", "有什么字段", "字段清单", "字段列表", "有哪些列", "列清单", "表结构", "表字段",
            "数据字典", "字段说明", "元数据", "包含什么数据"
    };

    /** 释义类提问标志词：问某个词是什么意思。 */
    private static final String[] GLOSSARY_HINTS = {
            "是什么意思", "什么含义", "什么意思", "指代什么", "口径", "字段含义", "代表什么", "怎么定义的"
    };

    /** 仅问"表清单"的标志词（用于区分"列出所有表"与"列出某表的字段"）。 */
    private static final String[] TABLE_LIST_HINTS = {
            "有哪些表", "有多少张表", "有什么表", "包含哪些表", "表清单", "表列表", "数据源里有什么"
    };

    private final DataSourceAdapterRegistry adapterRegistry;
    private final SchemaProvider schemaProvider;
    private final AibiConfig aibiConfig;

    public SchemaQaSkill(DataSourceAdapterRegistry adapterRegistry,
                         SchemaProvider schemaProvider,
                         AibiConfig aibiConfig) {
        this.adapterRegistry = adapterRegistry;
        this.schemaProvider = schemaProvider;
        this.aibiConfig = aibiConfig;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return "数据字典";
    }

    @Override
    public String description() {
        return "回答数据源有哪些表、某表有哪些字段、某字段是什么含义，直接读元数据，免大模型";
    }

    /** 排在确定性技能之后、兜底 NL2SQL 之前：这类提问与数值问法互斥，先判定不影响正常问数性能。 */
    @Override
    public int order() {
        return 35;
    }

    @Override
    public boolean enabled() {
        return aibiConfig.askSkillEnabled(CODE, true);
    }

    @Override
    public boolean llmFree() {
        return true;
    }

    @Override
    public SkillPlan plan(AskContext ctx) {
        String q = QuestionMatcher.normalize(ctx.question());
        if (q.isEmpty() || (!containsAny(q, META_HINTS) && !containsAny(q, GLOSSARY_HINTS))) {
            return SkillPlan.NO;
        }
        List<TableMeta> tables;
        try {
            DataSourceAdapter adapter = adapterRegistry.get(ctx.config().type());
            tables = schemaProvider.getTables(adapter, ctx.config());
        } catch (RuntimeException e) {
            // 扫不动 schema 不等于答不了这个问题，但本技能无米下锅——交兜底技能，不让异常冒泡影响路由
            log.debug("数据字典技能取表结构失败，视为未命中：{}", e.getMessage());
            return SkillPlan.NO;
        }
        if (tables == null || tables.isEmpty()) {
            return SkillPlan.NO;
        }

        TableMeta focused = focusTable(q, tables);
        if (focused != null) {
            return SkillPlan.of(90, "回答「" + focused.name() + "」的表结构", () -> columnRows(focused));
        }
        if (containsAny(q, GLOSSARY_HINTS) && !containsAny(q, TABLE_LIST_HINTS)) {
            List<List<Object>> gloss = glossaryRows(q, tables);
            if (!gloss.isEmpty()) {
                return SkillPlan.of(85, "解释字段/业务词含义",
                        () -> QueryResult.of(null, List.of("字段", "所属表", "类型", "说明"), gloss, gloss.size(), 0L));
            }
        }
        if (containsAny(q, TABLE_LIST_HINTS) || containsAny(q, META_HINTS)) {
            return SkillPlan.of(90, "回答数据源表清单", () -> tableRows(tables));
        }
        return SkillPlan.NO;
    }

    /** 提问点名的表：取提问中出现的最长表名（避免短名误命中，如 order 与 order_item）。 */
    private TableMeta focusTable(String q, List<TableMeta> tables) {
        TableMeta best = null;
        int bestLen = -1;
        for (TableMeta t : tables) {
            String n = QuestionMatcher.normalize(t.name());
            if (n.length() >= 2 && q.contains(n) && n.length() > bestLen) {
                best = t;
                bestLen = n.length();
            }
        }
        return best;
    }

    /** 表清单结果。 */
    private QueryResult tableRows(List<TableMeta> tables) {
        List<List<Object>> rows = new ArrayList<>();
        for (TableMeta t : tables) {
            rows.add(List.<Object>of(t.name(), nvl(t.comment()),
                    t.columns() == null ? 0 : t.columns().size()));
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get(0))));
        return QueryResult.of(null, List.of("表名", "说明", "字段数"), rows, rows.size(), 0L);
    }

    /** 字段清单结果（点名某张表时）。 */
    private QueryResult columnRows(TableMeta table) {
        List<List<Object>> rows = new ArrayList<>();
        if (table.columns() != null) {
            for (TableMeta.ColumnMeta c : table.columns()) {
                rows.add(List.<Object>of(c.name(), nvl(c.type()), nvl(c.comment())));
            }
        }
        return QueryResult.of(null, List.of("字段名", "类型", "说明"), rows, rows.size(), 0L);
    }

    /** 字段释义检索：按提问中的核心词匹配列名或列注释。 */
    private List<List<Object>> glossaryRows(String q, List<TableMeta> tables) {
        String term = termOf(q);
        if (term.length() < 2) {
            return List.of();
        }
        List<List<Object>> rows = new ArrayList<>();
        for (TableMeta t : tables) {
            if (t.columns() == null) {
                continue;
            }
            for (TableMeta.ColumnMeta c : t.columns()) {
                boolean hit = QuestionMatcher.normalize(c.name()).contains(term)
                        || (c.comment() != null && QuestionMatcher.normalize(c.comment()).contains(term))
                        || QuestionMatcher.normalize(t.name()).contains(term);
                if (hit) {
                    rows.add(List.<Object>of(c.name(), t.name(), nvl(c.type()), nvl(c.comment())));
                }
            }
        }
        return rows.size() > 20 ? rows.subList(0, 20) : rows;
    }

    /** 从"xxx 是什么意思"里剥出被解释的核心词。 */
    private String termOf(String q) {
        String term = q;
        for (String hint : GLOSSARY_HINTS) {
            term = term.replace(hint, "");
        }
        return QuestionMatcher.normalize(term).toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String q, String[] hints) {
        for (String h : hints) {
            if (q.contains(h)) {
                return true;
            }
        }
        return false;
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "" : s;
    }
}
