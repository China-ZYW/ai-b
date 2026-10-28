package com.aibi.module.report.service;

import com.aibi.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReportParamExpander} 纯逻辑单测（T12-1b K1）：固定 token 中英文/日期边界/
 * pattern 后缀/days_ago 与 hours_ago/字符串混排/非字符串透传/未知 token 与坏参数拒绝。
 */
class ReportParamExpanderTest {

    /** 2026-10-26 恰为周一；09:30 锚点。 */
    private static final LocalDateTime MON = LocalDateTime.of(2026, 10, 26, 9, 30);
    /** 2026-11-01 为周日（验证本周一回看）。 */
    private static final LocalDateTime SUN = LocalDateTime.of(2026, 11, 1, 23, 59, 59);

    private static String ex(String v, LocalDateTime at) {
        return ReportParamExpander.expandValue(v, at);
    }

    @Test
    void dateTokensWithChineseAliases() {
        assertEquals("2026-10-26", ex("{{today}}", MON));
        assertEquals("2026-10-25", ex("{{yesterday}}", MON));
        assertEquals("2026-10-27", ex("{{tomorrow}}", MON));
        assertEquals("2026-10-25", ex("{{昨天}}", MON));
        assertEquals("2026-10-26", ex("{{ 今天 }}", MON));   // 两侧空白宽容
        assertEquals("2026-10-26", ex("{{monday}}", MON));   // 周一锚点回看自身
        assertEquals("2026-10-26", ex("{{本周一}}", SUN));   // 周日锚点回看本周一
        assertEquals("2026-10-01", ex("{{month_start}}", MON));
        assertEquals("2026-10-31", ex("{{本月末日}}", MON));
        assertEquals("2026-09-01", ex("{{prev_month_start}}", MON));
        assertEquals("2026-09-30", ex("{{上月末日}}", MON));
    }

    @Test
    void lookbackTokens() {
        assertEquals("2026-10-19", ex("{{days_ago:7}}", MON));
        assertEquals("2026-10-26", ex("{{days_ago:0}}", MON));
        assertEquals("2026-10-26 04:30:00", ex("{{hours_ago:5}}", MON));
        // 尾参是 N 不是 pattern：即便写成 pattern 形态也按 N 解析并报错
        assertThrows(BizException.class, () -> ex("{{days_ago:yyyy}}", MON));
    }

    @Test
    void nowAndPatternSuffix() {
        assertEquals("2026-10-26 09:30:00", ex("{{now}}", MON));
        assertEquals("2026-10-26 09:30:00", ex("{{当前时间}}", MON));
        assertEquals("20261025", ex("{{yesterday:yyyyMMdd}}", MON));
        assertEquals("09:30", ex("{{now:HH:mm}}", MON));     // pattern 自身含冒号
        assertEquals("2026/10/26", ex("{{today:yyyy/MM/dd}}", MON));
    }

    @Test
    void mixedTextAndMultipleTokens() {
        assertEquals("dt>=2026-10-19 and dt<=2026-10-26",
                ex("dt>={{days_ago:7}} and dt<={{today}}", MON));
        assertEquals("2026-10-25~2026-10-25", ex("{{昨天}}~{{昨天}}", MON));
    }

    @Test
    void passthroughWhenNoToken() {
        assertSame("hangzhou", ex("hangzhou", MON));
        assertSame("2026-09-30", ex("2026-09-30", MON));     // 固定值报表零影响
        assertSame("{today}", ex("{today}", MON));           // 单括号不算 token，原样交给 SQL 报错
        assertEquals("", ex("", MON));
    }

    @Test
    void expandMapOnlyTouchesStrings() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("biz_date", "{{yesterday:yyyyMMdd}}");
        in.put("limit_n", 50);
        in.put("city", "杭州");
        Map<String, Object> out = ReportParamExpander.expand(in, MON);
        assertEquals("20261025", out.get("biz_date"));
        assertEquals(50, out.get("limit_n"));                // 非字符串原样
        assertEquals("杭州", out.get("city"));
        assertEquals(List.of("biz_date", "limit_n", "city"), List.copyOf(out.keySet()));
        // 空表/ null 表防御
        assertTrue(ReportParamExpander.expand(null, MON).isEmpty());
    }

    @Test
    void unknownTokenOrBadPatternRejected() {
        BizException e1 = assertThrows(BizException.class, () -> ex("{{next_week}}", MON));
        assertTrue(e1.getMessage().contains("next_week"));
        assertThrows(BizException.class, () -> ex("{{today:abc}}", MON));   // 未定义 pattern 字符
        assertThrows(BizException.class, () -> ex("{{days_ago}}", MON));    // 缺 N
        assertThrows(BizException.class, () -> ex("{{days_ago:-3}}", MON)); // 负 N
        assertThrows(BizException.class, () -> ex("{{hours_ago:999999}}", MON)); // N 超上限
    }

    @Test
    void assertValidDryRunForCreationEntry() {
        ReportParamExpander.assertValid(Map.of("d", "{{yesterday}}"));   // 合法不抛
        assertThrows(BizException.class,
                () -> ReportParamExpander.assertValid(Map.of("d", "{{bad_token}}")));
        ReportParamExpander.assertValid(null);   // null 宽容（无参数）
    }
}
