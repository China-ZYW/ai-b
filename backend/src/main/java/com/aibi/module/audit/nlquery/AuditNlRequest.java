package com.aibi.module.audit.nlquery;

/**
 * 审计智能查询请求体（T11-3）：只带一句自然语言。
 *
 * <p>租户范围<b>不在</b>请求体里——组织域由 Controller 从登录上下文钉死、平台域固定跨租户，
 * 与 {@code OrgAuditController} 既有"不接受任何 tenantId 入参"的防越权口径一致。
 *
 * @param question 自然语言问句（空则拒绝；超 500 字符截断，仅用于解析与留痕）
 */
public record AuditNlRequest(String question) {

    /** 问句长度硬上限（超长截断而非拒绝：尾部多为语气词，不影响槽位质量）。 */
    public static final int QUESTION_MAX = 500;

    public static String clamp(String question) {
        if (question == null) {
            return null;
        }
        String q = question.trim();
        return q.length() <= QUESTION_MAX ? q : q.substring(0, QUESTION_MAX);
    }
}
