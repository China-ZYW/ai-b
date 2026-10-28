package com.aibi.module.query.mapper;

import com.aibi.module.query.entity.QueryRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询记录 Mapper（SQL 编辑器历史页）。
 *
 * <p>只声明"按本人最近 N 条"这一条定制查询——其余 CRUD 走 MyBatis-Plus 通用方法，
 * 租户条件由拦截器统一注入。
 */
@Mapper
public interface QueryRecordMapper extends BaseMapper<QueryRecord> {
}
