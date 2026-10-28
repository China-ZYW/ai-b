package com.aibi.module.report.service;

import com.aibi.module.report.entity.ScheduledReport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 定时报表到期判定（T12-1 纯函数，不碰 Spring/DB）。
 *
 * <p>三档预置频率，统一以「本周期目标时刻 slot」为界、以
 * {@code anchor = lastRunAt != null ? lastRunAt : createdAt} 为幂等锚：
 * <ul>
 *   <li>DAILY：{@code slot = 今日 runTime}；{@code now >= slot && anchor < slot}</li>
 *   <li>WEEKLY：同 DAILY，另加「今日周几 ∈ weekdays」闸门</li>
 *   <li>HOURLY：{@code slot = now 截断到整点}；{@code anchor < slot}（每小时越过整点即到期）</li>
 * </ul>
 *
 * <p><b>宁漏不重</b>：锚点判定只比较「是否早于本周期 slot」——服务停机错过的周期不补跑
 * （DAILY 当天 23:00 建 09:00 档报表：created 晚于 slot，当天与昨天都不命中，从次日生效）；
 * 同一周期内重复扫描因 {@code anchor >= slot} 天然去重，配合调度侧先回写
 * {@code last_run_at} 再投递的顺序保证，杜绝同轮双发。
 *
 * <p>配置即闭集校验的一部分：freq 未知、runTime 非法、WEEKLY 缺有效周几一律判不到期（静默
 * 由 scheduler 侧 log 承担，判定本身绝不抛异常）。
 */
public final class ReportDueJudge {

    /** run_time 列格式 HH:mm（与 DDL VARCHAR(5) 对应）。 */
    private static final DateTimeFormatter RUN_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private ReportDueJudge() {
    }

    /**
     * 报表 {@code report} 在时刻 {@code now} 是否到期。null 入参/停用/配置非法一律 false。
     */
    public static boolean isDue(ScheduledReport report, LocalDateTime now) {
        if (report == null || now == null || !Boolean.TRUE.equals(report.getEnabled())) {
            return false;
        }
        // 幂等锚：跑过用 lastRunAt，从未跑过用创建时刻（当天建当天档不追溯历史周期）
        LocalDateTime anchor = report.getLastRunAt() != null ? report.getLastRunAt() : report.getCreatedAt();
        if (anchor == null) {
            return false;
        }
        String freq = report.getFreq() == null ? "" : report.getFreq().trim().toUpperCase(Locale.ROOT);
        switch (freq) {
            case "HOURLY":
                // 每个整点为周期界；本小时内跑过（anchor >= slot）则不再命中
                return anchor.isBefore(now.truncatedTo(ChronoUnit.HOURS));
            case "DAILY":
                return slotDue(parseRunTime(report.getRunTime()), now, anchor);
            case "WEEKLY":
                // 周几闸门在前：今日不在集合内直接不到期（即便 runTime 已越过）
                return matchesWeekday(report.getWeekdays(), now.toLocalDate())
                        && slotDue(parseRunTime(report.getRunTime()), now, anchor);
            default:
                return false;
        }
    }

    /** slot 维度通用判定：今日 slot 已越过，且锚点早于 slot（本周期内未跑过）。 */
    private static boolean slotDue(LocalTime runTime, LocalDateTime now, LocalDateTime anchor) {
        if (runTime == null) {
            return false;
        }
        LocalDateTime slot = now.toLocalDate().atTime(runTime);
        return !now.isBefore(slot) && anchor.isBefore(slot);
    }

    /** 解析 HH:mm；空/非法返回 null（调用侧按不到期处理）。 */
    static LocalTime parseRunTime(String runTime) {
        if (runTime == null || runTime.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(runTime.trim(), RUN_TIME_FMT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 今日（{@code date}）的 ISO 周几（周一=1…周日=7）是否落在 {@code weekdays}
     * 集合串（如 "1,3,5"）内。空/无有效数字返回 false——WEEKLY 未配周几视为配置不完整。
     */
    static boolean matchesWeekday(String weekdays, LocalDate date) {
        Set<Integer> set = parseWeekdays(weekdays);
        return set.contains(date.getDayOfWeek().getValue());
    }

    /** 宽容解析周几集：非数字分隔、去重、仅收 1~7；其余丢弃。 */
    static Set<Integer> parseWeekdays(String weekdays) {
        Set<Integer> set = new HashSet<>();
        if (weekdays == null || weekdays.isBlank()) {
            return set;
        }
        for (String part : weekdays.split("[^0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(part);
                if (v >= 1 && v <= 7) {
                    set.add(v);
                }
            } catch (NumberFormatException e) {
                // 超长数字串等异常片段直接忽略
            }
        }
        return set;
    }
}
