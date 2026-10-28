package com.aibi.module.drift.mapper;

import com.aibi.module.drift.entity.SchemaSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 结构变更感知快照 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code schema_snapshot} 为租户隔离表，通用 CRUD 自动注入 {@code tenant_id}。
 */
public interface SchemaSnapshotMapper extends BaseMapper<SchemaSnapshot> {
}
