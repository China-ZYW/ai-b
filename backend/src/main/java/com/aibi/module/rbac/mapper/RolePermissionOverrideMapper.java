package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.RolePermissionOverride;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 自定义角色权限增减 Mapper（{@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code role_permission_override} 带 {@code tenant_id} 且未列入 IGNORE 名单，
 * 由租户拦截器自动注入隔离条件；本 Mapper 仅按 {@code custom_role_id + effect} 查询，
 * 隔离由框架保证。
 */
public interface RolePermissionOverrideMapper extends BaseMapper<RolePermissionOverride> {

    /**
     * 自定义角色的权限覆盖码集合（按 effect 过滤）。
     *
     * <p>{@code role_permission_override} 带 {@code tenant_id} 且未列入 IGNORE 名单，
     * 租户拦截器自动追加隔离条件，此处只需按 {@code custom_role_id + effect} 查询。
     * 调用方（{@code PermissionService}）按 {@code tenantId:customRoleId} 缓存结果。
     */
    @Select("""
            <script>
            SELECT permission_code FROM role_permission_override
            WHERE custom_role_id = #{customRoleId} AND effect = #{effect}
            </script>
            """)
    List<String> selectOverrideCodes(@Param("customRoleId") Long customRoleId, @Param("effect") String effect);
}
