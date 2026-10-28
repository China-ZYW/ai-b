package com.aibi.module.dashboard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指标 canonical 口径表达式归一化工具。
 *
 * <p><b>为什么需要它</b>：指标的 {@code definition} 会被趋势 / 量价因子 / 维度归因三处当"列或表达式"直接
 * 拼进 SQL，且由 {@code AttributionSqlBuilder} 再包一层聚合（{@code SUM(...)}）。历史上部分指标
 * （人工从问数结果保存、早期种子数据）把 definition 存成了<b>输出别名</b>而非真实表达式，例如把
 * {@code SELECT SUM(quantity) AS total_quantity FROM orders} 的口径存成 {@code total_quantity}。
 * 一旦如此，归因会生成 {@code SUM(total_quantity)}，而业务库里根本没有 {@code total_quantity} 列，
 * 报 {@code Unknown column 'total_quantity' in 'field list'}——指标页看着正常，一归因就炸。
 *
 * <p>归一化规则（保守、可解释）：
 * <ol>
 *   <li>definition 为空 → 原样返回（是否"未沉淀"由调用方判断）；</li>
 *   <li>definition 已是表达式（含括号 / 运算符 / 空格）→ 原样返回；</li>
 *   <li>definition 是<b>裸标识符</b>且能在 {@code sql_text} 的 SELECT 列表里找到同名输出别名
 *       → 用产生该别名的表达式替换（{@code total_quantity} → {@code SUM(quantity)}）；</li>
 *   <li>否则原样返回（裸标识符很可能本就是真实列名，如 {@code amount}）。</li>
 * </ol>
 *
 * <p>纯函数、无副作用，既可用于<b>写侧</b>（保存指标时纠正）也可用于<b>读侧</b>（历史脏数据兜底）。
 */
public final class MetricDefinitionNormalizer {

    /** 裸标识符：字母/下划线开头，仅含字母数字下划线（不含括号、运算符、空格）。 */
    private static final Pattern BARE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** {@code <expr> AS <alias>}（别名可带反引号或引号）。 */
    private static final Pattern AS_ALIAS = Pattern.compile(
            "(?is)^(.*?)\\s+AS\\s+[`\"']?([A-Za-z_][A-Za-z0-9_]*)[`\"']?$");

    /** {@code <expr> <alias>} 省略 AS 的写法（仅当 expr 含括号或运算符时才认，避免把 "a b" 误当别名）。 */
    private static final Pattern BARE_ALIAS = Pattern.compile(
            "(?is)^(.*[()*/+\\-].*?)\\s+[`\"']?([A-Za-z_][A-Za-z0-9_]*)[`\"']?$");

    private MetricDefinitionNormalizer() {
    }

    /**
     * 归一化指标口径表达式。
     *
     * @param definition 指标 definition 原值
     * @param sqlText    该指标的原始 SQL（可为空）
     * @return 可直接拼进聚合 SQL 的表达式；无法判定时返回原值
     */
    public static String canonicalize(String definition, String sqlText) {
        if (definition == null || definition.isBlank()) {
            return definition;
        }
        // 去掉 MySQL 标识符引用符（反引号）：AI 指标自动分析会写出 SUM(`amount`) 这类口径，
        // 反引号在归因 SQL 构建器里属被拦截字符，会直接把归因打成 500。
        String def = stripQuoting(definition);
        if (def.isEmpty()) {
            return definition;
        }
        if (BARE_IDENT.matcher(def).matches()) {
            String resolved = resolveAlias(def, sqlText);
            if (resolved != null) {
                String cleaned = stripQuoting(resolved);
                return cleaned.isEmpty() ? def : cleaned;
            }
        }
        return def;
    }

    /** 去除标识符引用符（MySQL 反引号）。仅去引用符，其余危险字符仍交由 SQL 构建器拦截。 */
    private static String stripQuoting(String expr) {
        return expr.replace("`", "").trim();
    }

    /** 在 sql_text 的 SELECT 列表里找出别名为 {@code alias} 的那一项，返回其表达式；找不到返回 null。 */
    private static String resolveAlias(String alias, String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            return null;
        }
        String selectList = selectListOf(sqlText);
        if (selectList == null || selectList.isBlank()) {
            return null;
        }
        for (String item : splitTopLevel(selectList)) {
            String it = item.trim();
            if (it.isEmpty()) {
                continue;
            }
            Matcher as = AS_ALIAS.matcher(it);
            if (as.matches() && alias.equalsIgnoreCase(as.group(2).trim())) {
                return as.group(1).trim();
            }
            Matcher bare = BARE_ALIAS.matcher(it);
            if (bare.matches() && alias.equalsIgnoreCase(bare.group(2).trim())) {
                return bare.group(1).trim();
            }
        }
        return null;
    }

    /** 截取 SELECT 与顶层 FROM 之间的列表文本。 */
    private static String selectListOf(String sql) {
        String lower = sql.toLowerCase();
        int select = lower.indexOf("select");
        if (select < 0) {
            return null;
        }
        int start = select + "select".length();
        // 跳过紧邻的 DISTINCT
        int depth = 0;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == 'f' || c == 'F') && isWordStart(sql, i)
                    && lower.startsWith("from", i) && isWordEnd(sql, i + 4)) {
                return sql.substring(start, i);
            }
        }
        return sql.substring(start);
    }

    private static boolean isWordStart(String s, int i) {
        return i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_';
    }

    private static boolean isWordEnd(String s, int i) {
        return i >= s.length() || (!Character.isLetterOrDigit(s.charAt(i)) && s.charAt(i) != '_');
    }

    /** 按顶层逗号切分（忽略括号内的逗号，如 {@code ROUND(SUM(a),2)}）。 */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts;
    }
}
