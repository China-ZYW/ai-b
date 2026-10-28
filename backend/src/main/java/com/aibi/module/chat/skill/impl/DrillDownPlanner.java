package com.aibi.module.chat.skill.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多轮下钻构造器（T12-2）：把"那按省份呢"这类追问落成<b>确定性的下钻 SQL</b>——
 * 复用上轮已执行 SQL 的全部口径（表/过滤/聚合/排序/行数上限），仅追加一个新分组维度。
 *
 * <p><b>与追问改写的分工</b>：{@code QuestionRewriter} 把追问补全成独立问句后重新走生成链路，
 * 生成的 SQL 与上轮是否同口径全看模型发挥；本构造器不重新生成，只做"追加维度"这一处手术，
 * 口径继承是构造性的（逐字节保留上轮 SQL 其余部分），这正是"多轮下钻"区别于"多轮重写"的价值。
 *
 * <p><b>宁缺勿滥</b>：仅承接同时满足以下形态的下钻，任一不满足即弃权（返回空），由现有管线兜底：
 * <ul>
 *   <li>问句恰含一处"按W / 换到W / 细分到W / 拆到W / 分组到W"，剥掉助词后维度词 1~8 字；</li>
 *   <li>W 在候选列中<b>唯一</b>命中（大小写不敏感等值优先，其次包含），歧义/未命中不猜；</li>
 *   <li>上轮 SQL 为单层单表 GROUP BY 查询（无 UNION / 子 SELECT / WITH / JOIN，忽略字符串与注释）；</li>
 *   <li>W 尚未出现在上轮 SELECT 列表或 GROUP BY 段（那是重复提问，不是下钻）。</li>
 * </ul>
 *
 * <p><b>为什么不借 JSqlParser 重渲染</b>：本仓解析器面向校验时把非 ASCII 标识符折叠为占位符
 * （见 {@code SqlValidator}），AST 重渲染会毁掉中文列名与各库专有语法；故手术基于一个忽略
 * 字符串字面量/注释/括号深度的轻量扫描器做子句定位，产物 SQL 仍会经过既有只读校验链路。
 */
public final class DrillDownPlanner {

    /** 下钻维度诉求："按/按照/依/换成/换到/细分到/拆到/分组到" + 维度词（汉字、字母、数字、下划线）。 */
    private static final Pattern DIM_ASK =
            Pattern.compile("(?:按照|按|依|换成|换到|细分到|拆到|分组到)\\s*([\\p{IsHan}A-Za-z0-9_]{1,20})");

    /** 维度词尾部粘附的助词（贪婪捕获后逐个剥离，如"省份维度呢"→"省份"）。 */
    private static final String[] TRAILING_PARTICLES = {
            "维度", "字段", "方向", "口径", "来", "看", "统计", "分组", "拆分", "细分", "一下", "呢", "吧", "吗"
    };

    /** 参与"唯一命中"判定的最短维度词长度：单字维度词歧义面太大，放弃。 */
    private static final int MIN_DIM_LENGTH = 2;

    /** 下钻追问的长度上限：超过视为完整问句，交回常规链路。 */
    private static final int MAX_QUESTION_LENGTH = 40;

    /** 深度 0 的关键字（忽略大小写）；字符串字面量与注释内的出现不参与判定。 */
    private static final Pattern KW_GROUP_BY = Pattern.compile("(?i)\\bGROUP\\s+BY\\b");
    private static final Pattern KW_SELECT = Pattern.compile("(?i)\\bSELECT\\b");
    private static final Pattern KW_FROM = Pattern.compile("(?i)\\bFROM\\b");
    private static final Pattern KW_UNION = Pattern.compile("(?i)\\bUNION\\b");
    private static final Pattern KW_JOIN = Pattern.compile("(?i)\\b(JOIN|INNER|LEFT|RIGHT|CROSS)\\b");

    private DrillDownPlanner() {
    }

    /** 一次成功下钻的构造产物。 */
    public record Plan(String dimension, String sql) {
    }
    
