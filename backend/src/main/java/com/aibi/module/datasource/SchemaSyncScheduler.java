package com.aibi.module.datasource;

import com.aibi.common.tenant.PlanType;
import com.aibi.common.tenant.TenantContext;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.datasource.entity.DataSource;
import com.aibi.module.datasource.mapper.DataSourceMapper;
import com.aibi.module.datasource.service.DataSourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表结构定时同步调度器（修复"数据源新增表系统未同步"）。
 *
 * <p>按 {@code aibi.datasource.schema-sync-interval-seconds}（默认 1800s=30min）遍历全租户所有启用数据源，
 * 逐条失效缓存并真实重扫 JDBC 元数据、落盘 {@code lastSchemaSyncAt}（复用 {@link DataSourceService#syncSchema}）。
 * 开关 {@code aibi.datasource.schema-sync-enabled}（默认 true）可一键关停；关停后仅手动「同步」按钮生效。
 *
 * <p><b>租户隔离</b>：{@link DataSourceMapper#selectAllActive()} 用 {@code @InterceptorIgnore} 跨租户取全量，
 * 但每条同步仍需把当前线程租户上下文切到该数据源所属租户（{@link TenantContext#set}），
 * 否则 {@code syncSchema} 内部的 {@code getById} 会因租户拦截器过滤而找不到记录。每条结束 {@link TenantContext#clear} 清理。
 *
 * <p>健壮性：单条失败只告警、不中断整体循环；调度器本身异常（如 DB 不可用）由 Spring 捕获，下一轮继续。
 */
@Component
public class SchemaSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SchemaSyncScheduler.class);

    private final DataSourceMapper dataSourceMapper;
    private final DataSourceService dataSourceService;
    private final TenantMapper tenantMapper;
    private final boolean enabled;
    private final long intervalSeconds;

    public SchemaSyncScheduler(DataSourceMapper dataSourceMapper,
                               DataSourceService dataSourceService,
                               TenantMapper tenantMapper,
                               @Value("${aibi.datasource.schema-sync-enabled:true}") boolean enabled,
                               @Value("${aibi.datasource.schema-sync-interval-seconds:1800}") long intervalSeconds) {
        this.dataSourceMapper = dataSourceMapper;
        this.dataSourceService = dataSourceService;
        this.tenantMapper = tenantMapper;
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
    }

    @Scheduled(
            initialDelayString = "${aibi.datasource.schema-sync-initial-delay-seconds:120}000",
            fixedDelayString = "${aibi.datasource.schema-sync-interval-seconds:1800}000")
    public void syncAll() {
        if (!enabled) {
            log.debug("表结构定时同步已关闭（aibi.datasource.schema-sync-enabled=false），跳过本轮");
            return;
        }
        List<DataSource> all = dataSourceMapper.selectAllActive();
        if (all.isEmpty()) {
            return;
        }
        log.info("表结构定时同步开始：共 {} 个数据源，间隔 {}s", all.size(), intervalSeconds);
        int ok = 0;
        for (DataSource ds : all) {
            try {
                Tenant t = tenantMapper.selectById(ds.getTenantId());
                PlanType plan = (t != null && t.getPlan() != null)
                        ? PlanType.valueOf(t.getPlan()) : PlanType.FREE;
                TenantContext.set(ds.getTenantId(), plan);
                dataSourceService.syncSchema(ds.getId());
                ok++;
            } catch (Exception e) {
                log.warn("定时同步数据源表结构失败: tenant={}, ds={} - {}", ds.getTenantId(), ds.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        log.info("表结构定时同步结束：成功 {} / 共 {}", ok, all.size());
    }
}
