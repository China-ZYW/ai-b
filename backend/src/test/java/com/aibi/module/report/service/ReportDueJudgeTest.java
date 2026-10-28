package com.aibi.module.report.service;

import com.aibi.module.report.entity.ScheduledReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportDueJudge} 纯逻辑单测（T12-1 K1）。
 *
 * <p>全部用固定时刻构造场景：2026-10-28 为周三（ISO 周几 = 3）。覆盖三档频率的
 * 到期/未到期/本周期已跑/首跑锚点/非法配置/宁漏不重边界。
 */
class ReportDueJudgeTest {

    /** 周三 09:30（DAILY runTime=09:00 已越过） */
    private static final LocalDateTime WED_0930 = LocalDateTime.of(2026, 10, 28, 9, 30);
    /** 周三 08:59（DAILY runTime=09:00 未越过） */
    private static final LocalDateTime WED_0859 = LocalDateTime.of(2026, 10, 28, 8, 59);
    /** 周四（ISO=4） */
    private static final LocalDateTime THU_0930 = LocalDateTime.of(2026, 10, 29, 9, 30);

    private static ScheduledReport report(String freq, String runTime, String weekdays,
                                          LocalDateTime lastRunAt, LocalDateTime createdAt) {
        ScheduledReport r = new ScheduledReport();
        r.setEnabled(true);
        r.setFreq(freq);
        r.setRunTime(runTime);
        r.setWeekdays(weekdays);
        r.setLastRunAt(lastRunAt);
        r.setCreatedAt(createdAt);
        return r;
    }

    // ---------- 通用防御 ----------

    @Test
    void nullInputsAndDisabledNeverDue() {
        assertFalse(ReportDueJudge.isDue(null, WED_0930));
        assertFalse(ReportDueJudge.isDue(report("DAILY", "09:00", null, null, WED_0859), null));
        ScheduledReport disabled = report("DAILY", "09:00", null, null, WED_0859);
        disabled.setEnabled(false);
        assertFalse(ReportDueJudge.isDue(disabled, WED_0930));
        // 锚点缺失（lastRun 与 created 双空）防御
        assertFalse(ReportDueJudge.isDue(report("DAILY", "09:00", null, null, null), WED_0930));
    }

    @Test
    void unknownFreqAndIllegalRunTimeFalse() {
        assertFalse(ReportDueJudge.isDue(report("MONTHLY", "09:00", null, null, WED_0859), WED_0930));
        assertFalse(ReportDueJudge.isDue(report(null, "09:00", null, null, WED_0859), WED_0930));
        // runTime 非法格式 → 判不到期而不是抛异常
        assertFalse(ReportDueJudge.isDue(report("DAILY", "25:99", null, null, WED_0859), WED_0930));
        assertFalse(ReportDueJudge.isDue(report("DAILY", null, null, null, WED_0859), WED_0930));
    }

    // ---------- DAILY ----------

    @Test
    void dailyBeforeSlotNotDue() {
        assertFalse(ReportDueJudge.isDue(
                report("DAILY", "09:00", null, LocalDateTime.of(2026, 10, 27, 9, 0), WED_0859), WED_0859));
    }

    @Test
    void dailyAfterSlotDue() {
        // 昨天 09:00 跑过，今天 09:30 → 到期
        assertTrue(ReportDueJudge.isDue(
                report("DAILY", "09:00", null, LocalDateTime.of(2026, 10, 27, 9, 0), WED_0859), WED_0930));
        // freq 大小写宽容
        assertTrue(ReportDueJudge.isDue(
                report("daily", "09:00", null, LocalDateTime.of(2026, 10, 27, 9, 0), WED_0859), WED_0930));
    }

    @Test
    void dailyAlreadyRanThisCycleNotDue() {
        // 今天 09:05 已跑过（lastRun >= slot）→ 同周期不重跑
        assertFalse(ReportDueJudge.isDue(
                report("DAILY", "09:00", null, LocalDateTime.of(2026, 10, 28, 9, 5), WED_0859), WED_0930));
    }

