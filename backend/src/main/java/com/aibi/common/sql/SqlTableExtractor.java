package com.aibi.common.sql;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 表名抽取公共工具（T10-3 自 {@code DataMaskGate} 原样下沉，逻辑逐字节等价）。
 *
 * <p>此前这套"JSqlParser 为主 + 正则并集兜底"的抽取只服务表级授权拦截（DAC）；溯源回传
 * （{@code LineageExtractor}）需要同一份"这条 SQL 动了哪些表"的答案。两处各留一份必然漂移，
 * 而授权与溯源对表集合的一致性恰恰是"可审计"的根基，故下沉为公共静态工具、DAC 改为委派调用。
 *
 * <p><b>行为契约（不可变）</b>：解析成功时"JSqlParser 结果 ∪ 正则结果"（正则补漏非标准写法）；
 * 解析抛异常时只信正则（覆盖中文未引号表名——JSqlParser 4.6 不接受未加引号的非 ASCII 标识符）。
 * 返回值保留抽取原文（可能带反引号/库前缀），比较请用 {@link #bareName} 归一化后对裸名。
 */
public final class SqlTableExtractor {

    private SqlTableExtractor() {
    }

    /** 表名正则抽取兜底（兼容中文/带库前缀/反引号）：先剥离字符串字面量与注释，再抓 FROM/JOIN/INTO/UPDATE/USING 后的表名。 */
    // ★ 字符类里 ] 必须紧跟 [ 写成 [\]]（或 [\]\[]）；写成 [\]?[ 会让前一个 [ 永远不闭合，
    //   Pattern.compile 抛 PatternSyntaxException → 类初始化失败 → 整个 dataMaskGate bean 起不来（启动即崩）。
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)(?:FROM|JOIN|INTO|UPDATE|USING)\\s+([`\"]?[\\]\\[]?[\\w\\u4e00-\\u9fa5$#@]+"
                    + "(?:\\.[`\"]?[\\]\\[]?[\\w\\u4e00-\\u9fa5$#@]+)?"
                    + "(?:\\.[`\"]?[\\]\\[]?[\\w\\u4e00-\\u9fa5$#@]+)?)");

    /** 抽取 SQL 涉及的表名：JSqlParser 为主（结构准确，覆盖子查询/括号 JOIN），正则兜底（含中文未加引号场景）。 */
    public static Set<String> extractTables(String sql) {
        Set<String> tables = new HashSet<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder finder = new TablesNamesFinder();
            for (String t : finder.getTableList(stmt)) {
                tables.add(t);
            }
        } catch (Exception e) {
            // JSqlParser 4.6 不接受未加引号的非 ASCII 标识符，中文表名会解析失败 → 走正则兜底
            tables.addAll(extractTablesByRegex(sql));
        }
        // 始终补一遍正则，覆盖 JSqlParser 可能漏掉的非标准写法
        tables.addAll(extractTablesByRegex(sql));
        return tables;
    }

    private static Set<String> extractTablesByRegex(String sql) {
        Set<String> tables = new HashSet<>();
        if (sql == null) {
            return tables;
        }
        // 剥离字符串字面量与注释，避免误匹配到字面量内的 FROM/JOIN 等词
        String cleaned = sql.replaceAll("'[^']*'", "''")
                .replaceAll("\"[^\"]*\"", "\"\"")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?s)--[^\\n]*", " ");
        Matcher m = TABLE_REF.matcher(cleaned);
        while (m.find()) {
            tables.add(m.group(1));
        }
        return tables;
    }

    /** 归一化为可比较的裸表名：去反引号/双引号/方括号、转小写、取最后一个点之后的段。 */
    public static String bareName(String t) {
        if (t == null) {
            return "";
        }
        String s = t.trim().replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        int dot = s.lastIndexOf('.');
        return (dot >= 0 ? s.substring(dot + 1) : s).toLowerCase(Locale.ROOT);
    }
}
