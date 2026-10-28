package com.aibi.module.alert.mapper;

import com.aibi.module.alert.entity.AlertRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 预警触发记录 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 * {@code alert_record} 为租户隔离表，通用 CRUD 自动注入 {@code tenant_id}。
 */
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {
}
