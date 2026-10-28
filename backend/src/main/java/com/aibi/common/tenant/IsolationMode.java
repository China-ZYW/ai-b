package com.aibi.common.tenant;

/**
 * 数据隔离模式（见《SaaS 多租户与数据源接入设计》3.1）。
 *
 * <p>隔离强度与成本正相关：POOL 最省、DATABASE 最强。由 {@link PlanType} 决定默认值，
 * 企业版可按需覆盖为 SCHEMA/DATABASE。
 */
public enum IsolationMode {

    /** 共享库 + tenant_id 行级隔离（免费/专业版）。 */
    POOL,

    /** 同一实例内每租户独立 schema（企业版可选）。 */
    SCHEMA,

    /** 每租户独立库/实例或私有化部署（企业版/私有化）。 */
    DATABASE
}
