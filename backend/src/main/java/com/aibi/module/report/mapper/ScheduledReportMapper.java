package com.aibi.module.report.mapper;

import com.aibi.module.report.entity.ScheduledReport;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 定时报表 Mapper（由 {@code @MapperScan("com.aibi.module.**.mapper")} 扫描注册）。
 *
 * <p>{@code scheduled_report} 不在 {@code IGNORE_TABLES}，常规 CRUD 的 {@code tenant_id}
 * 由拦截器自动注入；唯一例外是 {@link #selectAllEnabled()}——scheduler 跨租户扫描入口，
 * 关闭 {@code tenantLine} 自动注入（同 {@code AlertRuleMapper.selectDistinctActiveTenantIds} 范式）。
 */
public interface ScheduledReportMapper extends BaseMapper<ScheduledReport> {

    /**
     * 全平台启用中的报表任务（scheduler sweepAll 跨租户扫描入口）。
     *
     * <p>本查询跨租户，关闭 {@code tenantLine} 注入；LIMIT 2000 防御极端全表扫
     * （单租户报表数量业务上远小于该量级，超限属于配置异常，log 侧可见）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM scheduled_report WHERE enabled = 1 ORDER BY id LIMIT 2000")
    List<ScheduledReport> selectAllEnabled();
}
