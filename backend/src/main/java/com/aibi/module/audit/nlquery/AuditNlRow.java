package com.aibi.module.audit.nlquery;

import java.time.LocalDateTime;

/**
 * 审计 NL 查询统一投影行（T11-3）：三源（权限审计/操作日志/问数历史）收敛成同一形状。
 *
 * <p>刻意只带<b>投影字段</b>：AUDIT 行不含 detail 原文、QUERY 行不含 result_json 快照原文
 * （只有 {@code resultSummary} 摘要，拍板 P2 的收敛口径）。字段可空按源而异，前端按 {@code source} 渲染。
 *
 * @param occurredAt    事件时间（融合排序键）
 * @param source        检索源（AUDIT/OPS/QUERY，见 {@link AuditNlDictionary}）
 * @param tenantId      租户 id（平台域跨租户检索时有值）
 * @param tenantName    租户名（平台域展示用；组织域为 null——本来就都是本组织）
 * @param username      操作人/查询人展示名
 * @param summary       一行人话（如 "GET /api/chat/query → 200 (35ms)"、"本月GMV [metric OK 12行]"）
 * @param action        动作码/方法（QUERY 行为技能码）
 * @param result        结果码（OK/DENIED/FAILED；OPS 行放 HTTP 状态语义）
 * @param resultSummary 问数结果快照摘要（仅 QUERY 行，拍板 P2；老记录无快照为 null）
 */
public record AuditNlRow(
        LocalDateTime occurredAt,
        String source,
        Long tenantId,
        String tenantName,
        String username,
        String summary,
        String action,
        String result,
        String resultSummary
) {
}
