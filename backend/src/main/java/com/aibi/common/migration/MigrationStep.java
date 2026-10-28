package com.aibi.common.migration;

/**
 * 单个版本化 Schema 迁移步骤：记账 key + 幂等执行体。
 *
 * <p>key 为 {@code schema_migration.migration_key} 唯一记账值——一旦成功执行即永久登记，
 * 此后启动直接跳过；失败则不记账，下次启动重试。
 *
 * <p>顺序语义：注册表为有序列表（{@link java.util.LinkedHashMap} 时代的原序完整保留），
 * 段间存在依赖（如 V26b 补 table_name 列依赖 V26 先建四张 ACL 表），调整分段归属时
 * 必须维持全局相对顺序不变。
 *
 * @param key 迁移记账键（V{年}_{月}_{语义}，入库后不可改名）
 * @param up  幂等执行体：只允许使用 {@link MigrationContext} 的幂等助手或
 *            {@code CREATE TABLE IF NOT EXISTS} / dup-safe 判定后的写操作
 */
public record MigrationStep(String key, Runnable up) {
}
