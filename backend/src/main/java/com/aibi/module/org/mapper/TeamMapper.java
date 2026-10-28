package com.aibi.module.org.mapper;

import com.aibi.module.org.entity.Team;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 团队 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code team} <b>不在</b>租户拦截器 IGNORE 名单，故通用 CRUD 会自动追加 {@code tenant_id}，
 * 按当前上下文天然隔离。下列查询例外地关闭自动注入并显式传 {@code tenantId}，
 * 原因是它们服务于<b>登录链路与启动回填</b>——此时 {@code TenantContext} 尚未建立，自动注入会得到
 * {@code tenant_id = NULL} 而恒不命中，导致"登录后团队集合为空"。
 *
 * <p><b>不用递归 CTE 展开子树</b>：jsqlparser 4.6（版本随 MyBatis-Plus 3.5.5 锁定，勿升级）
 * 对 {@code WITH RECURSIVE} 的解析支持不完整，而租户拦截器要解析每条 SQL，解析失败会直接抛错。
 * 改为一次性取回本组织全部团队、在 Java 侧按 {@code parent_id} 做 BFS（{@code TeamTree}），
 * 既避开解析风险，也把 N 次查询压成 1 次并可缓存。
 */
public interface TeamMapper extends BaseMapper<Team> {

    /** 本组织全部启用团队（构建树形与子团队集合的唯一数据来源）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM team WHERE tenant_id = #{tenantId} AND status = 1
            ORDER BY parent_id ASC, sort ASC, id ASC
            """)
    List<Team> selectAllByTenant(@Param("tenantId") Long tenantId);

    /** 本组织默认团队（{@code code='default'}）：回填与登录默认团队解析用，可能尚未创建而返回 null。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM team WHERE tenant_id = #{tenantId} AND code = 'default' LIMIT 1")
    Team selectDefault(@Param("tenantId") Long tenantId);

    /** 按 id 精确取团队并显式校验租户：跨组织传 teamId 时返回 null 而非他人数据。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM team WHERE id = #{teamId} AND tenant_id = #{tenantId} LIMIT 1")
    Team selectByIdAndTenant(@Param("teamId") Long teamId, @Param("tenantId") Long tenantId);
}
