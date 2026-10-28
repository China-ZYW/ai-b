package com.aibi.module.favorite.service.impl;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.favorite.dto.FavoriteRequest;
import com.aibi.module.favorite.dto.FavoriteVO;
import com.aibi.module.favorite.entity.QueryFavorite;
import com.aibi.module.favorite.mapper.QueryFavoriteMapper;
import com.aibi.module.favorite.service.QueryFavoriteService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问数收藏服务实现（MyBatis-Plus 四层架构之 ServiceImpl）。
 *
 * <p>继承 {@link ServiceImpl} 复用通用 CRUD；{@code tenant_id} 由 {@code TenantLineInnerInterceptor}
 * 自动注入（INSERT 自动补列、SELECT/UPDATE/DELETE 自动追加条件），故此处只显式处理用户级过滤 {@code uid}。
 */
@Service
public class QueryFavoriteServiceImpl extends ServiceImpl<QueryFavoriteMapper, QueryFavorite>
        implements QueryFavoriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryFavoriteServiceImpl.class);

    @Override
    public Long createFavorite(Long uid, FavoriteRequest request) {
        // 幂等去重：同一用户 + 数据源 + 问题 已收藏则直接返回既有 id，杜绝重复收藏（问题1）
        QueryFavorite existing = lambdaQuery()
                .eq(QueryFavorite::getUid, uid)
                .eq(QueryFavorite::getDatasourceId, request.datasourceId())
                .eq(QueryFavorite::getQuestion, request.question())
                .orderByDesc(QueryFavorite::getId)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            log.info("收藏已存在，幂等返回: uid={}, id={}", uid, existing.getId());
            return existing.getId();
        }
        QueryFavorite entity = new QueryFavorite();
        entity.setUid(uid);
        entity.setDatasourceId(request.datasourceId());
        entity.setTitle(request.title());
        entity.setQuestion(request.question());
        entity.setSqlText(request.sql());
        save(entity);   // 租户拦截器自动补 tenant_id；IdType.AUTO 回填自增主键
        log.info("新增收藏: uid={}, id={}", uid, entity.getId());
        return entity.getId();
    }

    @Override
    public List<FavoriteVO> listFavorites(Long uid) {
        List<QueryFavorite> rows = lambdaQuery()
                .eq(QueryFavorite::getUid, uid)
                .orderByDesc(QueryFavorite::getId)
                .list();
        return rows.stream().map(this::toVO).toList();
    }

    @Override
    public void deleteFavorite(Long uid, Long id) {
        boolean removed = remove(Wrappers.<QueryFavorite>lambdaQuery()
                .eq(QueryFavorite::getId, id)
                .eq(QueryFavorite::getUid, uid));
        if (!removed) {
            throw new BizException(ErrorCode.DATASOURCE_NOT_FOUND, "收藏不存在或无权访问");
        }
    }

    private FavoriteVO toVO(QueryFavorite e) {
        return new FavoriteVO(e.getId(), e.getDatasourceId(), e.getTitle(),
                e.getQuestion(), e.getSqlText(), e.getCreatedAt());
    }
}
