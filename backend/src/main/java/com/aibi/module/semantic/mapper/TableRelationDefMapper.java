package com.aibi.module.semantic.mapper;

import com.aibi.module.semantic.entity.TableRelationDef;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 表关联登记 Mapper（表 {@code table_relation} 为租户隔离表，tenant_id 由拦截器自动注入）。
 */
@Mapper
public interface TableRelationDefMapper extends BaseMapper<TableRelationDef> {
}
