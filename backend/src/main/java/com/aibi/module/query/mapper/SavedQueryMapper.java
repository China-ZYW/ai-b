package com.aibi.module.query.mapper;

import com.aibi.module.query.entity.SavedQuery;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询模板 Mapper（SQL 编辑器 P1）。
 *
 * <p>不做任何跨租户假设：{@code tenant_id} 由 {@code MybatisPlusConfig} 的租户拦截器自动注入，
 * 因此这里没有一条手写 {@code WHERE tenant_id = ?}。
 */
@Mapper
public interface SavedQueryMapper extends BaseMapper<SavedQuery> {
}
