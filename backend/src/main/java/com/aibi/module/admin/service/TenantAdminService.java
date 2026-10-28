package com.aibi.module.admin.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.tenant.PlanType;
import com.aibi.module.admin.dto.SeatQuotaForm;
import com.aibi.module.admin.dto.TenantForm;
import com.aibi.module.admin.dto.TenantVO;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.entity.User;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.auth.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户管理服务（超管后台）：租户 CRUD、套餐/隔离模式、使用期限与配额（每日/累计）配置、启停。
 *
 * <p>{@code tenant}/{@code app_user} 均在租户拦截器 IGNORE 名单，故此处按显式条件跨租户操作，不受当前上下文租户限制。
 * 更新一律用 {@code lambdaUpdate().set(...)} 精确写列：既允许把 {@code expire_at} 清空为 null（不过期），
 * 也避免 {@code updateById} 回写 {@code total_used} 造成的丢失更新。
 */
@Service
public class TenantAdminService {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public TenantAdminService(TenantMapper tenantMapper, UserMapper userMapper, AuditService auditService) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    public List<TenantVO> list() {
        return tenantMapper.selectList(Wrappers.<Tenant>lambdaQuery().orderByDesc(Tenant::getId))
                .stream().map(this::toVO).toList();
    }

    public TenantVO get(Long id) {
        return toVO(requireTenant(id));
    }

    @Transactional
    public TenantVO create(TenantForm form) {
        PlanType plan = PlanType.parse(form.plan());
        Tenant tenant = new Tenant();
        tenant.setName(form.name());
        tenant.setPlan(plan.name());
        tenant.setIsolationMode(resolveIsolation(form.isolationMode(), plan));
        tenant.setStatus(form.status() == null ? 1 : form.status());
        tenant.setExpireAt(form.expireAt());
        tenant.setDailyLimit(form.dailyLimit() == null ? defaultDailyLimit(plan) : form.dailyLimit());
        tenant.setTotalLimit(form.totalLimit() == null ? -1L : form.totalLimit());
        tenant.setTotalUsed(0L);
        tenant.setDatasourceLimit(plan.defaultDatasourceLimit());
        tenantMapper.insert(tenant);
        return toVO(tenant);
    }

