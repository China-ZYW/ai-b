package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.RolePermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色权限关联 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code role_permission} 为全局字典表，已列入租户拦截器 IGNORE 名单。播种时按
 * {@code (role_code, permission_code)} 唯一键逐条 insertIfMissing，<b>不能</b>用
 * "全表非空即跳过"的判断——那会让后续新增的权限点永远播不进去（项目已在
 * {@code DataSeeder.seedSysConfig} 上踩过这个坑）。
 */
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    /** 某角色已授予的权限点码（后台角色详情页与"授权比对"用）。 */
    @Select("SELECT permission_code FROM role_permission WHERE role_code = #{roleCode} ORDER BY id ASC")
    List<String> selectPermissionCodes(@Param("roleCode") String roleCode);

    /** 某权限点被授予给了哪些角色（下线权限点前评估影响面）。 */
    @Select("SELECT role_code FROM role_permission WHERE permission_code = #{permissionCode} ORDER BY id ASC")
    List<String> selectRoleCodes(@Param("permissionCode") String permissionCode);
}
