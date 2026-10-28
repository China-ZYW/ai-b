package com.aibi.module.manual.mapper;

import com.aibi.module.manual.entity.UserManual;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 用户手册 Mapper（MyBatis-Plus）。由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册。
 *
 * <p>{@code user_manual} 已列入租户拦截器 IGNORE 名单（平台级全局单例），通用 CRUD 不追加 tenant_id。
 */
public interface UserManualMapper extends BaseMapper<UserManual> {
}
