package com.aibi.common.tenant;

/**
 * 套餐类型，决定默认隔离模式、配额与组织协同上限（见《SaaS 多租户与数据源接入设计》3.1 / 10、
 * 《角色权限体系与组织协同设计》8.4）。
 *
 * <p>免费 / 专业 / 企业版均共享元数据库（POOL）+ tenant_id 隔离；独立库 / 私有化部署仅旗舰版可选。
 *
 * <p>三个 {@code -1} 一律表示"不限"。这里的值是<b>默认值</b>而非硬上限：
 * {@code tenant.seat_limit} / {@code tenant.datasource_limit} 落库后可由平台运营按合同单独调整
 * （企业版常见），判定一律读库里的值，本枚举只在"新建组织"与"存量回填"时提供初值。
 *
 * <p>{@code mcpRatePerUser/PerTenant} 为 MCP 对外调用的每分钟速率档（形成集成层差异），
 * {@code defaultDatasourceLimit} 为数据源数量上限初值（根除"无限数据源"超卖风险）。
 */
public enum PlanType {

    /** 免费版：共享库 + tenant_id 隔离，配额最小；个人版即此档（单人组织 + 仅默认团队）。 */
    FREE(IsolationMode.POOL, 3, 1, 0, 10, 10, 1),

    /** 专业版：共享库 + tenant_id 隔离，配额更高，可建多团队与少量自定义角色。 */
    PRO(IsolationMode.POOL, 20, 10, 5, 60, 60, 5),

    /** 企业版：共享池 + tenant_id 隔离，席位与问数均不限（按合同可设具体值），支持私有化部署。 */
    ENTERPRISE(IsolationMode.POOL, -1, -1, -1, 300, 300, -1);

    private final IsolationMode defaultIsolationMode;
    private final int defaultSeatLimit;
    private final int teamLimit;
    private final int customRoleLimit;
    private final int mcpRatePerUser;
    private final int mcpRatePerTenant;
    private final int defaultDatasourceLimit;

    PlanType(IsolationMode defaultIsolationMode, int defaultSeatLimit, int teamLimit, int customRoleLimit,
             int mcpRatePerUser, int mcpRatePerTenant, int defaultDatasourceLimit) {
        this.defaultIsolationMode = defaultIsolationMode;
        this.defaultSeatLimit = defaultSeatLimit;
        this.teamLimit = teamLimit;
        this.customRoleLimit = customRoleLimit;
        this.mcpRatePerUser = mcpRatePerUser;
        this.mcpRatePerTenant = mcpRatePerTenant;
        this.defaultDatasourceLimit = defaultDatasourceLimit;
    }

    public IsolationMode defaultIsolationMode() {
        return defaultIsolationMode;
    }

    /** 席位默认上限（-1=不限）。3 席位是免费版的增长钩子，不是"够用"的容量。 */
    public int defaultSeatLimit() {
        return defaultSeatLimit;
    }

    /** 可建团队数上限（-1=不限）；免费版为 1，即只有回填出来的默认团队。 */
    public int teamLimit() {
        return teamLimit;
    }

    /** 可建自定义角色数上限（-1=不限）；免费版为 0，即只能用内置角色。 */
    public int customRoleLimit() {
        return customRoleLimit;
    }

    /** MCP 调用每分钟速率（按用户）；{@code <=0} 表示不限。 */
    public int mcpRatePerUser() {
        return mcpRatePerUser;
    }

    /** MCP 调用每分钟速率（按租户）；{@code <=0} 表示不限。 */
    public int mcpRatePerTenant() {
        return mcpRatePerTenant;
    }

    /** 数据源数量默认上限（-1=不限）；免费版 1、专业版 5，根除"无限数据源"超卖。 */
    public int defaultDatasourceLimit() {
        return defaultDatasourceLimit;
    }

    /** 当前团队数是否已达上限（{@code limit<0} 表示不限）。 */
    public boolean teamLimitReached(int currentTeamCount) {
        return teamLimit >= 0 && currentTeamCount >= teamLimit;
    }

    /** 当前自定义角色数是否已达上限（{@code limit<0} 表示不限）。 */
    public boolean customRoleLimitReached(int currentRoleCount) {
        return customRoleLimit >= 0 && currentRoleCount >= customRoleLimit;
    }

    /** 容错解析：未知或空值回退到 FREE。 */
    public static PlanType parse(String value) {
        if (value == null || value.isBlank()) {
            return FREE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FREE;
        }
    }
}
