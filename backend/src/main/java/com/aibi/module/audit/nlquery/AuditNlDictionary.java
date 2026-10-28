package com.aibi.module.audit.nlquery;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 审计 NL 查询的闭集词表（T11-3）：动作码/结果码/检索源白名单。
 *
 * <p>既是 LLM prompt 的枚举依据，也是 {@link AuditSlotsSanitizer} 的校验依据——两处共用同一份，
 * 杜绝"prompt 让模型产出 X、校验却不认 X"的两张皮。动作码收录 {@code AuditAction} 中
 * <b>会真实产生</b>的码；「查了XX数据」类问法不映射动作码而是收窄 {@link #SOURCE_QUERY}。
 */
public final class AuditNlDictionary {

    private AuditNlDictionary() {
    }

    /** 检索源：权限审计事件（auth_audit_log）。 */
    public static final String SOURCE_AUDIT = "AUDIT";
    /** 检索源：全局操作日志（operation_log）。 */
    public static final String SOURCE_OPS = "OPS";
    /** 检索源：问数历史（query_history，"谁查了什么数据"）。 */
    public static final String SOURCE_QUERY = "QUERY";

    /** 检索源白名单（缺省全查三源）。 */
    public static final Set<String> SOURCES = Set.of(SOURCE_AUDIT, SOURCE_OPS, SOURCE_QUERY);

    /** 结果码白名单，与 {@code AuditAction.RESULT_*} 对齐。 */
    public static final Set<String> RESULTS = Set.of("OK", "DENIED", "FAILED");

    /** 自由文本槽位长度上限（超长截断，防 LLM 把整句话塞进关键词）。 */
    public static final int TEXT_MAX = 100;

    /**
     * 动作码白名单：仅收录已埋点会真实产生的码（另收 AUDIT_NL_QUERY 自身，AI 查询也留痕）。
     * 顺序即 prompt 展示顺序：认证 → 鉴权 → 审计自审 → 成员/角色 → 平台 → 预警/集成。
     */
    public static final Set<String> ACTIONS = unmodifiableOrdered(
            "LOGIN", "LOGOUT", "LOGIN_FAILED",
            "PERM_CHECK_DENIED", "DATA_SCOPE_DENIED", "ACL_DENIED",
            "AUDIT_EXPORT", "AUDIT_NL_QUERY",
            "MEMBER_REMOVE", "ROLE_GRANT", "ROLE_REVOKE", "PASSWORD_RESET", "OWNER_TRANSFER",
            "CUSTOM_ROLE_CREATE", "CUSTOM_ROLE_UPDATE", "CUSTOM_ROLE_DELETE", "CUSTOM_ROLE_PERM_UPDATE",
            "DATA_ACL_SET", "DATA_ACL_CLEAR",
            "INVITE_CREATE", "INVITE_REVOKE", "INVITE_ACCEPT",
            "PLATFORM_ROLE_GRANT", "PLATFORM_ROLE_REVOKE", "SEAT_CHANGE", "TENANT_DISABLE",
            "ALERT_RULE_CREATE", "ALERT_RULE_UPDATE", "ALERT_RULE_DELETE", "ALERT_FIRED",
            "ORG_SWITCH", "MCP_TOOL_CALL", "EMBED_QUERY");

    /** prompt 里的动作码速查表（码 → 中文语义），帮助模型把"登录失败/导出/移除成员"映射到码。 */
    public static final List<String> ACTION_HINTS = List.of(
            "LOGIN=登录, LOGOUT=登出, LOGIN_FAILED=登录失败",
            "PERM_CHECK_DENIED=权限被拒, ACL_DENIED=越权被拒",
            "AUDIT_EXPORT=导出审计日志, MEMBER_REMOVE=移除成员, ROLE_GRANT/ROLE_REVOKE=角色授予/回收",
            "PASSWORD_RESET=重置口令, OWNER_TRANSFER=转让所有权, TENANT_DISABLE=停用租户, SEAT_CHANGE=配额变更",
            "INVITE_CREATE/INVITE_REVOKE/INVITE_ACCEPT=邀请相关, ALERT_RULE_CREATE/UPDATE/DELETE=预警规则",
            "DATA_ACL_SET/CLEAR=数据授权, ORG_SWITCH=组织切换, MCP_TOOL_CALL=外部工具调用, EMBED_QUERY=嵌入问数");

    private static Set<String> unmodifiableOrdered(String... codes) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(codes)));
    }
}
