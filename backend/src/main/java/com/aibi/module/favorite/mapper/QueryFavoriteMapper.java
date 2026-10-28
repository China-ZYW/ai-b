package com.aibi.module.favorite.mapper;

import com.aibi.module.favorite.entity.QueryFavorite;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 问数收藏 Mapper（MyBatis-Plus）。由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册。
 *
 * <p>{@code query_favorite} 不在租户拦截器 IGNORE 名单，故所有 CRUD 会被自动追加 {@code tenant_id} 条件。
 */
public interface QueryFavoriteMapper extends BaseMapper<QueryFavorite> {
}