    @Transactional
    public TenantVO update(Long id, TenantForm form) {
        Tenant tenant = requireTenant(id);
        PlanType plan = PlanType.parse(form.plan());
        String isolation = (form.isolationMode() != null && !form.isolationMode().isBlank())
                ? form.isolationMode() : tenant.getIsolationMode();
        tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, id)
                .set(Tenant::getName, form.name())
                .set(Tenant::getPlan, plan.name())
                .set(Tenant::getIsolationMode, isolation)
                .set(Tenant::getStatus, form.status() == null ? tenant.getStatus() : form.status())
                .set(Tenant::getExpireAt, form.expireAt())   // 显式 set：允许清空为“不过期”
                .set(Tenant::getDailyLimit, form.dailyLimit() == null ? tenant.getDailyLimit() : form.dailyLimit())
                .set(Tenant::getTotalLimit, form.totalLimit() == null ? tenant.getTotalLimit() : form.totalLimit()));
        return get(id);
    }

    @Transactional
    public TenantVO setStatus(Long id, Integer status) {
        requireTenant(id);
        tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, id).set(Tenant::getStatus, status));
        // 停用（status=0）为高危动作：记审计，事件归属受影响租户 id（便于其组织内检索）
        if (status != null && status == 0) {
            auditService.record(AuditAction.TENANT_DISABLE, AuditAction.RESULT_OK, id, null,
                    "TENANT", String.valueOf(id), Map.of("status", status));
        }
        return get(id);
    }

    /**
     * 逐租户席位/配额调整（§9.2 席位与配额）。只写 {@code seat_limit}（必填）与
     * 非空的 {@code daily_limit}/{@code total_limit}；不碰 {@code seat_used}（由邀请/成员事务维护）。
     * 各值域 {@code >= -1}（-1=不限），非法招 {@code PARAM_ERROR}。
     */
    @Transactional
    public TenantVO updateSeats(Long id, SeatQuotaForm form) {
        Tenant before = requireTenant(id);
        var update = Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, id)
                .set(Tenant::getSeatLimit, requireLimit(form.seatLimit(), "席位上限"));
        if (form.dailyLimit() != null) {
            update.set(Tenant::getDailyLimit, requireLimit(form.dailyLimit(), "每日上限"));
        }
        if (form.totalLimit() != null) {
            requireLimit(form.totalLimit().intValue(), "累计上限");
            update.set(Tenant::getTotalLimit, form.totalLimit());
        }
        if (form.datasourceLimit() != null) {
            update.set(Tenant::getDatasourceLimit, requireLimit(form.datasourceLimit(), "数据源上限"));
        }
        tenantMapper.update(null, update);

        // detail 记变更前后值（仅带上本次实际修改的项），事件归属受影响租户 id
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("seatLimitBefore", before.getSeatLimit());
        detail.put("seatLimitAfter", form.seatLimit());
        if (form.dailyLimit() != null) {
            detail.put("dailyLimitBefore", before.getDailyLimit());
            detail.put("dailyLimitAfter", form.dailyLimit());
        }
        if (form.totalLimit() != null) {
            detail.put("totalLimitBefore", before.getTotalLimit());
            detail.put("totalLimitAfter", form.totalLimit());
        }
        if (form.datasourceLimit() != null) {
            detail.put("datasourceLimitBefore", before.getDatasourceLimit());
            detail.put("datasourceLimitAfter", form.datasourceLimit());
        }
        auditService.record(AuditAction.SEAT_CHANGE, AuditAction.RESULT_OK, id, null,
                "TENANT", String.valueOf(id), detail);

        return get(id);
    }

    /**
     * 批量按套餐重置席位与每日上限（§9.2“批量按套餐重置”）。
     *
     * <p>{@code plan} 为空则重置全部租户，否则仅重置该套餐档。重置值取自 {@link PlanType}：
     * {@code seat_limit=defaultSeatLimit()}、{@code daily_limit=defaultDailyLimit()}。
     * <b>不重置 {@code total_limit} 与 {@code seat_used}</b>：前者是合同级累计额度、后者是实际在册数，
     * 均不宜被批量操作覆盖。返回受影响租户数。
     */
    @Transactional
    public int resetSeatsByPlan(String plan) {
        var query = Wrappers.<Tenant>lambdaQuery();
        if (plan != null && !plan.isBlank()) {
            query.eq(Tenant::getPlan, PlanType.parse(plan).name());
        }
        List<Tenant> tenants = tenantMapper.selectList(query);
        for (Tenant t : tenants) {
            PlanType p = PlanType.parse(t.getPlan());
            tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                    .eq(Tenant::getId, t.getId())
                    .set(Tenant::getSeatLimit, p.defaultSeatLimit())
                    .set(Tenant::getDailyLimit, defaultDailyLimit(p)));
        }
        return tenants.size();
    }

    @Transactional
    public void delete(Long id) {
        requireTenant(id);
        // 先删该租户下账号，再删租户（schema 无外键约束，其余业务数据成为孤儿，可后续清理）
        userMapper.delete(Wrappers.<User>lambdaQuery().eq(User::getTenantId, id));
        tenantMapper.deleteById(id);
    }

    private Tenant requireTenant(Long id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "租户不存在: " + id);
        }
        return tenant;
    }

    private TenantVO toVO(Tenant t) {
        long userCount = userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getTenantId, t.getId()));
        return new TenantVO(t.getId(), t.getName(), t.getPlan(), t.getIsolationMode(), t.getStatus(),
                t.getExpireAt(), t.getDailyLimit(), t.getTotalLimit(), t.getTotalUsed(),
                t.getSeatLimit(), t.getSeatUsed(), userCount, t.getCreatedAt());
    }

    /** 限额值域校验：{@code -1} 表示不限，否则须 {@code >= 0}。 */
    private int requireLimit(Integer v, String label) {
        if (v == null || v < -1) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "非法（-1=不限，否则须 >= 0）");
        }
        return v;
    }

    private String resolveIsolation(String mode, PlanType plan) {
        return (mode == null || mode.isBlank()) ? plan.defaultIsolationMode().name() : mode;
    }

    private int defaultDailyLimit(PlanType plan) {
        return switch (plan) {
            case PRO -> 1000;
            case ENTERPRISE -> -1;
            default -> 20;
        };
    }
}
