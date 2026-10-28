package com.aibi.common.config;

import org.springframework.stereotype.Component;

/**
 * RBAC 播种编排壳（T8.1 补账拆分，2026-10-24）：原 700 行巨类按职责三分——
 * {@link RbacSeedDict}（权限矩阵/角色字典数据 + 格式自检）、
 * {@link RbacDictSeeder}（role/permission/role_permission 三字典逐条补齐 + perm_version 抬高）、
 * {@link RbacBackfiller}（§11.2 存量回填 7 步，顺序不可调换）。
 *
 * <p>由 {@link DataSeeder} 在演示租户与账号播种<b>之后</b>调用（order 900）——回填步骤要读到
 * {@code tenant}/{@code app_user} 的既有行，顺序颠倒会让步骤 4/5/7 空跑。
 *
 * <p>对 {@code DataSeeder} 的 {@code instanceof RbacSeeder} 严重域判定保持类名与类型不变；
 * 逐条 insertIfMissing、矩阵派生关联等约定详见 {@link RbacSeedDict} 类注释。
 * 任一步失败由调用方（{@link DataSeeder} 编排壳）统一兜住，不阻断应用启动。
 */
@Component
public class RbacSeeder implements Seeder {

    private final RbacDictSeeder dictSeeder;
    private final RbacBackfiller backfiller;

    public RbacSeeder(RbacDictSeeder dictSeeder, RbacBackfiller backfiller) {
        this.dictSeeder = dictSeeder;
        this.backfiller = backfiller;
    }

    @Override
    public String name() {
        return "rbac-dict/backfill";
    }

    /** 演示域（order 10~80）全部之后：回填步骤要读到刚插入的 tenant/app_user 行。 */
    @Override
    public int order() {
        return 900;
    }

    /** 播种字典 + 回填存量数据（字典在前：回填涉及的角色码须已存在）。 */
    @Override
    public void seed() {
        dictSeeder.seed();
        backfiller.backfill();
    }
}
