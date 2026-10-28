package com.aibi.module.drift;

import java.util.List;

/**
 * 结构对账 · 一键清理请求。
 *
 * <p>仅携带<b>可删除</b>的孤儿元数据 id 清单（语义行 / 关联 / 四类字段&表级权限），
 * 指标与驾驶舱属于「坏引用高亮 + 跳转修复」范畴、不在此删除。各清单为空或 {@code null} 表示该项不清理。
 * 删除走各 Mapper 的 {@code deleteBatchIds}，由租户拦截器强制追加 {@code tenant_id} 条件，
 * 跨租户 id 会被自然过滤、不会误删他人数据。
 */
public record DriftPruneRequest(
        Long datasourceId,
        List<Long> semanticIds,
        List<Long> relationIds,
        List<Long> userFieldAclIds,
        List<Long> userTableAclIds,
        List<Long> roleFieldAclIds,
        List<Long> roleTableAclIds) {
}
