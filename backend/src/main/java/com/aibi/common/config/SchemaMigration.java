package com.aibi.common.config;

import com.aibi.common.migration.EarlyMigrationSteps;
import com.aibi.common.migration.LateMigrationSteps;
import com.aibi.common.migration.MigrationContext;
import com.aibi.common.migration.MigrationStep;
import com.aibi.common.migration.MidMigrationSteps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本化 Schema 迁移器（编排壳）：为"已存在的元数据库"幂等补齐结构演进（增列 / 补索引 / 数据回填）。
 *
 * <p>背景：本项目用 {@code spring.sql.init} 执行 {@code db/metadata-schema.sql}（全部 {@code CREATE TABLE IF NOT EXISTS}），
 * 新表对新老库都会自动创建；但"老表新增列"在 MySQL 下无 {@code ADD COLUMN IF NOT EXISTS} 语法，
 * 故以迁移注册表 {@code schema_migration} 记录已应用迁移，按注册顺序幂等执行，
 * 老库（如联调 {@code aibi_meta}）启动即自动演进到最新结构。
 *
 * <p>T8.2 拆分（2026-10-24）：44 个步骤实现按原序三段平移至
 * {@link EarlyMigrationSteps}（V1~V15）/ {@link MidMigrationSteps}（V16~V30 段内 18 条）/
 * {@link LateMigrationSteps}（V31~V41），幂等助手收敛到 {@link MigrationContext}；
 * 本类只保留启动编排——记账表建表、逐步 try/catch 容错、步骤耗时日志与汇总。
 *
 * <p>扩展方式：新增结构变更时按序追加到对应段的末尾（key 建议 V{年月}_{语义}，入库后不可改名），
 * 实现体仅用幂等助手；执行成功才登记，失败仅告警不阻断启动、下次启动重试。
 *
 * <p>执行顺序：{@code @Order(HIGHEST_PRECEDENCE)} 保证早于 {@link DataSeeder}，使播种逻辑能安全引用新列新表。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final JdbcTemplate metaJdbc;
    private final List<MigrationStep> steps;

    public SchemaMigration(JdbcTemplate metaJdbc) {
        this.metaJdbc = metaJdbc;
        MigrationContext ctx = new MigrationContext(metaJdbc);
        // 拼接顺序 = 原 LinkedHashMap 全局顺序，段间依赖（V26b 在 V26 之后等）由此保持，禁止调整。
        List<MigrationStep> all = new ArrayList<>();
        all.addAll(EarlyMigrationSteps.steps(ctx));
        all.addAll(MidMigrationSteps.steps(ctx));
        all.addAll(LateMigrationSteps.steps(ctx));
        this.steps = List.copyOf(all);
    }

    @Override
    public void run(ApplicationArguments args) {
        long t0 = System.currentTimeMillis();
        try {
            metaJdbc.execute("CREATE TABLE IF NOT EXISTS schema_migration ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "migration_key VARCHAR(64) NOT NULL, "
                    + "applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), UNIQUE KEY uk_migration_key (migration_key)) "
                    + "ENGINE = InnoDB DEFAULT CHARSET = utf8mb4");
        } catch (Exception e) {
            // 记账表都建不起来 = 元库整体不可用，此时逐步迁移无意义，整套跳过
            log.warn("Schema 迁移跳过（记账表未就绪，元库可能不可用）: {}", e.getMessage());
            return;
        }
        // 逐步容错：单个步骤失败只跳过该步（未记账 → 下次启动重试），不得中断后续步骤。
        // 旧实现为外层单 try/catch，一步抛错会把其后所有步骤静默级联跳过，
        // 导致新环境（dev 零启动）schema 落后数十步且仅一条 WARN 难以察觉。
        int failed = 0;
        int applied = 0;
        for (MigrationStep m : steps) {
            try {
                if (applyIfAbsent(m)) {
                    applied++;
                }
            } catch (Exception e) {
                failed++;
                log.error("Schema 迁移步骤失败（跳过并继续，下次启动重试）: key={} cause={}",
                        m.key(), e.getMessage());
            }
        }
        // 步骤级可观测：新 apply 的步骤在 applyIfAbsent 内逐条 info（含本条耗时）；
        // 此处汇总首次演进耗时（老库全量命中记账时 applied=0，仅一行 debug 级以下开销）。
        log.info("Schema 迁移完成：{} 步（新应用 {}，失败 {}），耗时 {} ms",
                steps.size(), applied, failed, System.currentTimeMillis() - t0);
        if (failed > 0) {
            log.warn("Schema 迁移有 {} 步未应用，检查上方 error 日志", failed);
        }
    }

    /** 记账查询 → 执行 → 登记；返回本次是否真正执行（已登记步骤静默跳过返回 false）。 */
    private boolean applyIfAbsent(MigrationStep m) {
        Integer count = metaJdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE migration_key = ?", Integer.class, m.key());
        if (count != null && count > 0) {
            return false;
        }
        long t1 = System.currentTimeMillis();
        m.up().run();
        metaJdbc.update("INSERT INTO schema_migration(migration_key) VALUES (?)", m.key());
        log.info("Schema 迁移已应用: {}（{} ms）", m.key(), System.currentTimeMillis() - t1);
        return true;
    }
}
