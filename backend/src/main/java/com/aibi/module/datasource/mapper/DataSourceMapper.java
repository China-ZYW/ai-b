package com.aibi.module.datasource.mapper;

import com.aibi.module.datasource.entity.DataSource;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据源 Mapper（MyBatis-Plus）。由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册。
 *
 * <p>{@code data_source} 不在租户拦截器 IGNORE 名单，CRUD 自动追加 {@code tenant_id}；
 * 叠加实体上的 {@code @TableLogic}，查询自动过滤 {@code status=1}、删除自动转逻辑删除。
 *
 * <p>{@link #selectAllActive()} 供定时全量同步调度器跨租户遍历所有数据源：用 {@code @InterceptorIgnore}
 * 关闭租户行隔离，并自行 {@code status = 1} 过滤已删除源（规避 {@code @TableLogic} 自动注入）。
 */
public interface DataSourceMapper extends BaseMapper<DataSource> {

    /** 全量扫描所有租户处于启用状态的数据源（定时同步调度器用）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM data_source WHERE status = 1")
    List<DataSource> selectAllActive();
}
