package com.aibi.module.alert.service;

import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.alert.dto.AlertAttributionVO;
import com.aibi.module.alert.dto.AlertDriverVO;
import com.aibi.module.alert.entity.AlertRecord;
import com.aibi.module.alert.entity.AlertRule;
import com.aibi.module.alert.mapper.AlertRecordMapper;
import com.aibi.module.alert.mapper.AlertRuleMapper;
import com.aibi.module.attribution.AttributionService;
import com.aibi.module.attribution.dto.AttributionOptionVO;
import com.aibi.module.attribution.dto.AttributionReq;
import com.aibi.module.attribution.dto.AttributionVO;
import com.aibi.module.attribution.dto.ContributionItem;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能预警引擎（H 缺口骨架）：规则—检测—通知—记录。
 *
 * <p>① 检测：{@link #detectTenant} 加载本租户启用规则，逐条经 {@link MetricValueResolver} 取实时值并比对阈值；
 * ② 触发：越界且窗口内无重复 FIRED 记录时写 {@link AlertRecord} 并调用 {@link AlertNotifier}；
 * ③ 通知：当前仅 LOG（见 {@link LogAlertNotifier}，对应 F：通知渠道仅 LOG）；
 * ④ 记录：落 {@code alert_record}，供前端「触发记录」查看与后续处置（ACK/RESOLVED）。
 *
 * <p>定时扫描 {@link #sweepAll} 由全局 {@code @EnableScheduling} 驱动（cron 可配 {@code aibi.alert.scan-cron}），
 * 逐租户设置访问上下文后复用 {@code detectTenant}，确保租户拦截器正确注入 tenant_id。
 */
@Service
public class AlertEngineService {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineService.class);

    /** 业务指标键前缀 {@code metric:<id>}：与 {@code DefaultMetricValueResolver} 同口径，仅此族可归因。 */
    private static final String METRIC_PREFIX = "metric:";
    /** 站内信渠道码（与 {@link InAppAlertNotifier#CHANNEL} 一致）：用于判定主渠道已为 INAPP 时跳过默认旁路。 */
    private static final String INAPP_CHANNEL = InAppAlertNotifier.CHANNEL;
    /** 归因摘要里保留的头部贡献成员数：告警只需回答"是谁"，不做完整明细。 */
    private static final int TOP_DRIVERS = 3;

    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper recordMapper;
    private final MetricValueResolver metricValueResolver;
    /** 通知路由（不再是单个 AlertNotifier：多渠道下按 rule.notifyChannel 分发）。 */
    private final AlertNotifierRouter notifierRouter;
    /**
     * 站内信渠道：作为"默认旁路"在每条预警触发时额外投递（见 {@link #fire}），
     * 保证站内信默认触发。此引用是刻意的——INAPP 是平台自建收件箱，不是可插拔扩展渠道，
     * 故引擎直接持有它，而非经路由按码分发。
     */
    private final InAppAlertNotifier inAppNotifier;
    private final AuditService auditService;
    private final AttributionService attributionService;
    private final ObjectMapper objectMapper;

    public AlertEngineService(AlertRuleMapper ruleMapper, AlertRecordMapper recordMapper,
                              MetricValueResolver metricValueResolver, AlertNotifierRouter notifierRouter,
                              InAppAlertNotifier inAppNotifier,
                              AuditService auditService, AttributionService attributionService,
                              ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.metricValueResolver = metricValueResolver;
        this.notifierRouter = notifierRouter;
        this.inAppNotifier = inAppNotifier;
        this.auditService = auditService;
        this.attributionService = attributionService;
        this.objectMapper = objectMapper;
    }

    /** 对单个租户执行一次检测（请求线程手动触发 / 定时线程已设上下文）。 */
    @Transactional
    public int detectTenant(Long tenantId) {
        List<AlertRule> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<AlertRule>().eq(AlertRule::getEnabled, true));
        int fired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AlertRule rule : rules) {
            try {
                Optional<BigDecimal> valueOpt = metricValueResolver.resolve(tenantId, rule.getMetricKey());
                if (valueOpt.isEmpty()) {
                    log.debug("预警规则 {} 指标 {} 无当前值，跳过", rule.getId(), rule.getMetricKey());
                    continue;
                }
                BigDecimal value = valueOpt.get();
                if (breaches(rule, value) && !recentlyFired(rule, now)) {
                    fire(rule, value);
                    fired++;
                }
            } catch (Exception e) {
                log.warn("预警规则 {} 检测异常: {}", rule.getId(), e.getMessage());
            }
        }
        return fired;
    }

    /**
     * 预跑归因（P1）：跑一次单维归因并压缩为摘要，供触发记录落库与"试跑"预览共用。
     *
     * <p><b>口径从哪来</b>：维度优先取 {@code dimensionOverride}（规则上显式指定），
     * 为空则回落到指标库沉淀的默认维度（{@code metric.attribution_dimension}）；
     * 基准取指标默认基准，期间留空由归因服务自动取最新一期。
     *
     * <p><b>为什么 {@code clarified=true}</b>：自动执行场景不允许反问人，
     * 故直接跳过单轮澄清（需求 §3.2 的 CLARIFY 门控）；门控结果为 BLOCK 时仍如实记录
     * （gate 字段随摘要返回），由前端提示"置信度不足，结论仅供参考"。
     *
     * <p><b>失败语义</b>：任何异常都归一为"仅带 reason 的摘要"，不抛出——
     * 归因是告警的增强信息，缺了它告警依旧成立，绝不能让归因拖垮整轮检测。
     *
     * @param metricKey          规则监控的指标键（仅 {@code metric:<id>} 可归因）
     * @param dimensionOverride  维度覆盖（物理列名）；为空则用指标默认维度
     * @return 归因摘要；无法归因时 {@link AlertAttributionVO#hasResult()} 为 false 并带 reason
     */
    public AlertAttributionVO preRunAttribution(String metricKey, String dimensionOverride) {
        if (metricKey == null || !metricKey.startsWith(METRIC_PREFIX)) {
            return AlertAttributionVO.skipped(null, "仅业务指标（metric:<id>）支持自动归因");
        }
        long metricId;
        try {
            metricId = Long.parseLong(metricKey.substring(METRIC_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            return AlertAttributionVO.skipped(null, "指标键无法解析：" + metricKey);
        }
        try {
            AttributionOptionVO options = attributionService.dimensions(metricId);
            String dim = (dimensionOverride != null && !dimensionOverride.isBlank())
                    ? dimensionOverride.trim()
                    : options.defaultDimension();
            if (dim == null || dim.isBlank()) {
                return AlertAttributionVO.skipped(metricId,
                        "指标未沉淀归因默认维度，请在规则中指定维度或到指标库补充");
            }
            // topN 多取 2 个再截断：头部成员若有并列或空值，仍保证摘要里有足够候选
            // 倒数第三个 null = historyId：预警触发的归因不挂在某条问数历史上，故不回写
            AttributionReq req = new AttributionReq(metricId, dim, options.defaultBaseline(), null,
                    AttributionReq.GRANULARITY_MONTH, null, null, TOP_DRIVERS + 2, true);
            AttributionVO vo = attributionService.run(req);
            return new AlertAttributionVO(vo.metricId(), vo.metricName(), vo.dimension(), vo.dimensionLabel(),
                    vo.baseline(), vo.currentPeriod(), vo.basePeriod(), vo.totalDelta(),
                    topDrivers(vo.items()), vo.insight(), vo.confidence(), vo.gate(), null);
        } catch (Exception e) {
            log.warn("预警归因预跑失败 metricKey={}: {}", metricKey, e.getMessage());
            return AlertAttributionVO.skipped(metricId, "归因预跑失败：" + e.getMessage());
        }
    }

    /** 按 |贡献| 降序取头部成员——告警只回答"是谁"，不做完整明细。 */
    private List<AlertDriverVO> topDrivers(List<ContributionItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ContributionItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> absOf(b.delta()).compareTo(absOf(a.delta())));
        List<AlertDriverVO> out = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_DRIVERS, sorted.size()); i++) {
            ContributionItem c = sorted.get(i);
            out.add(new AlertDriverVO(c.member(), c.label(), c.delta(), c.share()));
        }
        return out;
    }

    private static BigDecimal absOf(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.abs();
    }

    /** 归因一句话摘要：追加到告警 message 末尾，让站内通知与列表首屏直接看到主因。 */
    private String attributionBrief(AlertAttributionVO avo) {
        if (!avo.hasResult()) {
            return "归因未生成：" + (avo.reason() == null ? "无归因数据" : avo.reason());
        }
        AlertDriverVO top = avo.drivers().get(0);
        String name = (top.label() != null && !top.label().isBlank()) ? top.label() : top.member();
        String dimLabel = avo.dimensionLabel() != null ? avo.dimensionLabel() : avo.dimension();
        if (top.delta() == null) {
            return String.format("归因(%s)：%s", dimLabel, name);
        }
        return String.format("归因(%s)：%s %,.2f", dimLabel, name, top.delta());
    }

    private boolean breaches(AlertRule rule, BigDecimal value) {
        int c = value.compareTo(rule.getThreshold());
        String op = rule.getOperator();
        if (op == null) {
            return false;
        }
        switch (op) {
            case "GT":
                return c > 0;
            case "LT":
                return c < 0;
            case "GTE":
                return c >= 0;
            case "LTE":
                return c <= 0;
            default:
                return false;
        }
    }

    private boolean recentlyFired(AlertRule rule, LocalDateTime now) {
        int window = rule.getWindowMinutes() == null ? 1440 : rule.getWindowMinutes();
        LocalDateTime since = now.minusMinutes(window);
        Long cnt = recordMapper.selectCount(new LambdaQueryWrapper<AlertRecord>()
                .eq(AlertRecord::getRuleId, rule.getId())
                .eq(AlertRecord::getStatus, "FIRED")
                .ge(AlertRecord::getCreatedAt, since));
        return cnt != null && cnt > 0;
    }

    private void fire(AlertRule rule, BigDecimal value) {
        AlertRecord rec = new AlertRecord();
        rec.setTenantId(rule.getTenantId());
        rec.setRuleId(rule.getId());
        rec.setRuleName(rule.getName());
        rec.setMetricKey(rule.getMetricKey());
        rec.setValue(value);
        rec.setOperator(rule.getOperator());
        rec.setThreshold(rule.getThreshold());
        rec.setSeverity(rule.getSeverity());
        rec.setStatus("FIRED");
        rec.setMessage(String.format("指标 %s 当前值 %s %s 阈值 %s",
                rule.getMetricKey(), value, rule.getOperator(), rule.getThreshold()));
        rec.setNotified(false);
        rec.setCreatedAt(LocalDateTime.now());
        // P1「预警触发预跑归因」：触发瞬间数据就在手边，顺手跑一次单维归因写进记录，
        // 让告警从"指标越界了"升级为"是谁导致的"。失败只降级（不写摘要），绝不反噬告警本身。
        if (Boolean.TRUE.equals(rule.getAutoAttribution())) {
            AlertAttributionVO avo = preRunAttribution(rule.getMetricKey(), rule.getAttributionDimension());
            if (avo != null) {
                try {
                    rec.setAttribution(objectMapper.writeValueAsString(avo));
                    rec.setMessage(rec.getMessage() + " ｜ " + attributionBrief(avo));
                } catch (JsonProcessingException e) {
                    log.warn("预警记录归因摘要序列化失败 rule={}: {}", rule.getId(), e.getMessage());
                }
            }
        }
        recordMapper.insert(rec);

        // 通知：路由按 rule.notifyChannel 分发（渠道未接入/不可用会回落兜底渠道）。
        // 真的投递失败时 notified 记 false，让"没发出去的告警"在 alert_record 上可见。
        boolean notified = true;
        try {
            notifierRouter.notify(rule, rec);
        } catch (Exception e) {
            notified = false;
            log.warn("预警通知失败 rule={} 渠道[{}]: {}", rule.getId(), rule.getNotifyChannel(), e.getMessage());
        }
        if (notified) {
            recordMapper.update(null, new LambdaUpdateWrapper<AlertRecord>()
                    .eq(AlertRecord::getId, rec.getId())
                    .set(AlertRecord::getNotified, true));
        }

        // 站内信默认触发（旁路）：无论主渠道是否为 INAPP，都额外写一条站内信给规则相关人
        // （notify_target 指定者 ∪ 规则创建人），让"每条预警都进收件箱"成为默认行为。
        // 主渠道本身就是 INAPP 时，路由已写过，这里跳过以免重复；否则 best-effort 写入，
        // 失败只记日志、不影响上面主渠道的 notified 标记与告警主链路。
        if (!INAPP_CHANNEL.equals(rule.getNotifyChannel())) {
            try {
                inAppNotifier.notifyDefault(rule, rec);
            } catch (Exception e) {
                log.warn("站内信默认旁路失败 rule={}: {}", rule.getId(), e.getMessage());
            }
        }
        try {
            auditService.record(AuditAction.ALERT_FIRED, AuditAction.RESULT_OK, rule.getTenantId(), null,
                    "ALERT_RULE", String.valueOf(rule.getId()),
                    Map.of("metric", rule.getMetricKey(), "value", value.toPlainString()));
        } catch (Exception ignored) {
            // 审计失败不反噬业务
        }
    }

    /** 全平台定时扫描：逐租户设置上下文后检测（确保 tenant_id 正确注入）。 */
    @Scheduled(cron = "${aibi.alert.scan-cron:0 0 * * * *}")
    public void sweepAll() {
        List<Long> tenants;
        try {
            tenants = ruleMapper.selectDistinctActiveTenantIds();
        } catch (Exception e) {
            log.warn("预警租户扫描失败: {}", e.getMessage());
            return;
        }
        for (Long tid : tenants) {
            if (tid == null) {
                continue;
            }
            AccessContext previous = AccessContextHolder.get();
            AccessContextHolder.set(AccessContext.builder().tenantId(tid).build());
            try {
                int fired = detectTenant(tid);
                if (fired > 0) {
                    log.info("预警定时扫描 tenant={} 触发 {} 条", tid, fired);
                }
            } catch (Exception e) {
                log.warn("预警定时扫描 tenant={} 失败: {}", tid, e.getMessage());
            } finally {
                if (previous == null) {
                    AccessContextHolder.clear();
                } else {
                    AccessContextHolder.set(previous);
                }
            }
        }
    }
}
