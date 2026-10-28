package com.aibi.module.semantic.patrol;

import com.aibi.common.sql.SqlTableExtractor;
import com.aibi.module.chat.distill.GovernanceIssue;
import com.aibi.module.chat.distill.GovernanceSignal;
import com.aibi.module.dashboard.entity.Metric;
import com.aibi.module.semantic.entity.SemanticMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 元数据巡检扫描器（T11-4 · 纯静态规则，零 Spring、零 IO）.
 *
 * <p>输入某数据源的语义行 + 指标 + 当前 schema（小写表名 → 小写列集），确定性识别三类缺陷：
 * <ul>
 *   <li>S-M1 {@code META_LINEAGE_BROKEN}：幽灵语义行（enabled 行的表/列不在 schema）、
 *       指标 SQL 引用表不在 schema（{@link SqlTableExtractor} 提取，解析失败该条跳过不报错）；</li>
 *   <li>S-M2 {@code META_SAME_NAME_DIFF_DEF}：同 (ds, aliasCn) 映射 ≥2 个不同物理列；
 *       同 (ds, name) 指标 ≥2 条且口径（definition/sqlText 归一）不同——完全相同=重复资产，不判；</li>
 *   <li>S-M3 {@code META_NO_COMMENT}：enabled COLUMN 行既无中文别名又无注释（裸列名），
 *       按表聚簇一表一条，只报裸列数最多的前 {@value #NO_COMMENT_CAP} 表。</li>
 * </ul>
 *
 * <p>normalizedKey 规范（设计稿 §4）：{@code BROKEN|ds|table[.column]}、
 * {@code DIFFDEF|ds|kind|归一名称}、{@code NOCOM|ds|table}。
 * 巡检行统一口径：skill={@value #SKILL}、occurrences=1（NO_COMMENT 为表内裸列数）、
 * sampleSql=null、hasSuccessSql=false、ledgerId=null、status="OPEN"、ref 全 null。
 *
 * <p>本类不查库不组 schema——数据供给与台账写入在 {@code MetaPatrolService}（K2）。
 */
public final class MetaPatrolScanner {

    /** 巡检行的技能标记（区别于 direct_sql/metric/fewshot/nl2sql，前端与台账可读）。 */
    public static final String SKILL = "meta_patrol";

    /** S-M3 按表聚簇后的上报上限（防海量刷屏，全量补齐走既有 alias-completion）。 */
    public static final int NO_COMMENT_CAP = 20;

    /** S-M2 建议里冲突明细的展示上限（防 detail 爆长）。 */
    private static final int DIFF_DEF_DETAIL_MAX = 3;

    private MetaPatrolScanner() {
    }

    /**
     * 扫描一个数据源的元数据缺陷。
     *
     * @param datasourceId   数据源 id（进 normalizedKey 与 jumpAnchor）
     * @param metas          该 ds 的语义元数据行（含未启用行，内部自行过滤 enabled）
     * @param metrics        该 ds 的指标列表
     * @param schemaByTable  当前 schema：小写裸表名 → 小写列名集（列集为空=拿不到列信息，只判表存在性）
     * @return 命中的治理问题（内部优先级 BROKEN &gt; DIFFDEF &gt; NO_COMMENT 分组顺序），无缺陷返回空表
     */
    public static List<GovernanceIssue> scan(Long datasourceId,
                                             List<SemanticMeta> metas,
                                             List<Metric> metrics,
                                             Map<String, Set<String>> schemaByTable) {
        List<GovernanceIssue> issues = new ArrayList<>();
        Map<String, Set<String>> schema = schemaByTable == null ? Map.of() : schemaByTable;
        // S-M1 血缘断裂（幽灵语义行 + 指标断链）
        issues.addAll(scanBrokenTables(metas, schema));
        issues.addAll(scanBrokenColumns(metas, schema));
        issues.addAll(scanBrokenMetricSql(datasourceId, metrics, schema));
        // S-M2 同名不同口径（语义层别名冲突 + 指标同名异口径）
        issues.addAll(scanAliasConflicts(datasourceId, metas));
        issues.addAll(scanMetricDefConflicts(datasourceId, metrics));
        // S-M3 注释缺失（按表聚簇 + cap）
        issues.addAll(scanNoComment(datasourceId, metas, schema));
        return issues;
    }

    // ------------------------------------------------------------------
    // S-M1 血缘断裂
    // ------------------------------------------------------------------

    /** 幽灵语义行：enabled 行（TABLE 或 COLUMN）的表不在当前 schema。 */
    static List<GovernanceIssue> scanBrokenTables(List<SemanticMeta> metas, Map<String, Set<String>> schema) {
        List<GovernanceIssue> out = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>(); // 同表只出一条（列行会重复撞车）
        for (SemanticMeta m : enabledRows(metas)) {
            String table = norm(m.getTableName());
            if (table.isEmpty() || schema.containsKey(table) || !reported.add(table)) {
                continue;
            }
            String label = isColumn(m) ? m.getTableName() + "." + m.getColumnName() : m.getTableName();
            out.add(issue(GovernanceSignal.META_LINEAGE_BROKEN,
                    "语义行「" + label + "」引用的表在数据源中已不存在",
                    "BROKEN|" + m.getDatasourceId() + "|" + table,
                    1,
                    "该表已下线或改名：到语义管理核对该行，修正表名或禁用删除",
                    anchor(m.getDatasourceId(), table, null)));
        }
        return out;
    }

    /** 幽灵语义列：enabled COLUMN 行，表在但列不在（列集为空=拿不到列信息，不判，防误杀）。 */
    static List<GovernanceIssue> scanBrokenColumns(List<SemanticMeta> metas, Map<String, Set<String>> schema) {
        List<GovernanceIssue> out = new ArrayList<>();
        for (SemanticMeta m : enabledRows(metas)) {
            if (!isColumn(m)) {
                continue;
            }
            String table = norm(m.getTableName());
            String column = norm(m.getColumnName());
            if (table.isEmpty() || column.isEmpty()) {
                continue;
            }
            Set<String> cols = schema.get(table);
            if (cols == null || cols.isEmpty() || cols.contains(column)) {
                continue;
            }
            out.add(issue(GovernanceSignal.META_LINEAGE_BROKEN,
                    "语义列「" + m.getTableName() + "." + m.getColumnName() + "」在数据源中已不存在",
                    "BROKEN|" + m.getDatasourceId() + "|" + table + "." + column,
                    1,
                    "该列已下线或改名：到语义管理核对该行，修正列名或禁用删除",
                    anchor(m.getDatasourceId(), m.getTableName(), m.getColumnName())));
        }
        return out;
    }

    /** 指标断链：sqlText 静态提取的表（裸名归一小写）不在当前 schema。提取为空/失败该条跳过。 */
    static List<GovernanceIssue> scanBrokenMetricSql(Long datasourceId, List<Metric> metrics,
                                                     Map<String, Set<String>> schema) {
        List<GovernanceIssue> out = new ArrayList<>();
        if (metrics == null) {
            return out;
        }
        Set<String> reported = new LinkedHashSet<>(); // 同名断链表只出一条
        for (Metric mt : metrics) {
            if (mt == null || mt.getSqlText() == null || mt.getSqlText().isBlank()) {
                continue;
            }
            Set<String> tables;
            try {
                tables = SqlTableExtractor.extractTables(mt.getSqlText());
            } catch (RuntimeException e) {
                continue; // 解析异常静默跳过（该工具内部已兜底，此处纯防御）
            }
            for (String raw : tables) {
                String table = SqlTableExtractor.bareName(raw);
                if (table.isEmpty() || schema.containsKey(table) || !reported.add(table)) {
                    continue;
                }
                Long dsId = mt.getDatasourceId() != null ? mt.getDatasourceId() : datasourceId;
                out.add(issue(GovernanceSignal.META_LINEAGE_BROKEN,
                        "指标「" + mt.getName() + "」的 SQL 引用表 " + table + " 在数据源中已不存在",
                        "BROKEN|" + dsId + "|" + table,
                        1,
                        "指标引用了已下线/改名的表：到指标管理修正 SQL，或确认表迁移后的新位置",
                        anchor(dsId, table, "metric")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // S-M2 同名不同口径
    // ------------------------------------------------------------------

    /** 语义层：同 (ds, aliasCn) 映射 ≥2 个不同物理列（aliasCn 非空且含汉字才参与）。 */
    static List<GovernanceIssue> scanAliasConflicts(Long datasourceId, List<SemanticMeta> metas) {
        Map<String, Set<String>> colByAlias = new LinkedHashMap<>();
        Map<String, Long> dsByAlias = new LinkedHashMap<>();
        Map<String, String> displayByAlias = new LinkedHashMap<>();
        for (SemanticMeta m : enabledRows(metas)) {
            if (!isColumn(m) || !hasChinese(m.getAliasCn())) {
                continue;
            }
            String alias = m.getAliasCn().trim().toLowerCase(Locale.ROOT);
            String col = norm(m.getTableName()) + "." + norm(m.getColumnName());
            if (col.equals(".")) {
                continue;
            }
            colByAlias.computeIfAbsent(alias, k -> new LinkedHashSet<>()).add(col);
            dsByAlias.putIfAbsent(alias, m.getDatasourceId() != null ? m.getDatasourceId() : datasourceId);
            displayByAlias.putIfAbsent(alias, m.getAliasCn().trim());
        }
        List<GovernanceIssue> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : colByAlias.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            List<String> cols = new ArrayList<>(e.getValue());
            String detail = String.join("、", cols.subList(0, Math.min(DIFF_DEF_DETAIL_MAX, cols.size())))
                    + (cols.size() > DIFF_DEF_DETAIL_MAX ? " 等 " + cols.size() + " 项" : "");
            out.add(issue(GovernanceSignal.META_SAME_NAME_DIFF_DEF,
                    "中文别名「" + displayByAlias.get(e.getKey()) + "」叫法相同但映射了多个物理列",
                    "DIFFDEF|" + dsByAlias.get(e.getKey()) + "|ALIAS|" + e.getKey(),
                    cols.size(),
                    "同一别名指向：" + detail + "。到语义管理统一叫法或拆分别名，避免取数歧义",
                    anchor(dsByAlias.get(e.getKey()), null, "alias")));
        }
        return out;
    }

    /** 指标层：同 (ds, name) ≥2 条且 definition/sqlText 归一后不同（完全相同=重复资产，不判）。 */
    static List<GovernanceIssue> scanMetricDefConflicts(Long datasourceId, List<Metric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return List.of();
        }
        Map<String, List<Metric>> byName = new LinkedHashMap<>();
        for (Metric mt : metrics) {
            if (mt == null || mt.getName() == null || mt.getName().isBlank()) {
                continue;
            }
            byName.computeIfAbsent(mt.getName().trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(mt);
        }
        List<GovernanceIssue> out = new ArrayList<>();
        for (Map.Entry<String, List<Metric>> e : byName.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            Set<String> defs = new LinkedHashSet<>();
            for (Metric mt : e.getValue()) {
                defs.add(canonicalDef(mt));
            }
            if (defs.size() < 2) {
                continue; // 口径完全相同=重复资产，不属本信号
            }
            Long dsId = e.getValue().get(0).getDatasourceId() != null
                    ? e.getValue().get(0).getDatasourceId() : datasourceId;
            out.add(issue(GovernanceSignal.META_SAME_NAME_DIFF_DEF,
                    "指标「" + e.getValue().get(0).getName() + "」存在多条同名但口径不同的定义",
                    "DIFFDEF|" + dsId + "|METRIC|" + e.getKey(),
                    e.getValue().size(),
                    "同名指标口径不一（共 " + defs.size() + " 种）：到指标管理保留一条权威口径，其余改名或下线",
                    anchor(dsId, null, "metric")));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // S-M3 注释缺失
    // ------------------------------------------------------------------

    /** 裸列行（无中文别名且无注释）按表聚簇，一表一条，只报裸列数最多的前 {@value #NO_COMMENT_CAP} 表。 */
    static List<GovernanceIssue> scanNoComment(Long datasourceId, List<SemanticMeta> metas,
                                               Map<String, Set<String>> schema) {
        Map<String, List<SemanticMeta>> bareByTable = new LinkedHashMap<>();
        Map<String, Integer> totalByTable = new LinkedHashMap<>();
        for (SemanticMeta m : enabledRows(metas)) {
            if (!isColumn(m)) {
                continue;
            }
            String table = norm(m.getTableName());
            if (table.isEmpty()) {
                continue;
            }
            totalByTable.merge(table, 1, Integer::sum);
            if (!hasChinese(m.getAliasCn()) && isBlank(m.getDescription())) {
                bareByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(m);
            }
        }
        // 按裸列数降序（同数按表名升序保证确定性），cap 截断
        List<Map.Entry<String, List<SemanticMeta>>> ranked = new ArrayList<>(bareByTable.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, List<SemanticMeta>>>comparingInt(e -> e.getValue().size())
                .thenComparing(Map.Entry::getKey)
                .reversed());
        List<GovernanceIssue> out = new ArrayList<>();
        for (Map.Entry<String, List<SemanticMeta>> e : ranked) {
            if (out.size() >= NO_COMMENT_CAP) {
                break;
            }
            String table = e.getKey();
            int bare = e.getValue().size();
            int total = totalByTable.getOrDefault(table, bare);
            Long dsId = e.getValue().get(0).getDatasourceId() != null
                    ? e.getValue().get(0).getDatasourceId() : datasourceId;
            out.add(issue(GovernanceSignal.META_NO_COMMENT,
                    "表 " + table + " 有 " + bare + "/" + total + " 列既无中文别名又无注释（Prompt 侧等同裸列名）",
                    "NOCOM|" + dsId + "|" + table,
                    bare,
                    "缺注释列（示例）：" + sampleCols(e.getValue()) + "。可在语义管理用「别名补全」批量补齐",
                    anchor(dsId, e.getValue().get(0).getTableName(), null)));
        }
        return out;
    }

    private static String sampleCols(List<SemanticMeta> rows) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(5, rows.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(rows.get(i).getColumnName());
        }
        if (rows.size() > max) {
            sb.append(" 等 ").append(rows.size()).append(" 列");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 公共小工具
    // ------------------------------------------------------------------

    /** 指标口径归一比较串：definition 优先，空则回落 sqlText；去全部空白 + 小写。 */
    private static String canonicalDef(Metric mt) {
        String base = !isBlank(mt.getDefinition()) ? mt.getDefinition() : orEmpty(mt.getSqlText());
        return base.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static List<SemanticMeta> enabledRows(List<SemanticMeta> metas) {
        if (metas == null) {
            return List.of();
        }
        List<SemanticMeta> out = new ArrayList<>();
        for (SemanticMeta m : metas) {
            if (m != null && m.getEnabled() != null && m.getEnabled() == 1) {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean isColumn(SemanticMeta m) {
        return m.getObjectType() != null && "COLUMN".equalsIgnoreCase(m.getObjectType().trim());
    }

    private static boolean hasChinese(String s) {
        return s != null && !s.isBlank() && Pattern_HANYU.matcher(s).find();
    }

    private static final java.util.regex.Pattern Pattern_HANYU =
            java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]");

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 裸名归一：去首尾空白、去反引号/引号/方括号、小写（与 SqlTableExtractor.bareName 同口径但不截点分）。 */
    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replace("`", "").replace("\"", "").replace("[", "").replace("]", "")
                .toLowerCase(Locale.ROOT);
    }

    /** 统一构造巡检行（设计稿 §5.1 口径：skill=meta_patrol、无 SQL 样本、OPEN、未转写）。 */
    private static GovernanceIssue issue(GovernanceSignal signal, String question, String normalizedKey,
                                         int occurrences, String suggestedAction, Map<String, Object> jumpAnchor) {
        return new GovernanceIssue(signal, question, normalizedKey, occurrences, SKILL,
                null, suggestedAction, null, false, jumpAnchor,
                null, "OPEN", null, null);
    }

    private static Map<String, Object> anchor(Long datasourceId, String table, String kind) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (datasourceId != null) {
            a.put("datasourceId", datasourceId);
        }
        if (table != null && !table.isBlank()) {
            a.put("table", table);
        }
        if (kind != null) {
            a.put("kind", kind);
        }
        return java.util.Collections.unmodifiableMap(a);
    }
}