    /**
     * 候选维度列：真实列名 + 可读标签（语义层别名/列注释，可空）。
     * 问句里的维度词先对标签匹配（"省份"→province），让英文列库也能接住中文问法；
     * 无别名时退化为纯列名匹配。
     */
    public record Dim(String column, String label) {
    }
    
    /** 便捷入口：列名自身兼作标签（无语义层别名时的退化形态）。 */
    public static Optional<Plan> plan(String question, String lastSql, List<String> columns) {
        if (columns == null) {
            return Optional.empty();
        }
        List<Dim> dims = new ArrayList<>(columns.size());
        for (String c : columns) {
            dims.add(new Dim(c, c));
        }
        return planWithDims(question, lastSql, dims);
    }
    
    /**
     * 尝试构造下钻 SQL（主入口，带语义层标签的候选列）。
     *
     * @param question 本轮问句（追问改写后的 effective 亦可：含"按W"即可判定）
     * @param lastSql  上一轮成功执行的 SQL（会话最近 OK 轮）
     * @param dims     上轮 SQL 主表的候选维度列（列名+语义层标签，技能侧从元数据供给）
     * @return 构造成功返回新 SQL；任一形态条件不满足返回空（弃权，绝不抛错）
     */
    public static Optional<Plan> planWithDims(String question, String lastSql, List<Dim> dims) {
        if (question == null || lastSql == null || dims == null || dims.isEmpty()) {
            return Optional.empty();
        }
        String q = question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION_LENGTH) {
            return Optional.empty();
        }
        // ① 维度词：恰一处"按W"类表达，剥助词后 2~8 字
        Optional<String> extracted = extractDimension(q);
        if (extracted.isEmpty()) {
            return Optional.empty();
        }
        String dim = extracted.get();
        // ② 列匹配：唯一命中才继续（标签/列名等值优先，其次包含式，多命中=歧义）
        Optional<String> column = matchColumn(dim, dims);
        if (column.isEmpty()) {
            return Optional.empty();
        }
        String col = column.get();
        // ③ 上轮 SQL 形态：单层单表 GROUP BY，深度 0 关键字计数受控
        String sql = stripTrailingSemicolon(lastSql.trim());
        Shape shape = inspectShape(sql);
        if (shape == null) {
            return Optional.empty();
        }
        // ④ 已是分组维度/已在选择列表：重复提问而非下钻
        String selectedGrouped = sql.substring(0, shape.fromIndex) + sql.substring(shape.groupByIndex, sql.length());
        if (containsIgnoreCase(selectedGrouped, col)) {
            return Optional.empty();
        }
        // ⑤ 手术：GROUP BY 段尾与 SELECT 列表尾各追加维度列；其余部分逐字节保留
        // SELECT 插入点回跳到 FROM 前空白之前（"total FROM" → "total, province FROM" 而非 "total , provinceFROM"）
        int selIns = shape.fromIndex;
        while (selIns > 0 && Character.isWhitespace(sql.charAt(selIns - 1))) {
            selIns--;
        }
        String s1 = insertAt(sql, shape.groupByEnd, ", " + col);
        String s2 = insertAt(s1, selIns, ", " + col);
        return Optional.of(new Plan(col, s2));
    }

    // ---------- 形态探测 ----------

    /**
     * 廉价预探测：问句是否含唯一一处可剥净的"按W"下钻表达（维度词 2~8 字）。
     * 技能侧用它在碰元数据缓存前快速排除绝大多数非下钻问句（契约：plan 判定必须廉价）。
     */
    public static Optional<String> extractDimension(String question) {
        if (question == null) {
            return Optional.empty();
        }
        String q = question.trim();
        if (q.isEmpty() || q.length() > MAX_QUESTION_LENGTH) {
            return Optional.empty();
        }
        Matcher m = DIM_ASK.matcher(q);
        List<String> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(m.group(1));
        }
        if (hits.size() != 1) {
            return Optional.empty();
        }
        String dim = stripParticles(hits.get(0));
        return dim.length() >= MIN_DIM_LENGTH && dim.length() <= 8
                ? Optional.of(dim) : Optional.empty();
    }

    /** 深度 0 子句位置快照（探测失败返回 null）。 */
    private record Shape(int fromIndex, int groupByIndex, int groupByEnd) {
    }

    /**
     * 用忽略字符串/注释/括号深度的扫描序列探测深度 0 关键字，校验"单层单表 GROUP BY"形态。
     * 不满足（WITH 开头 / 多 SELECT / 多 FROM / JOIN / UNION / 无 GROUP BY / 括号不闭合）返回 null。
     */
    private static Shape inspectShape(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select")) {
            return null;
        }
        // 归一化掩码：字符串字面量与注释内容替换为空格，避免其间的 from/select 干扰判定
        String masked = maskLiteralsAndComments(sql);
        int selectCount = countAtDepth0(masked, KW_SELECT);
        int fromCount = countAtDepth0(masked, KW_FROM);
        if (selectCount != 1 || fromCount != 1) {
            return null;
        }
        if (countAtDepth0(masked, KW_UNION) > 0 || countAtDepth0(masked, KW_JOIN) > 0) {
            return null;
        }
        int gbStart = indexOfAtDepth0(masked, KW_GROUP_BY);
        if (gbStart < 0) {
            return null;
        }
        Matcher gb = KW_GROUP_BY.matcher(masked);
        if (!gb.find(gbStart)) {
            return null;
        }
        int exprEnd = nextClauseBoundary(masked, gb.end());
        // GROUP BY 段尾取表达式实际末端（去掉尾部空白/分号残留）
        int realEnd = exprEnd;
        while (realEnd > gb.end() && (Character.isWhitespace(sql.charAt(realEnd - 1)) || sql.charAt(realEnd - 1) == ';')) {
            realEnd--;
        }
        int fromIdx = indexOfAtDepth0(masked, KW_FROM);
        if (fromIdx <= 0 || fromIdx > gbStart) {
            return null;
        }
        // FROM 紧跟左括号 = 派生表（追加的维度列不在其输出列里，造出来必错）：弃权
        int t = fromIdx + 4;
        while (t < masked.length() && Character.isWhitespace(masked.charAt(t))) {
            t++;
        }
        if (t < masked.length() && masked.charAt(t) == '(') {
            return null;
        }
        // FROM..GROUP BY 深度 0 逗号 = 隐式多表（逗号连接）：维度列归属歧义，弃权
        int d = 0;
        for (int i = fromIdx; i < gbStart; i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                d++;
            } else if (c == ')') {
                d--;
            } else if (d == 0 && c == ',') {
                return null;
            }
        }
        return new Shape(fromIdx, gbStart, realEnd);
    }

    /** GROUP BY 表达式之后的下一个深度 0 子句起点（HAVING/ORDER BY/LIMIT/OFFSET/FETCH），没有则到句尾。 */
    private static int nextClauseBoundary(String masked, int from) {
        Pattern p = Pattern.compile("(?i)\\b(?:HAVING|ORDER\\s+BY|LIMIT|OFFSET|FETCH)\\b");
        Matcher m = p.matcher(masked);
        int depth = 0;
        for (int i = from; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return i;   // 外层子句包裹括号收尾（段在子查询内的情形已由单层的形检查排除）
                }
                depth--;
            } else if (depth == 0 && m.find(i) && m.start() == i) {
                return i;
            }
        }
        return masked.length();
    }

    // ---------- 轻量扫描工具 ----------

    /** 把字符串字面量（含 '' 与 \ 转义）与注释（-- / # / &#42;&#42;/）替换为等长空白，长度与原串一致。 */
    private static String maskLiteralsAndComments(String sql) {
        char[] out = sql.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n) {
                    if (out[j] == '\\' && c != '`') {
                        out[j] = ' ';
                        if (j + 1 < n) {
                            out[j + 1] = ' ';
                        }
                        j += 2;
                        continue;
                    }
                    if (out[j] == c) {
                        if (j + 1 < n && out[j + 1] == c) {   // '' 双写转义
                            out[j] = ' ';
                            out[j + 1] = ' ';
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    out[j] = ' ';
                    j++;
                }
                out[i] = ' ';
                if (j < n) {
                    out[j] = ' ';
                }
                i = j + 1;
            } else if ((c == '-' && i + 1 < n && out[i + 1] == '-') || c == '#') {
                int j = i;
                while (j < n && out[j] != '\n') {
                    out[j] = ' ';
                    j++;
                }
                i = j;
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                int j = i;
                int end = sql.indexOf("*/", j + 2);
                int stop = end < 0 ? n : end + 2;
                while (j < stop) {
                    if (out[j] != '\n') {
                        out[j] = ' ';
                    }
                    j++;
                }
                i = stop;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** 深度 0（不在括号内）的关键字出现次数。掩码串中括号仍在，字符串/注释已置空。 */
    private static int countAtDepth0(String masked, Pattern keyword) {
        int count = 0;
        int depth = 0;
        Matcher m = keyword.matcher(masked);
        int i = 0;
        while (i < masked.length()) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && m.find(i) && m.start() == i) {
                count++;
                i = m.end();
                continue;
            }
            i++;
        }
        // 括号不闭合：形态可疑，判不可用（返回 -1 由调用侧拒绝）
        return depth == 0 ? count : -1;
    }

    /** 深度 0 的首个关键字位置；无则 -1。 */
    private static int indexOfAtDepth0(String masked, Pattern keyword) {
        int depth = 0;
        Matcher m = keyword.matcher(masked);
        int i = 0;
        while (i < masked.length()) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && m.find(i) && m.start() == i) {
                return i;
            }
            i++;
            if (depth < 0) {
                return -1;
            }
        }
        return -1;
    }

    // ---------- 维度词与列匹配 ----------

    /** 反复剥离维度词尾部粘附助词（"省份维度呢" → "省份"）。 */
    private static String stripParticles(String raw) {
        String s = raw;
        boolean changed = true;
        while (changed && s.length() > MIN_DIM_LENGTH) {
            changed = false;
            for (String p : TRAILING_PARTICLES) {
                if (s.endsWith(p) && s.length() - p.length() >= MIN_DIM_LENGTH) {
                    s = s.substring(0, s.length() - p.length());
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    /**
     * 唯一命中：标签/列名等值（忽略大小写）优先；否则包含式，命中到不同列视为歧义放弃。
     * 同一列被多路命中（如列名与标签都含维度词）只算一个候选。
     */
    private static Optional<String> matchColumn(String dim, List<Dim> dims) {
        String d = dim.toLowerCase(Locale.ROOT);
        String exact = null;
        int exactHits = 0;
        for (Dim dim2 : dims) {
            if (dim2 == null || dim2.column() == null) {
                continue;
            }
            String col = dim2.column().toLowerCase(Locale.ROOT);
            String label = dim2.label() == null ? col : dim2.label().toLowerCase(Locale.ROOT);
            if (label.equals(d) || col.equals(d)) {
                if (exact != null && !exact.equals(dim2.column())) {
                    return Optional.empty();   // 同标签落在不同列：等值也歧义
                }
                if (exact == null) {
                    exactHits++;
                    exact = dim2.column();
                }
            }
        }
        if (exactHits == 1) {
            return Optional.of(exact);
        }
        String found = null;
        for (Dim dim2 : dims) {
            if (dim2 == null || dim2.column() == null) {
                continue;
            }
            String col = dim2.column().toLowerCase(Locale.ROOT);
            String label = dim2.label() == null ? col : dim2.label().toLowerCase(Locale.ROOT);
            if (label.contains(d) || col.contains(d)) {
                if (found != null && !found.equals(dim2.column())) {
                    return Optional.empty();   // 命中不同列：歧义
                }
                found = dim2.column();
            }
        }
        return Optional.ofNullable(found);
    }

    // ---------- 字符串手术 ----------

    /** 在指定下标处插入片段（原内容右移，逐字节保留其余部分）。 */
    private static String insertAt(String s, int index, String piece) {
        return s.substring(0, index) + piece + s.substring(index);
    }

    private static String stripTrailingSemicolon(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ';' || Character.isWhitespace(s.charAt(end - 1)))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
