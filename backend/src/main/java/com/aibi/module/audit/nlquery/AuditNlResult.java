package com.aibi.module.audit.nlquery;

import java.util.List;

/**
 * 审计 NL 查询响应（T11-3）：槽位回显（可改后走既有人工检索复搜）+ 融合行。
 *
 * @param slots    清洗后的槽位；{@code degraded} 时为 null
 * @param degraded LLM 未配置/异常/JSON 不可解析 → true（HTTP 仍 200，前端回落人工筛选表单）
 * @param total    融合行数（截断后的实际返回数）
 * @param rows     统一投影行，时间倒序、上限由调用方 cap
 */
public record AuditNlResult(AuditQuerySlots slots, boolean degraded, long total, List<AuditNlRow> rows) {

    /** LLM 解析失败的静默降级体：不报错、不带条件、前端提示改用筛选表单。 */
    public static AuditNlResult degradedResult() {
        return new AuditNlResult(null, true, 0, List.of());
    }
}
