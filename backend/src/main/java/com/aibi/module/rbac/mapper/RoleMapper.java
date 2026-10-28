package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.Role;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 角色字典 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code role} 为全局字典表，已列入租户拦截器 IGNORE 名单，CRUD 不注入 {@code tenant_id}。
 * 内置角色由 {@code DataSeeder} 逐条播种，运行期只读；自定义角色另见 {@code custom_role}。
 */
public interface RoleMapper extends BaseMapper<Role> {
}
