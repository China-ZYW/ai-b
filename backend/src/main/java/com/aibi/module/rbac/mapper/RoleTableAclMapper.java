package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.RoleTableAcl;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 角色表级可见性 Mapper（MyBatis-Plus）。租户隔离由拦截器自动注入。
 */
public interface RoleTableAclMapper extends BaseMapper<RoleTableAcl> {
}
