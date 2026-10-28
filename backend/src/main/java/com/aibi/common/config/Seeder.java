package com.aibi.common.config;

/**
 * 启动播种器契约（T8.1，2026-10-24 自 {@link DataSeeder} 拆分引入）。
 *
 * <p>每个实现负责一个域的幂等播种（"按唯一键补齐"或"表非空即跳过"语义逐类保持，
 * 与原 DataSeeder 内私有方法一一对应）；由 {@link DataSeeder} 按 {@link #order()}
 * 排序逐个执行并<b>单独兜异常</b>——单个域播种失败不连带跳过其余域（与原"共享一个
 * try/catch"相比是放宽容错，与 SchemaMigration 逐步容错同因，全部操作幂等、下次启动可重试）。
 *
 * <p>前提约束不变：本组播种在 spring.sql.init 建表与 SchemaMigration（@Order 最高优先级）
 * 之后执行，实现内可安全引用新列新表。
 */
public interface Seeder {

    /** 域名称，用于启动日志与失败定位。 */
    String name();

    /** 执行顺序，小者先行；演示域整体先于 RBAC（回填要读到刚插入的 tenant/app_user 行）。 */
    default int order() {
        return 100;
    }

    /** 幂等播种：已存在的数据跳过且不覆盖人工编辑；失败直接抛出，由 {@link DataSeeder} 统一兜住。 */
    void seed();
}
