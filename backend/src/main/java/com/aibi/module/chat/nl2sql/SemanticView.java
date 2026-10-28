package com.aibi.module.chat.nl2sql;

import com.aibi.module.semantic.entity.SemanticMeta;

import java.util.Map;

/**
 * 语义视图（配置化升级 V2）：某数据源"表级语义 + 列级语义"的只读快照，
 * 由 {@code SemanticService} 构建并缓存，供 {@link SchemaLinker} 打分与 Prompt 语义段落消费。
 *
 * <p>放在 nl2sql 包内以避免 chat ↔ semantic 的包循环依赖（semantic 服务层引用本记录，反向仅引用实体）。
 *
 * @param tables 表名 → 表级语义行
 * @param columns 表名 → (列名 → 列级语义行)
 */
public record SemanticView(Map<String, SemanticMeta> tables,
                           Map<String, Map<String, SemanticMeta>> columns) {

    public static SemanticView empty() {
        return new SemanticView(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return tables.isEmpty() && columns.isEmpty();
    }
}
