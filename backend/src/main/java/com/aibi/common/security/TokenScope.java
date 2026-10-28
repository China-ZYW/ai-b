package com.aibi.common.security;

/**
 * 令牌作用域（JWT claim {@code scope}），见《角色权限体系与组织协同设计》6.3 / 9.3。
 *
 * <p>{@link #ASSIST} 是平台 SUPPORT 经租户 OWNER 审批后签发的<b>限时限权影子令牌</b>：
 * 只读、TTL ≤ 2h、全程审计，且被 {@code PermissionAspect} 的硬编码拒绝清单拦住一切写操作与敏感读
 * （如 {@code datasource:config:test} 连通性测试）。前端须在顶栏展示红色"平台协助中"横幅。
 */
public enum TokenScope {

    /** 常规登录令牌。 */
    NORMAL,

    /** 代客协助影子令牌：只读 + 硬拒绝清单 + 全程审计。 */
    ASSIST;

    /** 容错解析：未知或空值回退到 {@link #NORMAL}（存量令牌无此 claim）。 */
    public static TokenScope parse(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
