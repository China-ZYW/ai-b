package com.aibi.module.favorite.service;

import com.aibi.module.favorite.dto.FavoriteRequest;
import com.aibi.module.favorite.dto.FavoriteVO;
import com.aibi.module.favorite.entity.QueryFavorite;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 问数收藏服务（M4 / 开发计划 T4.4）：收藏的增删查。四层架构中的业务层接口。
 *
 * <p>继承 {@link IService} 获得 MyBatis-Plus 通用 CRUD；下列为面向控制器的业务方法。
 * <b>隔离</b>：{@code tenant_id} 由租户拦截器自动注入，{@code uid} 由方法参数显式携带，
 * 删除仅命中本租户本人的收藏，跨用户/跨租户视为不存在。
 */
public interface QueryFavoriteService extends IService<QueryFavorite> {

    /** 新增收藏，返回自增 id。 */
    Long createFavorite(Long uid, FavoriteRequest request);

    /** 列出某用户（本租户内）的全部收藏，按时间倒序。 */
    List<FavoriteVO> listFavorites(Long uid);

    /** 删除收藏（仅限本租户本人）。 */
    void deleteFavorite(Long uid, Long id);
}
