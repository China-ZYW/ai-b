package com.aibi.module.semantic.mapper;

import com.aibi.module.semantic.entity.SemanticMeta;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 语义层 Mapper（表 {@code semantic_meta} 为租户隔离表，tenant_id 由拦截器自动注入）。
 */
@Mapper
public interface SemanticMetaMapper extends BaseMapper<SemanticMeta> {
}
