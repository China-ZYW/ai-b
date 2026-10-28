package com.aibi.module.audit.nlquery;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计 NL 查询的时间短语<b>确定性</b>解析器（T11-3）。
 *
 * <p><b>为什么规则先行</b>：「今天/昨天/最近7天/上周/本月」覆盖了审计场景绝大部分时间问法，
 * 纯函数解析零成本、结果确定、可穷举单测；LLM 只负责规则盖不住的槽位（人物/动作/关键词），
 * 即便 LLM 也输出了时间，融合侧以本规则结果为准（确定性优先，见设计稿 §4.1）。
 * 规则未命中返回 {@code null}，交 LLM 的 timeFrom/timeTo 兜底。
 */
public final class AuditTimePhrases {

    /** 与审计检索端点一致的时间格式。 */
    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 「最近/近/过去 N 天|日|周|星期|个月|月」：N 支持阿拉伯数字与简单中文数（一~九十九、两）。 */
    private static final Pattern RELATIVE = Pattern.compile("(?:最近|近|过去)(?:的)?([0-9]{1,3}|[一二两三四五六七八九十]{1,3})(天|日|周|星期|个?月)");
    private static final Pattern ABS_FULL = Pattern.compile("(\\d{4})[年-](\\d{1,2})[月-](\\d{1,2})日?");
    private static final Pattern ABS_MD = Pattern.compile("(?<![0-9])(\\d{1,2})月(\\d{1,2})日");

    private AuditTimePhrases() {
    }

    /** 时间窗（含边界）。 */
    public record Window(LocalDateTime from, LocalDateTime to) {
        public String fromText() {
            return from.format(TS);
        }

        public String toText() {
            return to.format(TS);
        }
    }

    /**
     * 从问句中解析时间短语；未命中返回 null（不臆造默认窗口——没提时间就查全量，由检索上限护栏）。
     *
     * @param question 原始问句（勿传归一化文本：归一会剥数字，"最近7天" 将失真）
     * @param now      当前时间（调用方注入，便于单测固定时钟）
     */
    public static Window parse(String question, LocalDateTime now) {
        if (question == null || question.isBlank() || now == null) {
            return null;
        }
        String q = question;
        LocalDate today = now.toLocalDate();

        if (q.contains("今天") || q.contains("今日")) {
            return day(today);
        }
        if (q.contains("昨天") || q.contains("昨日")) {
            return day(today.minusDays(1));
        }
        if (q.contains("前天")) {
            return day(today.minusDays(2));
        }
        // 相对窗要先于「上周/本月」判定："最近一周" 属相对而非周界
        Matcher rel = RELATIVE.matcher(q);
        if (rel.find()) {
            Integer n = num(rel.group(1));
            if (n != null && n > 0) {
                int days = switch (rel.group(2)) {
                    case "周", "星期" -> n * 7;
                    case "月", "个月" -> n * 30;
                    default -> n; // 天 / 日
                };
                // 「最近N天」含今天：起点 = 今天-(N-1) 零点
                return new Window(today.minusDays(Math.max(0, days - 1L)).atStartOfDay(), endOfDay(today));
            }
        }
        if (q.contains("本周") || q.contains("这周") || q.contains("这个星期") || q.contains("这星期")) {
            LocalDate mon = mondayOf(today);
            return new Window(mon.atStartOfDay(), endOfDay(mon.plusDays(6)));
        }
        if (q.contains("上周") || q.contains("上个星期") || q.contains("上星期")) {
            LocalDate mon = mondayOf(today).minusWeeks(1);
            return new Window(mon.atStartOfDay(), endOfDay(mon.plusDays(6)));
        }
        if (q.contains("本月") || q.contains("这个月")) {
            LocalDate first = today.withDayOfMonth(1);
            return new Window(first.atStartOfDay(), endOfDay(first.plusMonths(1).minusDays(1)));
        }
        if (q.contains("上月") || q.contains("上个月")) {
            LocalDate first = today.withDayOfMonth(1).minusMonths(1);
            return new Window(first.atStartOfDay(), endOfDay(first.plusMonths(1).minusDays(1)));
        }
        // 绝对日期：2026-10-25 / 2026年10月25日；"10月25日" 缺年份按当年
        Matcher ymd = ABS_FULL.matcher(q);
        if (ymd.find()) {
            LocalDate d = safeDate(parseInt(ymd.group(1)), parseInt(ymd.group(2)), parseInt(ymd.group(3)));
            if (d != null) {
                return day(d);
            }
        }
        Matcher md = ABS_MD.matcher(q);
        if (md.find()) {
            LocalDate d = safeDate(today.getYear(), parseInt(md.group(1)), parseInt(md.group(2)));
            if (d != null) {
                return day(d);
            }
        }
        return null;
    }

    private static Window day(LocalDate d) {
        return new Window(d.atStartOfDay(), endOfDay(d));
    }

    private static LocalDateTime endOfDay(LocalDate d) {
        return d.atTime(23, 59, 59);
    }

    private static LocalDate mondayOf(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            return null; // 非法日期（如 13月40日）视为未命中，不臆造
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 中文数/阿拉伯数 → 整数（一~九十九，含"两"；解析不了返回 null）。 */
    static Integer num(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(s);
        }
        int tenIdx = s.indexOf('十');
        if (tenIdx < 0) {
            return s.length() == 1 ? digit(s.charAt(0)) : null;
        }
        int tens = tenIdx == 0 ? 1 : digitValue(s.substring(0, tenIdx));
        int ones = tenIdx == s.length() - 1 ? 0 : digitOrZero(s.charAt(s.length() - 1));
        if (tens < 0 || ones < 0) {
            return null;
        }
        return tens * 10 + ones;
    }

    private static int digitValue(String prefix) {
        Integer d = digit(prefix.charAt(0));
        return d == null ? -1 : d;
    }

    private static int digitOrZero(char c) {
        Integer d = digit(c);
        return d == null ? -1 : d;
    }

    private static Integer digit(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> null;
        };
    }
}
