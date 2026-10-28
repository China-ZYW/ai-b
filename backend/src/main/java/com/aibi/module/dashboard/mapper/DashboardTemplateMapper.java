package com.aibi.module.dashboard.mapper;

import com.aibi.module.dashboard.entity.DashboardTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 驾驶舱内置模板 Mapper（由 {@code @MapperScan} 扫描注册）。{@code dashboard_template} 为全局表，
 * 已列入租户拦截器 IGNORE 名单，故 CRUD 不注入 {@code tenant_id}，所有租户共享同一套内置模板。
 */
public interface DashboardTemplateMapper extends BaseMapper<DashboardTemplate> {
}
