package com.aibi.module.report.service;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.config.service.AibiConfig;
import com.aibi.module.report.entity.ScheduledReport;
import com.aibi.module.report.mapper.ScheduledReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时报表调度器（T12-1）：分钟级 tick + 纯函数到期判定，镜像
 * {@code AlertEngineService.sweepAll()} / {@code GovernancePushScheduler} 的逐任务设上下文范式。
 *
 * <p><b>为什么按报表逐条设上下文</b>（而非逐租户分组）：{@code runOnce} 需要
 * 「租户 + 创建人 uid」双要素才能过 {@code requireOwnTemplate} 并让执行历史按创建人落库；
 * 报表量级小（跨租户上限 2000 防御），逐条 save/restore 最直白。
 *
 * <p><b>门控</b>：{@code ASK / schedule} 按报表所属租户读取（上下文已设，{@code TenantContext}
 * 即取自该上下文）——关闭租户的任务直接跳过，不删任务、不计到期。
 *
 * <p><b>幂等</b>：同一分钟内多轮 tick 靠 {@link ReportDueJudge} 的 {@code last_run_at} 锚天然去重
 * （跑过即本周期不再命中）；单报表任何异常只折进其 {@code last_error}，绝不中断整轮扫描。
 *
 * <p><b>单实例假设</b>：多实例部署会同分钟双跑（设计稿 §10 留痕，二期随分布式锁解决）。
 */
@Service
public class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    private final ScheduledReportMapper reportMapper;
    private final ReportService reportService;
    private final AibiConfig aibiConfig;

    public ReportScheduler(ScheduledReportMapper reportMapper, ReportService reportService,
                           AibiConfig aibiConfig) {
        this.reportMapper = reportMapper;
        this.reportService = reportService;
        this.aibiConfig = aibiConfig;
    }

    /** 每分钟扫一轮（cron 可配 {@code aibi.report.sweep-cron}，默认对齐告警域惯例）。 */
    @Scheduled(cron = "${aibi.report.sweep-cron:0 * * * * *}")
    public void sweep() {
        List<ScheduledReport> reports;
        try {
            reports = reportMapper.selectAllEnabled();
        } catch (Exception e) {
            log.warn("定时报表扫描取数失败: {}", e.getMessage());
            return;
        }
        if (reports.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int ran = 0;
        for (ScheduledReport r : reports) {
            if (r.getTenantId() == null || r.getUid() == null) {
                continue;
            }
            AccessContext previous = AccessContextHolder.get();
            AccessContextHolder.set(AccessContext.builder().tenantId(r.getTenantId()).uid(r.getUid()).build());
            try {
                if (!aibiConfig.scheduleEnabled()) {
                    continue;
                }
                if (ReportDueJudge.isDue(r, now)) {
                    reportService.runOnce(r, now);
                    ran++;
                }
            } catch (Exception e) {
                // runOnce 内部已不抛；此处兜住门控读取/判定等外围异常，单表失败不拖垮整轮
                log.warn("定时报表调度执行失败 id={} tenant={}: {}", r.getId(), r.getTenantId(), e.getMessage());
            } finally {
                if (previous == null) {
                    AccessContextHolder.clear();
                } else {
                    AccessContextHolder.set(previous);
                }
            }
        }
        if (ran > 0) {
            log.info("定时报表扫描完成：候选 {} 条，执行 {} 条", reports.size(), ran);
        }
    }
}
