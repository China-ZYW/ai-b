package com.aibi.module.chat.mapper;

import com.aibi.module.chat.entity.TermMap;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务术语词典 Mapper（表 {@code term_map} 为租户隔离表，tenant_id 由拦截器自动注入）。
 */
@Mapper
public interface TermMapMapper extends BaseMapper<TermMap> {
}
