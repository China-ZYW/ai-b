package com.aibi.common.tenant;

/**
 * 数据范围（行级可见性收敛单位），见《角色权限体系与组织协同设计》4.3 / 3.6⑦。
 *
 * <p>与租户隔离<b>正交</b>：{@code tenant_id} 由 {@code TenantLineInnerInterceptor} 强制注入，
 * 本枚举在其之上再按"团队/本人"收敛。多角色并存时取<b>最宽</b>（兼任 ADMIN 与 TEAM_VIEWER 的人
 * 应按 ADMIN 看全域），由 {@link #widest} 实现。
 *
 * <p>{@code width} 仅用于内部比较，不代表业务语义；新增枚举须同时扩展
 * {@code MybatisPlusConfig.AibiDataScopeHandler} 的 SQL 改写分支，否则该范围会退化为"不追加条件"（越权）。
 */
public enum DataScope {

    /** 全平台跨租户：仅 {@code tenant_id=0} 的平台身份可用，且必须配合 {@code admin:*} 权限点。 */
    ALL(5),

    /** 组织内全可见：不追加条件（{@code tenant_id} 已由租户拦截器注入）。 */
    TENANT(4),

    /** 本团队及全部子团队 + 组织级公开资源 + 本人创建的资源。 */
    TEAM_AND_CHILD(3),

    /** 仅当前团队 + 组织级公开资源 + 本人创建的资源。 */
    TEAM(2),

    /** 仅本人创建的资源。 */
    SELF(1),

    /** 逐资源授权：走 {@code resource_acl} 关联子查询，无默认放行。 */
    CUSTOM(0);

    private final int width;

    DataScope(int width) {
        this.width = width;
    }

    /** 宽窄序数值（越大越宽），仅用于 {@link #widest} 比较。 */
    public int width() {
        return width;
    }

    /** 该范围是否需要"当前团队"上下文（缺失时应返回 40306 而非放行）。 */
    public boolean requiresTeam() {
        return this == TEAM || this == TEAM_AND_CHILD;
    }

    /** 是否为跨租户范围（平台域专用，租户身份持有它属越权配置）。 */
    public boolean isCrossTenant() {
        return this == ALL;
    }

    /** 取两者中较宽的范围；任一为空则返回另一个。 */
    public static DataScope widest(DataScope a, DataScope b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.width >= b.width ? a : b;
    }

    /** 容错解析：未知或空值回退到 {@link #SELF}（最窄，安全默认）。 */
    public static DataScope parse(String value) {
        if (value == null || value.isBlank()) {
            return SELF;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SELF;
        }
    }
}
