package com.aibi.module.report.service;

import com.aibi.module.datasource.model.QueryResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 定时报表正文渲染（T12-1 纯函数：QueryResult → 纯文本，不碰 Spring/DB/网络）。
 *
 * <p>风格对齐告警站内信/邮件的简洁文本口径：标题行 + 元信息行 + 等宽对齐的文本表格。
 * 两条截断红线（设计稿 P6）：
 * <ul>
 *   <li>行数：最多 {@link #TOP_ROWS} 行，超出时标注「共 N 行，仅展示前 20 行」——
 *       报表正文要进邮件与站内信卡片，巨表撑爆可读性与体积；</li>
 *   <li>单元格：字符串超 {@link #CELL_MAX} 字符截断加省略号，防长文本把表格拉变形。</li>
 * </ul>
 *
 * <p>表头优先用 {@code columnLabels}（业务别名，元素级回落物理列名），与前端明细表
 * 同一套「列身份/展示名分离」口径；null 单元格渲染为 {@code -}。
 */
public final class ReportRenderer {

    /** 正文最多展示的数据行数（设计稿 P6 拍板值）。 */
    public static final int TOP_ROWS = 20;
    /** 单元格最大展示字符数，超出截断加省略号。 */
    public static final int CELL_MAX = 24;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DIVIDER = "--------------------";

    private ReportRenderer() {
    }

    /**
     * 渲染一份报表正文。
     *
     * @param reportName  报表名（标题行）
     * @param templateName 来源模板名（元信息行，可 null）
     * @param runAt       本轮执行时刻（可 null 则省略）
     * @param result      模板重跑结果（null/空行 → 「（查询结果为空）」）
     */
    public static String render(String reportName, String templateName, LocalDateTime runAt, QueryResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("【定时报表】").append(reportName == null || reportName.isBlank() ? "(未命名)" : reportName).append('\n');
        if (templateName != null && !templateName.isBlank()) {
            sb.append("来源模板：").append(templateName).append('\n');
        }
        if (runAt != null) {
            sb.append("执行时间：").append(runAt.format(TIME_FMT)).append('\n');
        }
        sb.append(DIVIDER).append('\n');
        appendTable(sb, result);
        if (result != null) {
            sb.append(DIVIDER).append('\n');
            sb.append("耗时 ").append(result.executionTimeMs()).append(" ms");
        }
        return sb.toString();
    }

    /** 表格主体：空结果占位；否则等宽对齐的前 {@link #TOP_ROWS} 行 + 截断标注。 */
    private static void appendTable(StringBuilder sb, QueryResult result) {
        List<String> columns = result == null ? null : result.columns();
        List<List<Object>> rows = result == null ? null : result.rows();
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            sb.append("（查询结果为空）\n");
            return;
        }
        int limit = Math.min(TOP_ROWS, rows.size());
        // ① 逐格格式化（含单元格截断），表头优先展示别名
        List<String> headers = new ArrayList<>(columns.size());
        List<String> alias = result.columnLabels();
        for (int c = 0; c < columns.size(); c++) {
            String label = alias != null && c < alias.size() ? alias.get(c) : null;
            headers.add(truncate(label != null && !label.isBlank() ? label : columns.get(c)));
        }
        List<List<String>> cells = new ArrayList<>(limit);
        for (int r = 0; r < limit; r++) {
            List<Object> row = rows.get(r);
            List<String> line = new ArrayList<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                Object v = row != null && c < row.size() ? row.get(c) : null;
                line.add(truncate(formatCell(v)));
            }
            cells.add(line);
        }
        // ② 数值列判定（该列全部非空值都是 Number 才算）：右对齐 + 千分位，金额/比率读数不靠肉眼数零
        boolean[] numeric = new boolean[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            boolean seen = false;
            boolean allNumeric = true;
            for (int r = 0; r < limit; r++) {
                Object v = rows.get(r) != null && c < rows.get(r).size() ? rows.get(r).get(c) : null;
                if (v == null) {
                    continue;
                }
                seen = true;
                if (!(v instanceof Number)) {
                    allNumeric = false;
                    break;
                }
            }
            numeric[c] = seen && allNumeric;
        }
        // ③ 列宽 = 表头与该列展示值的最大长度，等宽对齐（纯文本渠道的可读性底线）
        int[] widths = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            int w = headers.get(c).length();
            for (List<String> line : cells) {
                w = Math.max(w, line.get(c).length());
            }
            widths[c] = w;
        }
        sb.append(joinPadded(headers, widths, numeric)).append('\n');
        for (List<String> line : cells) {
            sb.append(joinPadded(line, widths, numeric)).append('\n');
        }
        int total = result.rowCount() > 0 ? Math.max(result.rowCount(), rows.size()) : rows.size();
        if (rows.size() > limit) {
            sb.append("… 共 ").append(total).append(" 行，仅展示前 ").append(limit).append(" 行\n");
        } else if (total > rows.size()) {
            // rows 已被上游（maxRows）截断：展示的是全部所得行，但总数更大，需注明
            sb.append("（结果共 ").append(total).append(" 行，本表仅获取前 ").append(rows.size()).append(" 行）\n");
        }
    }

    /**
     * 单元格值 → 展示串：null 用 "-"；数值走千分位（整数无小数位、浮点/定点固定 2 位，
     * 全精度去历史明细表看）；其余 toString（日期 ISO 形态纯文本可读）。
     */
    private static String formatCell(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Number) {
            return formatNumber((Number) value);
        }
        return String.valueOf(value);
    }

    /** 数值格式化：整型族千分位无小数；浮点/定点千分位 + 四舍五入 2 位小数。 */
    private static String formatNumber(Number value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return String.format(Locale.ROOT, "%,d", value.longValue());
        }
        // Double/Float/BigDecimal：经 toString 转 BigDecimal，避免 float 二进制噪声
        BigDecimal d = new BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
        return String.format(Locale.ROOT, "%,.2f", d);
    }

    /** 超长字符串截断加省略号；未超限原样返回。 */
    private static String truncate(String text) {
        if (text.length() <= CELL_MAX) {
            return text;
        }
        return text.substring(0, CELL_MAX - 1) + "…";
    }

    /** 一行按列宽拼接：数值列右对齐（表头随列），其余左对齐，" | " 分隔。 */
    private static String joinPadded(List<String> line, int[] widths, boolean[] numeric) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < line.size(); c++) {
            if (c > 0) {
                sb.append(" | ");
            }
            sb.append(numeric[c] ? padLeft(line.get(c), widths[c]) : padRight(line.get(c), widths[c]));
        }
        return sb.toString();
    }

    private static String padLeft(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat(width - text.length()) + text;
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }
}
