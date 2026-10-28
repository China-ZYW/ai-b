package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.CustomRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 租户自定义角色 Mapper（{@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code custom_role} 带 {@code tenant_id} 且未列入租户拦截器 IGNORE 名单，
 * 故 MyBatis-Plus 自动注入隔离条件，本 Mapper 无需手写 {@code tenant_id}。
 */
public interface CustomRoleMapper extends BaseMapper<CustomRole> {
}
