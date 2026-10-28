package com.aibi.module.message.mapper;

import com.aibi.module.message.entity.NotifyMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 站内信 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 * {@code notify_message} 为租户隔离表，通用 CRUD 自动注入 {@code tenant_id}。
 */
public interface NotifyMessageMapper extends BaseMapper<NotifyMessage> {
}
