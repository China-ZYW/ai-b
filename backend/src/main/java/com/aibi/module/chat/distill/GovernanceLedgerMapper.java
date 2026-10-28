package com.aibi.module.chat.distill;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 待治理问题台账 Mapper（T11-1 二期 B）。
 *
 * <p>由 {@code @MapperScan} 或 {@code @Mapper} 注解注册。{@code governance_issue} 不在
 * {@code IGNORE_TABLES} → {@code tenant_id} 由拦截器自动注入，常规 CRUD 无需显式携带。
 * 但 {@link #selectTenantsWithData()} 是跨租户查询（scheduler 扫描入口），需手动绕过拦截器。
 */
@Mapper
public interface GovernanceLedgerMapper extends BaseMapper<GovernanceLedgerEntry> {

    /**
     * 取有 query_history 数据的租户 id（scheduler 跨租户扫描入口）。
     *
     * <p>本查询跨租户，关闭 {@code tenantLine} 自动注入（同 {@code AlertRuleMapper.selectDistinctActiveTenantIds} 范式）。
     * 限制 200 防止极端全表扫。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT DISTINCT tenant_id FROM query_history WHERE tenant_id > 0 LIMIT 200")
    List<Long> selectTenantsWithData();
}
