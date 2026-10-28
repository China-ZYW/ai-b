package com.aibi.module.theme.mapper;

import com.aibi.module.theme.entity.ThemeConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 主题令牌 Mapper（表 {@code theme_config} 在租户拦截器 IGNORE 名单中，
 * tenant_id 可空，由服务层显式解析）。
 */
@Mapper
public interface ThemeConfigMapper extends BaseMapper<ThemeConfig> {
}
