package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.UserFieldAcl;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 成员字段脱敏 Mapper（{@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code user_field_acl} 带 {@code tenant_id} 且未列入 IGNORE 名单，由租户拦截器自动注入隔离条件。
 */
@Mapper
public interface UserFieldAclMapper extends BaseMapper<UserFieldAcl> {
}
