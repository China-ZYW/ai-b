package com.aibi.module.audit.nlquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计 NL 查询解析层（T11-3 K1）纯逻辑单测：不起 Spring、不连库、零 LLM——
 * 钉死时间短语确定性解析（含跨日/周界/中文数/非法日期不臆造）、槽位闭集清洗
 * （幻觉动作码剔除、时间格式与先后纠正、sources 归一兜底）与 prompt 关键要素。
 * 统一时钟：2026-10-25（周日）14:30。
 */
class AuditNlQueryParsingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 14, 30, 0);

    private static AuditQuerySlots raw(String from, String to, String username, String action,
                                       String result, List<String> sources, String note) {
        return new AuditQuerySlots(from, to, username, action, result, null, null, sources, note);
    }

    // ==================== 时间短语确定性解析 ====================

    @Test
    @DisplayName("今天/昨天/前天：整日闭窗")
    void dayPhrases() {
        AuditTimePhrases.Window today = AuditTimePhrases.parse("今天谁查了订单", NOW);
        assertEquals("2026-10-25 00:00:00", today.fromText());
        assertEquals("2026-10-25 23:59:59", today.toText());
        assertEquals("2026-10-24 00:00:00", AuditTimePhrases.parse("昨日登录失败", NOW).fromText());
        assertEquals("2026-10-23 00:00:00", AuditTimePhrases.parse("前天谁导出过", NOW).fromText());
    }

    @Test
    @DisplayName("最近N天：含今天回溯，阿拉伯数与中文数等价")
    void relativeDays() {
        AuditTimePhrases.Window w7 = AuditTimePhrases.parse("最近7天有谁用过", NOW);
        assertEquals("2026-10-19 00:00:00", w7.fromText()); // 今天往前数 6 天
        assertEquals("2026-10-25 23:59:59", w7.toText());
        assertEquals(w7.from(), AuditTimePhrases.parse("最近七天有谁用过", NOW).from());
        assertEquals("2026-10-24 00:00:00", AuditTimePhrases.parse("近两天谁登录过", NOW).fromText());
        assertEquals("2026-10-19 00:00:00", AuditTimePhrases.parse("最近一周谁导出过", NOW).fromText());
        assertEquals("2026-09-26 00:00:00", AuditTimePhrases.parse("过去一个月谁删过规则", NOW).fromText());
    }

    @Test
    @DisplayName("本周/上周/本月/上月：自然周边界（周一起算）")
    void calendarWindows() {
        AuditTimePhrases.Window wk = AuditTimePhrases.parse("本周登录过谁", NOW);
        assertEquals("2026-10-19 00:00:00", wk.fromText()); // 2026-10-25 是周日，周一为 10-19
        assertEquals("2026-10-25 23:59:59", wk.toText());
        AuditTimePhrases.Window lw = AuditTimePhrases.parse("上周谁查过销售", NOW);
        assertEquals("2026-10-12 00:00:00", lw.fromText());
        assertEquals("2026-10-18 23:59:59", lw.toText());
        AuditTimePhrases.Window m = AuditTimePhrases.parse("本月导出审计", NOW);
        assertEquals("2026-10-01 00:00:00", m.fromText());
        assertEquals("2026-10-31 23:59:59", m.toText());
        AuditTimePhrases.Window lm = AuditTimePhrases.parse("上月移除过成员", NOW);
        assertEquals("2026-09-01 00:00:00", lm.fromText());
        assertEquals("2026-09-30 23:59:59", lm.toText());
    }

    @Test
    @DisplayName("绝对日期：连字符/年月日/缺年份按当年；非法日期不臆造")
    void absoluteDates() {
        assertEquals("2026-10-01 00:00:00", AuditTimePhrases.parse("2026-10-01 谁查过", NOW).fromText());
        assertEquals("2026-10-25 23:59:59", AuditTimePhrases.parse("2026年10月25日的事件", NOW).toText());
        assertEquals("2026-03-05 00:00:00", AuditTimePhrases.parse("3月5日谁登录过", NOW).fromText());
        assertNull(AuditTimePhrases.parse("2月30日的导出", NOW)); // 非法日期视为未命中
    }

    @Test
    @DisplayName("未命中与退化：无时间短语返回 null；num 支持十一~九十九")
    void missesAndNum() {
        assertNull(AuditTimePhrases.parse("张三是谁", NOW));
        assertNull(AuditTimePhrases.parse(null, NOW));
        assertEquals(15, AuditTimePhrases.num("十五"));
        assertEquals(20, AuditTimePhrases.num("二十"));
        assertEquals(23, AuditTimePhrases.num("二十三"));
        assertEquals(7, AuditTimePhrases.num("7"));
        assertNull(AuditTimePhrases.num("百"));
    }

    // ==================== 槽位闭集清洗 ====================

    @Test
    @DisplayName("null 输入 → 三源全查的空槽位（绝不产出可查穿全库的野条件）")
    void nullBecomesSafeDefault() {
        AuditQuerySlots s = AuditSlotsSanitizer.sanitize(null);
        assertNull(s.action());
        assertEquals(List.of("AUDIT", "OPS", "QUERY"), s.sources());
        assertNull(s.note());
    }

    @Test
    @DisplayName("幻觉动作码剔除并说明；合法码大小写归一放行")
    void actionWhitelist() {
        AuditQuerySlots bad = AuditSlotsSanitizer.sanitize(
                raw(null, null, null, "DROP_TABLE", null, null, null));
        assertNull(bad.action());
        assertTrue(bad.note().contains("DROP_TABLE"));
        AuditQuerySlots ok = AuditSlotsSanitizer.sanitize(
                raw(null, null, null, "login_failed", null, null, null));
        assertEquals("LOGIN_FAILED", ok.action());
        assertNull(ok.note());
    }

    @Test
    @DisplayName("结果码闭集：SUCCESS 非法、小写 ok 归一")
    void resultWhitelist() {
        assertTrue(AuditSlotsSanitizer.sanitize(raw(null, null, null, null, "SUCCESS", null, null)).result() == null);
        assertEquals("OK", AuditSlotsSanitizer.sanitize(raw(null, null, null, null, "ok", null, null)).result());
    }

    @Test
    @DisplayName("非法时间格式丢弃并说明；颠倒窗口纠正为正常区间")
    void timeGuards() {
        AuditQuerySlots bad = AuditSlotsSanitizer.sanitize(raw("2026-10-25", null, null, null, null, null, null));
        assertNull(bad.timeFrom());
        assertTrue(bad.note().contains("起始时间"));
        AuditQuerySlots swapped = AuditSlotsSanitizer.sanitize(
                raw("2026-10-25 00:00:00", "2026-10-01 00:00:00", null, null, null, null, null));
        assertEquals("2026-10-01 00:00:00", swapped.timeFrom());
        assertEquals("2026-10-25 00:00:00", swapped.timeTo());
    }

    @Test
    @DisplayName("sources 大写去重取交集；全非法回落三源；空白用户名置 null；超长关键词截断")
    void textGuards() {
        assertEquals(List.of("QUERY"), AuditSlotsSanitizer.sanitize(
                raw(null, null, null, null, null, List.of("query", "QUERY", "BOGUS"), null)).sources());
        assertEquals(3, AuditSlotsSanitizer.sanitize(
                raw(null, null, null, null, null, List.of(), null)).sources().size());
        assertNull(AuditSlotsSanitizer.sanitize(raw(null, null, "   ", null, null, null, null)).username());
        String longKw = "订".repeat(AuditNlDictionary.TEXT_MAX + 50);
        AuditQuerySlots capped = AuditSlotsSanitizer.sanitize(new AuditQuerySlots(
                null, null, null, null, null, null, longKw, null, null));
        assertEquals(AuditNlDictionary.TEXT_MAX, capped.keyword().length());
    }

    @Test
    @DisplayName("原 note 保留并与清洗追加项分号拼接")
    void noteMerge() {
        AuditQuerySlots s = AuditSlotsSanitizer.sanitize(
                raw(null, null, null, "BOGUS", null, null, "部门维度暂不支持，已忽略"));
        assertNotNull(s.note());
        assertTrue(s.note().startsWith("部门维度暂不支持"));
        assertTrue(s.note().contains("；已忽略无法识别的动作"));
    }

    // ==================== prompt 关键要素 ====================

    @Test
    @DisplayName("prompt 含当前时间、动作码白名单、原问句与纯 JSON 约束")
    void promptComposition() {
        String p = AuditNlPrompts.build("今天运营部谁查了订单数据", NOW);
        assertTrue(p.contains("2026-10-25 14:30:00"));
        assertTrue(p.contains("LOGIN_FAILED"));
        assertTrue(p.contains("只输出纯 JSON"));
        assertTrue(p.contains("今天运营部谁查了订单数据"));
        assertTrue(p.contains("QUERY"));
        assertTrue(p.contains("部门")); // 部门维度忽略规则必须在 prompt 里显式声明
    }
}
