package com.aibi.module.alert.mapper;

import com.aibi.module.alert.entity.AlertRule;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 预警规则 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 * {@code alert_rule} 为租户隔离表，通用 CRUD 自动注入 {@code tenant_id}。
 */
public interface AlertRuleMapper extends BaseMapper<AlertRule> {

    /**
     * 定时扫描用：列出所有「启用且已归属租户」的租户 id（去重）。
     * 跨租户聚合，故关闭租户自动注入、显式按 {@code enabled} 过滤。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT DISTINCT tenant_id FROM alert_rule WHERE enabled = 1 AND tenant_id IS NOT NULL")
    List<Long> selectDistinctActiveTenantIds();
}
