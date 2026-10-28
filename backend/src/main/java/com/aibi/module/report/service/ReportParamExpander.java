package com.aibi.module.report.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时报表相对时间参数展开器（T12-1b）：把 {@code params_json} 值里的 {@code {{token}}}
 * 变量按<b>本次执行锚点</b>确定性展开成字面值，让日报/周报每天自动跑"昨天/本月"的数据。
 *
 * <p><b>设计口径（K0 拍板）</b>：
 * <ol>
 *   <li>锚点 = {@code runOnce} 的 {@code runAt}（调度 tick 与手动 run-now 同源），
 *       同一次执行内所有参数共享锚点，跨参数日期关系恒一致（如 {@code dt>={{days_ago:7}}}
 *       与 {@code dt<={{today}}} 永不跨天错位）；</li>
 *   <li>仅处理 String 值；不含 {@code &#123;&#123;} 的值原样透传（固定值报表零影响）；
 *       数字/布尔等非字符串值不动（JDBC 绑定本就直接传）；</li>
 *   <li>字符串内可混排多变量与字面文本（模板拼接场景）；</li>
 *   <li><b>未知 token / 非法格式 pattern 一律抛错</b>——宁停不错跑：带残缺参数执行
 *       会把错误数据推给用户，比不推更糟。执行链路折进 FAILED + last_error；
 *       创建/更新入口用 {@link #assertValid} dry-run 提前拒绝，不让人等到夜里才发现；</li>
 *   <li>纯静态零依赖零 IO，可全分支离线单测（对齐 T3 风格）。</li>
 * </ol>
 *
 * <p><b>支持的 token</b>（中英文别名等价，日期默认 {@code yyyy-MM-dd}，时刻默认
 * {@code yyyy-MM-dd HH:mm:ss}，可加 {@code :pattern} 后缀自定义，如
 * {@code &#123;&#123;yesterday:yyyyMMdd&#125;&#125;} 适配 yyyymmdd 分区列）：
 * <pre>
 *   today/今天 · yesterday/昨天 · tomorrow/明天
 *   monday/本周一 · month_start/本月首日 · month_end/本月末日
 *   prev_month_start/上月首日 · prev_month_end/上月末日
 *   now/当前时间（含时分秒）
 *   days_ago:N（N 天前的日期） · hours_ago:N（N 小时前的时刻）
 * </pre>
 */
public final class ReportParamExpander {

    /** token 形态：{{name}} 或 {{name:arg}}；首个 : 后全部作参数位（pattern 自身含 : 如 HH:mm）。 */
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";
    private static final String DEFAULT_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** days_ago / hours_ago 的 N 上限（防手滑写出无意义回溯，也防负数）。 */
    private static final long MAX_LOOKBACK_DAYS = 3660;
    private static final long MAX_LOOKBACK_HOURS = 87840;

    private ReportParamExpander() {
    }

    /**
     * 逐参数展开（执行链路入口）：无 {@code {{}}} 的表原样返回；抛错即整单 FAILED。
     *
     * @param params 解析自 params_json 的参数表（值可为任意 JSON 类型）
     * @param runAt  本次执行锚点
     */
    public static Map<String, Object> expand(Map<String, Object> params, LocalDateTime runAt) {
        if (params == null || params.isEmpty()) {
            return params == null ? Map.of() : params;
        }
        Map<String, Object> out = new LinkedHashMap<>(params.size());
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof String s ? expandValue(s, runAt) : v);
        }
        return out;
    }

    /** 单值展开：不含 token 直接原样返回（快路径）。 */
    public static String expandValue(String value, LocalDateTime runAt) {
        if (value == null || value.indexOf('{') < 0) {
            return value;
        }
        Matcher m = TOKEN.matcher(value);
        StringBuilder sb = new StringBuilder(value.length());
        int last = 0;
        while (m.find()) {
            String body = m.group(1);
            int c = body.indexOf(':');
            String name = c < 0 ? body : body.substring(0, c).trim();
            String arg = c < 0 ? null : body.substring(c + 1).trim();
            sb.append(value, last, m.start());
            sb.append(resolve(name, arg, runAt));
            last = m.end();
        }
        if (last == 0) {
            // 含 { 但没有任何完整 token（如手滑写单括号 {today}）：原样透传，交给 SQL 执行时报参错
            return value;
        }
        sb.append(value, last, value.length());
        return sb.toString();
    }

    /**
     * 创建/更新入口的 dry-run：以当前时刻为锚试算一遍，未知 token / 坏 pattern /
     * 坏 N 当场拒（文案可控），不等夜间调度执行才发现配置错误。
     */
    public static void assertValid(Map<String, Object> params) {
        if (params == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() instanceof String s) {
                expandValue(s, now);
            }
        }
    }

    /** 解析单个 token：name + 可选尾参（日期类为 pattern，days_ago/hours_ago 为 N）→ 字面值。 */
    private static String resolve(String name, String arg, LocalDateTime runAt) {
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        LocalDateTime base = runAt == null ? LocalDateTime.now() : runAt;
        boolean isDateTime = false;
        LocalDate d = null;
        LocalDateTime dt = null;
        String pattern = arg;

        switch (key) {
            case "today", "今天" -> d = base.toLocalDate();
            case "yesterday", "昨天" -> d = base.toLocalDate().minusDays(1);
            case "tomorrow", "明天" -> d = base.toLocalDate().plusDays(1);
            case "monday", "this_monday", "本周一" ->
                    d = base.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month_start", "first_day_of_month", "本月首日" ->
                    d = base.toLocalDate().withDayOfMonth(1);
            case "month_end", "last_day_of_month", "本月末日" ->
                    d = base.toLocalDate().with(TemporalAdjusters.lastDayOfMonth());
            case "prev_month_start", "last_month_start", "上月首日" ->
                    d = base.toLocalDate().minusMonths(1).withDayOfMonth(1);
            case "prev_month_end", "last_month_end", "上月末日" ->
                    d = base.toLocalDate().minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
            case "now", "当前时间" -> {
                dt = base;
                isDateTime = true;
            }
            case "days_ago" -> d = base.toLocalDate().minusDays(
                    parseLookback(arg, MAX_LOOKBACK_DAYS, "days_ago"));
            case "hours_ago" -> {
                dt = base.minusHours(parseLookback(arg, MAX_LOOKBACK_HOURS, "hours_ago"));
                isDateTime = true;
            }
            default -> throw new BizException(ErrorCode.PARAM_ERROR,
                    "不识别的时间变量 {{" + name + "}}，可用：today/yesterday/tomorrow/monday/"
                            + "month_start/month_end/prev_month_start/prev_month_end/now/"
                            + "days_ago:N/hours_ago:N（或对应中文别名）");
        }
        // days_ago/hours_ago 的尾参是 N 不是 pattern，不开放自定义格式（宁缺勿滥）
        if ("days_ago".equals(key) || "hours_ago".equals(key)) {
            pattern = null;
        }
        String pat = pattern == null || pattern.isBlank()
                ? (isDateTime ? DEFAULT_DATETIME_PATTERN : DEFAULT_DATE_PATTERN) : pattern;
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pat);
            return dt != null ? fmt.format(dt) : fmt.format(d);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "时间变量 {{" + name + (pattern != null ? ":" + pattern : "") + "}} 格式不合法: "
                            + e.getMessage());
        }
    }

    /** days_ago/hours_ago 的 N：非负整数且不超上限，否则拒（防手滑；缺 N 同样拒）。 */
    private static long parseLookback(String raw, long max, String tokenName) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, tokenName + " 必须携带天数/小时数：{{"
                    + tokenName + ":7}}");
        }
        long n;
        try {
            n = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, tokenName + " 的 N 必须是整数：" + raw);
        }
        if (n < 0 || n > max) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    tokenName + " 的 N 须在 0~" + max + " 之间：" + n);
        }
        return n;
    }
}