    @Test
    void dailyFirstRunUsesCreatedAtAnchor() {
        // 当天 08:00 建、09:00 档、现在 09:30 → 首跑到期（created < slot）
        assertTrue(ReportDueJudge.isDue(report("DAILY", "09:00", null, null,
                LocalDateTime.of(2026, 10, 28, 8, 0)), WED_0930));
        // 当天 23:00 建、09:00 档、现在 23:30 → 宁漏不重：created 晚于今日 slot，不补跑
        assertFalse(ReportDueJudge.isDue(report("DAILY", "09:00", null, null,
                LocalDateTime.of(2026, 10, 28, 23, 0)), LocalDateTime.of(2026, 10, 28, 23, 30)));
    }

    // ---------- WEEKLY ----------

    @Test
    void weeklyMatchingDayDue() {
        // 周三 ∈ {1,3,5}，09:30 > 09:00，上轮为上周 → 到期
        assertTrue(ReportDueJudge.isDue(report("WEEKLY", "09:00", "1,3,5",
                LocalDateTime.of(2026, 10, 26, 9, 10), WED_0859), WED_0930));
    }

    @Test
    void weeklyNonSelectedDayNotDue() {
        // 周四 ∉ {1,3,5}
        assertFalse(ReportDueJudge.isDue(report("WEEKLY", "09:00", "1,3,5",
                LocalDateTime.of(2026, 10, 21, 9, 10), WED_0859), THU_0930));
    }

    @Test
    void weeklyEmptyOrIllegalWeekdaysNotDue() {
        assertFalse(ReportDueJudge.isDue(report("WEEKLY", "09:00", null, null, WED_0859), WED_0930));
        assertFalse(ReportDueJudge.isDue(report("WEEKLY", "09:00", "", null, WED_0859), WED_0930));
        assertFalse(ReportDueJudge.isDue(report("WEEKLY", "09:00", "8,0", null, WED_0859), WED_0930));
    }

    @Test
    void weeklyWeekdaysParsingIsTolerant() {
        // 非数字分隔宽容："1; 3" 与 "1,3" 等价（周三命中）
        assertTrue(ReportDueJudge.matchesWeekday("1; 3", WED_0930.toLocalDate()));
        assertTrue(ReportDueJudge.matchesWeekday("3", WED_0930.toLocalDate()));
        assertFalse(ReportDueJudge.matchesWeekday("2,4", WED_0930.toLocalDate()));
    }

    @Test
    void weeklyAlreadyRanThisWeekNotDue() {
        // 今日（周期内同一天）09:05 已跑过
        assertFalse(ReportDueJudge.isDue(report("WEEKLY", "09:00", "3",
                LocalDateTime.of(2026, 10, 28, 9, 5), WED_0859), WED_0930));
    }

    // ---------- HOURLY ----------

    @Test
    void hourlyCrossedHourDue() {
        // 上一小时内跑过（10:59），现在 11:02 → 越过整点即到期
        assertTrue(ReportDueJudge.isDue(report("HOURLY", null, null,
                LocalDateTime.of(2026, 10, 28, 10, 59), WED_0859), LocalDateTime.of(2026, 10, 28, 11, 2)));
    }

    @Test
    void hourlySameHourNotDue() {
        // 本小时内已跑（11:01），现在 11:02 → 不重
        assertFalse(ReportDueJudge.isDue(report("HOURLY", null, null,
                LocalDateTime.of(2026, 10, 28, 11, 1), WED_0859), LocalDateTime.of(2026, 10, 28, 11, 2)));
    }

    @Test
    void hourlyFirstRunBeforeCurrentHourDue() {
        // 首跑：created 在上一小时（10:30），现在 11:02 → 到期
        assertTrue(ReportDueJudge.isDue(report("HOURLY", null, null, null,
                LocalDateTime.of(2026, 10, 28, 10, 30)), LocalDateTime.of(2026, 10, 28, 11, 2)));
        // 首跑：本小时内刚建（11:01），现在 11:02 → 等下个小时，不当小时抢跑
        assertFalse(ReportDueJudge.isDue(report("HOURLY", null, null, null,
                LocalDateTime.of(2026, 10, 28, 11, 1)), LocalDateTime.of(2026, 10, 28, 11, 2)));
    }
}
