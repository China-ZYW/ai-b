package com.aibi.module.lineage.mapper;

import com.aibi.module.lineage.entity.LineageEdge;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 血缘边表数据访问。
 *
 * <p>仅提供 BaseMapper 能力：上层服务一律显式携带 {@code tenant_id} 条件查询，
 * 不在此处写死租户逻辑，避免调用方误以为"查出来的就是本租户的"。
 */
@Mapper
public interface LineageEdgeMapper extends BaseMapper<LineageEdge> {
}
