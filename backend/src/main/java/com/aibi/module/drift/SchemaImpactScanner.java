package com.aibi.module.drift;

import com.aibi.common.sql.SqlTableExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 变更影响反查器（T12-3 指标变更感知，纯函数、可单测）。
 *
 * <p>输入删除类变更事件（{@link SchemaChangeDiff#drops}）与资产文本清单（saved_query 的
 * sql_template+spec、metric 的 sql_text+chart_spec 拼接文本），输出被打断的资产与明细。
 *
 * <p><b>匹配口径（确定性、宁缺勿滥）</b>：
 * <ul>
 *   <li>表级：{@link SqlTableExtractor} 抽 SQL 真实表引用（与 DataMaskGate/血缘同口径，
 *       不被 FROM 关键字出现在字符串里的假象骗过），裸名小写等于被删表 → 命中；</li>
 *   <li>列级：仅当该 SQL <b>引用了删除列所在的表</b>时，才对列做标识符 token 匹配
 *       （前后不接字母数字下划线$，大小写不敏感）——避免其他表同名列跨表误报；
 *       命中的列若所在表整表被删则不再重复报列（表级已覆盖）；</li>
 *   <li>匹配前列级文本统一掩码：单引号字符串字面量与注释（'…'/--/#/块注释）替换等长空白，
 *       防止文案、注释里的同名串造成误报；反引号/双引号不掩——MySQL 反引号是标识符引号，
 *       builderSpec JSON 双引号内正是待匹配的表/列名；表级匹配用原文，
 *       {@link SqlTableExtractor} 内部已自行剥字面量。</li>
 * </ul>
 */
public final class SchemaImpactScanner {

    /** 待反查资产。type 取 SAVED_QUERY / METRIC；ownerUid 用于站内信定向（可空=不推送）。 */
    public record Asset(String type, Long id, String name, Long ownerUid, String text) {
    }

    /** 一条影响：资产 + 打断明细（"表 x 已被删除" / "表 x 的列 y 已被删除"）。 */
    public record Impact(Asset asset, List<String> brokenRefs) {
    }

    private SchemaImpactScanner() {
    }

    /**
     * @param drops  删除类变更事件（表/列名为小写归一名）
     * @param assets 资产清单（text 可空跳过）
     * @return 受影响资产（保持入参序；无命中返回空表）
     */
    public static List<Impact> scan(List<SchemaChangeDiff.Change> drops, List<Asset> assets) {
        List<Impact> out = new ArrayList<>();
        if (drops == null || drops.isEmpty() || assets == null || assets.isEmpty()) {
            return out;
        }
        Set<String> droppedTables = new LinkedHashSet<>();
        Map<String, Set<String>> droppedColsByTable = new HashMap<>();
        for (SchemaChangeDiff.Change c : drops) {
            if (c.kind() == SchemaChangeDiff.Kind.TABLE_DROPPED) {
                droppedTables.add(c.table());
            } else if (c.kind() == SchemaChangeDiff.Kind.COLUMN_DROPPED) {
                droppedColsByTable.computeIfAbsent(c.table(), k -> new LinkedHashSet<>()).add(c.column());
            }
        }
        for (Asset a : assets) {
            if (a == null || a.text() == null || a.text().isBlank()) {
                continue;
            }
            List<String> refs = new ArrayList<>();
            String masked = maskLiteralsAndComments(a.text());
            Set<String> tables = referencedTables(a.text());
            for (String t : tables) {
                if (droppedTables.contains(t)) {
                    refs.add("表 " + t + " 已被删除");
                }
            }
            for (Map.Entry<String, Set<String>> e : droppedColsByTable.entrySet()) {
                String table = e.getKey();
                if (droppedTables.contains(table) || !tables.contains(table)) {
                    continue; // 整表已删不重复报列；SQL 未引用该表则其同名列不作数
                }
                for (String col : e.getValue()) {
                    if (containsIdentifier(masked, col)) {
                        refs.add("表 " + table + " 的列 " + col + " 已被删除");
                    }
                }
            }
            if (!refs.isEmpty()) {
                out.add(new Impact(a, List.copyOf(refs)));
            }
        }
        return out;
    }

    /** SQL 引用的裸表名集合（小写）；解析失败退化为空集（表级漏报可接受，不乱报）。 */
    private static Set<String> referencedTables(String sql) {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (String raw : SqlTableExtractor.extractTables(sql)) {
                String bare = SqlTableExtractor.bareName(raw);
                if (bare != null && !bare.isEmpty()) {
                    out.add(bare.toLowerCase(Locale.ROOT));
                }
            }
        } catch (RuntimeException ignored) {
            // 解析不了=不判表级（列级 token 匹配仍照常）
        }
        return out;
    }

    /** 标识符 token 匹配：前后不接 [A-Za-z0-9_$]，大小写不敏感（兼容反引号/中文列名）。 */
    private static boolean containsIdentifier(String text, String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        String quoted = Pattern.quote(identifier);
        return Pattern.compile("(?<![A-Za-z0-9_$])" + quoted + "(?![A-Za-z0-9_$])",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /**
     * 单引号字符串字面量与注释替换为等长空白（保留长度与下标）：'…'（'' 与 \ 转义视为串内）、
     * -- 行注释、# 行注释、/* … *&#47; 块注释。反引号/双引号刻意不掩（见类注释）。
     */
    static String maskLiteralsAndComments(String s) {
        char[] c = s.toCharArray();
        int n = c.length;
        int i = 0;
        while (i < n) {
            char ch = c[i];
            if (ch == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (c[j] == '\\') {
                        j += 2;
                        continue;
                    }
                    if (c[j] == '\'') {
                        if (j + 1 < n && c[j + 1] == '\'') {
                            j += 2; // 双写引号=串内转义
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                int end = Math.min(j, n - 1);
                for (int k = i; k <= end; k++) {
                    if (c[k] != '\n') {
                        c[k] = ' ';
                    }
                }
                i = end + 1;
            } else if (ch == '-' && i + 1 < n && c[i + 1] == '-') {
                int j = i;
                while (j < n && c[j] != '\n') {
                    c[j] = ' ';
                    j++;
                }
                i = j;
            } else if (ch == '#') {
                int j = i;
                while (j < n && c[j] != '\n') {
                    c[j] = ' ';
                    j++;
                }
                i = j;
            } else if (ch == '/' && i + 1 < n && c[i + 1] == '*') {
                int j = i + 2;
                while (j + 1 < n && !(c[j] == '*' && c[j + 1] == '/')) {
                    j++;
                }
                int end = Math.min(j + 1, n - 1);
                for (int k = i; k <= end; k++) {
                    if (c[k] != '\n') {
                        c[k] = ' ';
                    }
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return new String(c);
    }
}
