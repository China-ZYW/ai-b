package com.aibi.module.audit.nlquery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 槽位闭集清洗（T11-3）：LLM 产物进入检索前的唯一通道。
 *
 * <p>LLM 会幻觉动作码、把整句话塞进关键词、甚至给出颠倒的时间窗——本类把一切不可信输出收敛为
 * 可执行的安全子集：非法值<b>剔除并说明</b>（note 回显给用户），而不是带病下查或直接报错。
 * 纯函数、零依赖，方便穷举单测。
 */
public final class AuditSlotsSanitizer {

    private AuditSlotsSanitizer() {
    }

    /** 清洗：trim/置空、闭集校验、时间格式与先后校验、sources 归一；产出可安全执行的槽位。 */
    public static AuditQuerySlots sanitize(AuditQuerySlots raw) {
        if (raw == null) {
            return new AuditQuerySlots(null, null, null, null, null, null, null,
                    List.of(AuditNlDictionary.SOURCE_AUDIT, AuditNlDictionary.SOURCE_OPS,
                            AuditNlDictionary.SOURCE_QUERY), null);
        }
        List<String> notes = new ArrayList<>();
        appendNote(notes, raw.note());

        String from = trim(raw.timeFrom());
        String to = trim(raw.timeTo());
        from = validTs(from, "起始时间", notes);
        to = validTs(to, "截止时间", notes);
        if (from != null && to != null && from.compareTo(to) > 0) {
            String t = from;
            from = to;
            to = t; // 颠倒的窗口纠正为正常区间，不悄悄丢弃
        }

        String action = trim(raw.action());
        if (action != null) {
            action = action.toUpperCase(Locale.ROOT);
            if (!AuditNlDictionary.ACTIONS.contains(action)) {
                appendNote(notes, "已忽略无法识别的动作「" + raw.action() + "」");
                action = null;
            }
        }

        String result = trim(raw.result());
        if (result != null) {
            result = result.toUpperCase(Locale.ROOT);
            if (!AuditNlDictionary.RESULTS.contains(result)) {
                appendNote(notes, "已忽略无法识别的结果状态「" + raw.result() + "」");
                result = null;
            }
        }

        // sources：大写去重取白名单交集；空/非法全被滤掉时恢复为三源全查
        List<String> sources = null;
        if (raw.sources() != null) {
            Set<String> picked = new LinkedHashSet<>();
            for (String s : raw.sources()) {
                String u = trim(s);
                if (u != null && AuditNlDictionary.SOURCES.contains(u.toUpperCase(Locale.ROOT))) {
                    picked.add(u.toUpperCase(Locale.ROOT));
                }
            }
            if (!picked.isEmpty()) {
                sources = List.copyOf(picked);
            }
        }
        if (sources == null) {
            sources = List.of(AuditNlDictionary.SOURCE_AUDIT, AuditNlDictionary.SOURCE_OPS,
                    AuditNlDictionary.SOURCE_QUERY);
        }

        return new AuditQuerySlots(from, to,
                cap(trim(raw.username())), action, result,
                cap(trim(raw.module())), cap(trim(raw.keyword())),
                sources, notes.isEmpty() ? null : String.join("；", notes));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cap(String s) {
        return s != null && s.length() > AuditNlDictionary.TEXT_MAX
                ? s.substring(0, AuditNlDictionary.TEXT_MAX) : s;
    }

    /** 时间串必须能按 {@code yyyy-MM-dd HH:mm:ss} 解析；非法则丢弃并 note 说明。 */
    private static String validTs(String ts, String label, List<String> notes) {
        if (ts == null) {
            return null;
        }
        try {
            LocalDateTime.parse(ts, AuditTimePhrases.TS);
            return ts;
        } catch (RuntimeException e) {
            appendNote(notes, "已忽略无法解析的" + label + "「" + ts + "」");
            return null;
        }
    }

    private static void appendNote(List<String> notes, String note) {
        if (note != null && !note.isBlank()) {
            notes.add(note.trim());
        }
    }
}
