package com.aibi.module.config.mapper;

import com.aibi.module.config.entity.SysConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 统一配置中心 Mapper（表 {@code sys_config} 列入租户拦截器 IGNORE 名单，条件由服务层显式携带）。
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
