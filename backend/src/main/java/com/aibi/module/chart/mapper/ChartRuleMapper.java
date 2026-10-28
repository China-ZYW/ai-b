package com.aibi.module.chart.mapper;

import com.aibi.module.chart.entity.ChartRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图表推荐规则 Mapper（表 {@code chart_rule} 在租户拦截器 IGNORE 名单中，
 * tenant_id 可空，由服务层显式按租户解析）。
 */
@Mapper
public interface ChartRuleMapper extends BaseMapper<ChartRule> {
}
