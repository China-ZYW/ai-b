package com.aibi.module.quota.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.PlanType;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.quota.dto.QuotaStatus;
import com.aibi.module.quota.mapper.QuotaUsageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 配额限流（M6 + 超管配额配置）：按“租户”限制问数次数，支持每日上限、累计总次数上限与使用期限，
 * 三者均由超级管理员在租户维度配置（见 {@code module/admin}）。
 *
 * <p><b>DB 持久化实现</b>：每日计数落 {@code quota_usage}（租户+自然日，原子 upsert），累计计数落
 * {@code tenant.total_used}（原子带护栏 UPDATE），上限/期限读 {@code tenant}。相较早期内存实现，
 * 重启不丢计数、支持多实例，且配额可动态调整。{@code aibi.quota.*} 仅作为建租户时的兜底默认值来源。
 *
 * <p>计费口径：一次问数在进入执行前<b>原子占用</b>每日与累计各一个名额（防并发穿透），无论后续成功失败均计入，
 * 因为失败同样产生了 LLM 调用成本。每日或累计任一为 {@code -1} 表示该维度不限制。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final TenantMapper tenantMapper;
    private final QuotaUsageMapper quotaUsageMapper;
    private final int freeDailyLimit;
    private final int proDailyLimit;

    public QuotaService(TenantMapper tenantMapper,
                        QuotaUsageMapper quotaUsageMapper,
                        @Value("${aibi.quota.free-daily:20}") int freeDailyLimit,
                        @Value("${aibi.quota.pro-daily:1000}") int proDailyLimit) {
        this.tenantMapper = tenantMapper;
        this.quotaUsageMapper = quotaUsageMapper;
        this.freeDailyLimit = freeDailyLimit;
        this.proDailyLimit = proDailyLimit;
        log.info("配额限流初始化（DB 持久化）：兜底默认 free-daily={}, pro-daily={}", freeDailyLimit, proDailyLimit);
    }

    /**
     * 校验并原子占用一个名额（每日 + 累计）；触限或租户停用/过期时抛出对应 {@link ErrorCode}。
     * 不限量的维度直接放行；累计被拦截时回滚已占用的当日名额以保持一致。
     */
    public void checkAndConsume(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_MISSING, "租户不存在或已删除");
        }
        assertUsable(tenant);

        int dailyLimit = resolveDailyLimit(tenant);
        LocalDate today = LocalDate.now();
        // ① 每日配额：limit<0 不限；==0 直接拒绝；>0 原子占用（受影响行数 0 表示已达上限）
        if (dailyLimit == 0) {
            throw new BizException(ErrorCode.FREE_LIMIT_EXCEEDED, "每日问数上限为 0，请联系管理员调整配额");
        }
        if (dailyLimit > 0 && quotaUsageMapper.tryConsume(tenantId, today, dailyLimit) == 0) {
            log.warn("每日配额超限：tenant={}, dailyLimit={}", tenantId, dailyLimit);
            throw new BizException(ErrorCode.FREE_LIMIT_EXCEEDED,
                    "每日问数上限 " + dailyLimit + " 次已用尽，请明日再试或联系管理员提升配额");
        }

        // ② 累计配额：原子带护栏占用；受影响行数 0 表示已达累计上限
        if (tenantMapper.consumeTotal(tenantId) == 0) {
            if (dailyLimit > 0) {
                quotaUsageMapper.decrement(tenantId, today);   // 回滚当日占用，保持一致
            }
            long totalLimit = tenant.getTotalLimit() == null ? -1L : tenant.getTotalLimit();
            log.warn("累计配额超限：tenant={}, totalLimit={}", tenantId, totalLimit);
            throw new BizException(ErrorCode.TOTAL_LIMIT_EXCEEDED,
                    "累计生成次数已达上限 " + totalLimit + "，请联系管理员提升配额");
        }
    }

    /** 查询某租户当前配额状态（每日 + 累计 + 期限），用于前端展示。 */
    public QuotaStatus status(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            return new QuotaStatus(PlanType.FREE.name(), freeDailyLimit, 0, freeDailyLimit,
                    -1L, 0L, -1L, null, false);
        }
        PlanType plan = PlanType.parse(tenant.getPlan());
        int dailyLimit = resolveDailyLimit(tenant);
        Integer usedBox = quotaUsageMapper.currentUsed(tenantId, LocalDate.now());
        int dailyUsed = usedBox == null ? 0 : usedBox;
        int dailyRemaining = dailyLimit < 0 ? -1 : Math.max(0, dailyLimit - dailyUsed);

        long totalLimit = tenant.getTotalLimit() == null ? -1L : tenant.getTotalLimit();
        long totalUsed = tenant.getTotalUsed() == null ? 0L : tenant.getTotalUsed();
        long totalRemaining = totalLimit < 0 ? -1L : Math.max(0L, totalLimit - totalUsed);

        boolean expired = tenant.getExpireAt() != null && tenant.getExpireAt().isBefore(LocalDateTime.now());
        return new QuotaStatus(plan.name(), dailyLimit, dailyUsed, dailyRemaining,
                totalLimit, totalUsed, totalRemaining, tenant.getExpireAt(), expired);
    }

    /** 租户可用性门禁：停用或过期禁止问数（与登录门禁一致，防会话中途到期）。 */
    private void assertUsable(Tenant tenant) {
        if (tenant.getStatus() != null && tenant.getStatus() == 0) {
            throw new BizException(ErrorCode.TENANT_DISABLED);
        }
        if (tenant.getExpireAt() != null && tenant.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.TENANT_EXPIRED);
        }
    }

    /** 解析每日上限：优先取租户配置，为空时按套餐回退到默认兜底值。 */
    private int resolveDailyLimit(Tenant tenant) {
        if (tenant.getDailyLimit() != null) {
            return tenant.getDailyLimit();
        }
        return switch (PlanType.parse(tenant.getPlan())) {
            case PRO -> proDailyLimit;
            case ENTERPRISE -> -1;
            default -> freeDailyLimit;
        };
    }
}
