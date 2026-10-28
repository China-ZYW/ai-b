package com.aibi.module.admin.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 平台级统计 Mapper（超管概览）。跨租户聚合查询，全部关闭租户自动注入（{@code @InterceptorIgnore}），
 * 因为超级管理员需要看到全平台汇总而非某个租户的数据。由 {@code @MapperScan} 扫描注册。
 *
 * <p>趋势类查询返回 {@code List<Map<String,Object>>}（列别名均为小写无下划线，
 * 规避 map-underscore-to-camel-case 对 Map 结果的改写）；数值列统一以
 * {@code ((Number) v).longValue()} 收敛（COUNT→Long、SUM(CASE)→BigDecimal）。
 */
public interface AdminStatsMapper {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM tenant")
    long countTenants();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM app_user")
    long countUsers();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM data_source")
    long countDataSources();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM llm_config")
    long countLlmConfigs();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM dashboard")
    long countDashboards();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM query_history WHERE created_at >= CURDATE()")
    long countTodayQueries();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM query_history")
    long countTotalQueries();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM query_history WHERE status = 'FAILED'")
    long countFailedQueries();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT DATE(created_at) AS d, COUNT(*) AS total, "
            + "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed "
            + "FROM query_history WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 13 DAY) "
            + "GROUP BY DATE(created_at) ORDER BY d")
    List<Map<String, Object>> queryTrendRaw();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT DATE(created_at) AS d, COUNT(*) AS users FROM app_user "
            + "WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 13 DAY) "
            + "GROUP BY DATE(created_at) ORDER BY d")
    List<Map<String, Object>> userTrendRaw();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT DATE(created_at) AS d, COUNT(*) AS tenants FROM tenant "
            + "WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 13 DAY) "
            + "GROUP BY DATE(created_at) ORDER BY d")
    List<Map<String, Object>> tenantTrendRaw();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT plan AS name, COUNT(*) AS value FROM tenant GROUP BY plan")
    List<Map<String, Object>> planDistributionRaw();

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT t.name AS name, COUNT(*) AS value FROM query_history q "
            + "JOIN tenant t ON t.id = q.tenant_id "
            + "GROUP BY q.tenant_id, t.name ORDER BY value DESC LIMIT 8")
    List<Map<String, Object>> topTenantsRaw();
}
