package com.aibi.module.rbac.mapper;

import com.aibi.module.rbac.entity.UserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色授予 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p><b>务必注意</b>：{@code user_role} 带 {@code tenant_id} 却列入租户拦截器 IGNORE 名单
 * （登录链路要在租户上下文建立之前按 {@code uid} 反查角色），因此本 Mapper 的每条 SQL
 * 都<b>显式携带 {@code tenant_id}</b>——这不是冗余，而是该表唯一的隔离保障。
 * 新增查询若漏写 {@code tenant_id}，将造成跨组织读取他人角色（静默越权，难以排查）。
 *
 * <p>平台角色查询传 {@code tenantId=0}。
 */
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * 某身份在指定域内的全部有效授予（{@code status=1} 且未过期）。
     *
     * <p>过期授权在此处即被过滤，无需定时清理任务；组织域与平台域各调一次
     * （平台域传 {@code tenantId=0}），二者结果<b>不可合并</b>——见设计文档 3.6① 的防跨域提权。
     */
    @Select("""
            SELECT * FROM user_role
            WHERE tenant_id = #{tenantId} AND uid = #{uid} AND status = 1
              AND (expire_at IS NULL OR expire_at > NOW())
            ORDER BY id ASC
            """)
    List<UserRole> selectEffective(@Param("tenantId") Long tenantId, @Param("uid") Long uid);

    /** 某组织内持有指定组织角色的有效授予数（"不可移除最后一个 OWNER"护栏用）。 */
    @Select("""
            SELECT COUNT(*) FROM user_role
            WHERE tenant_id = #{tenantId} AND role_code = #{roleCode} AND status = 1
              AND scope_type = 'TENANT' AND (expire_at IS NULL OR expire_at > NOW())
            """)
    long countTenantRoleHolders(@Param("tenantId") Long tenantId, @Param("roleCode") String roleCode);

    /** 某组织内持有指定组织角色的 uid 列表（转让所有者时校验目标是否为 OWNER/ADMIN）。 */
    @Select("""
            SELECT uid FROM user_role
            WHERE tenant_id = #{tenantId} AND role_code = #{roleCode} AND status = 1
              AND scope_type = 'TENANT' AND (expire_at IS NULL OR expire_at > NOW())
            ORDER BY uid ASC
            """)
    List<Long> selectTenantRoleHolders(@Param("tenantId") Long tenantId, @Param("roleCode") String roleCode);

    /**
     * 某组织内全部有效授予（成员列表页一次性取回后在内存分组，避免 N+1 查询）。
     */
    @Select("""
            SELECT * FROM user_role
            WHERE tenant_id = #{tenantId} AND status = 1
              AND (expire_at IS NULL OR expire_at > NOW())
            ORDER BY uid ASC, id ASC
            """)
    List<UserRole> selectEffectiveByTenant(@Param("tenantId") Long tenantId);
}
