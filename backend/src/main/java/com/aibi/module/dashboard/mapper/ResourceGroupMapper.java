package com.aibi.module.dashboard.mapper;

import com.aibi.module.dashboard.entity.ResourceGroup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 资源分组表访问（租户隔离由拦截器自动注入 {@code tenant_id}）。 */
@Mapper
public interface ResourceGroupMapper extends BaseMapper<ResourceGroup> {
}
