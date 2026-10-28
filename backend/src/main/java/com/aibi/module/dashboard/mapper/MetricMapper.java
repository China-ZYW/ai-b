package com.aibi.module.dashboard.mapper;

import com.aibi.module.dashboard.entity.Metric;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 指标 Mapper（由 {@code @MapperScan} 扫描注册）。{@code metric} 为租户隔离表，
 * CRUD 由租户拦截器自动注入 {@code tenant_id}，天然按当前租户 + 上下文隔离。
 */
public interface MetricMapper extends BaseMapper<Metric> {
}
