package com.aibi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 启动播种编排壳（T8.1，2026-10-24）：原 910 行巨类按域拆为 {@link Seeder} 实现——
 * {@link TenantSeeder}(10) → {@link LlmConfigSeeder}(20) → {@link DashboardTemplateSeeder}(30) →
 * {@link ChartRuleSeeder}(40) → {@link ThemeSeeder}(50) → {@link SysConfigSeeder}(60) →
 * {@link ConfigCatalogSeeder}(70) → {@link UserManualSeeder}(80) → {@link RbacSeeder}(900)。
 *
 * <p>两类失败语义保持原设计意图并显式区分：
 * <ul>
 *   <li><b>演示域</b>失败仅告警不阻断启动（"元数据表未就绪"提示原样保留）——
 *       应用必须能起来，否则连查日志排障都做不到；各域幂等、下次启动自动重试。
 *       与拆分前"共享一个 try/catch、一失败连带跳过其余"相比改为逐域兜住：
 *       容错更宽（与 SchemaMigration 逐步容错同因），代价是表未就绪时日志从 1 行变多行，均为同一 message；</li>
 *   <li><b>RBAC 失败单独报错</b>：权限体系不可用会导致全站 403，比"缺一个演示数据源"严重得多，
 *       不能被"演示数据播种跳过"这条提示误导排查方向。启动后 {@code aibi.rbac.enabled=false}
 *       可临时退回二元判定保业务可用（11.1.4）。</li>
 * </ul>
 *
 * <p>执行前提不变：spring.sql.init 建表与 SchemaMigration（@Order 最高优先级）先于本 Runner。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final List<Seeder> seeders;

    public DataSeeder(List<Seeder> seeders) {
        this.seeders = seeders.stream()
                .sorted(Comparator.comparingInt(Seeder::order))
                .toList();
    }

    @Override
    public void run(String... args) {
        long t0 = System.currentTimeMillis();
        int failed = 0;
        for (Seeder s : seeders) {
            try {
                s.seed();
            } catch (Exception e) {
                failed++;
                if (s instanceof RbacSeeder) {
                    log.error("RBAC 字典播种/存量回填失败（权限体系可能不可用，请检查 role/permission/role_permission"
                            + " 表是否已建）: {}", e.getMessage(), e);
                } else {
                    log.warn("演示数据播种跳过 [{}]（元数据表可能未就绪，请先执行 db/metadata-schema.sql）: {}",
                            s.name(), e.getMessage());
                }
            }
        }
        log.info("启动播种完成：{} 个域（失败 {}），耗时 {} ms", seeders.size(), failed, System.currentTimeMillis() - t0);
    }
}
