package com.aibi.module.audit.nlquery;

import java.util.List;

/**
 * 审计 NL 查询槽位（T11-3）：LLM 输出、接口回显、前端可改三段共用同一结构。
 *
 * <p><b>为什么是槽位而不是 SQL</b>：审计检索的过滤维度是有限闭集（时间/用户/动作/结果/模块/关键词），
 * LLM 只需把自然语言压成这段 JSON，实际查询全部走既有参数化 Mapper——注入面为零、结果列确定、
 * 解析结果可当场回显修改（改完用既有人工检索端点复搜）。LLM 幻觉出的非法动作码/结果值由
 * {@link AuditSlotsSanitizer} 闭集校验剔除，绝不带病下查。
 *
 * @param timeFrom 起始时间，格式 {@code yyyy-MM-dd HH:mm:ss}（与审计检索端点口径一致），可空
 * @param timeTo   截止时间，格式同上，可空
 * @param username 查询人（用户名或昵称原样字符串；审计侧按 uid 解析 / LIKE 匹配），可空
 * @param action   动作码（{@link AuditNlDictionary#ACTIONS} 闭集），可空
 * @param result   结果码（OK/DENIED/FAILED），可空
 * @param module   操作日志模块关键词（operation_log.module LIKE），可空
 * @param keyword  对象关键词（问句 / URI / 资源 LIKE），可空
 * @param sources  检索源子集（AUDIT=权限事件、OPS=操作日志、QUERY=问数历史），空=全查
 * @param note     给使用者的解析说明（部门维度忽略、动作码丢弃、降级提示等），可空
 */
public record AuditQuerySlots(
        String timeFrom,
        String timeTo,
        String username,
        String action,
        String result,
        String module,
        String keyword,
        List<String> sources,
        String note
) {
}
