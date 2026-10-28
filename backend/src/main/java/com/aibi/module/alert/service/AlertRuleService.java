package com.aibi.module.alert.service;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.AccessContext;
import com.aibi.common.security.AccessContextHolder;
import com.aibi.module.alert.dto.AlertRuleForm;
import com.aibi.module.alert.entity.AlertRule;
import com.aibi.module.alert.mapper.AlertRuleMapper;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 预警规则 CRUD（组织管理侧）。仅组织管理员（OWNER/ADMIN）可操作，鉴权由 {@code assertManager} 统一把关，
 * 不依赖新增 RBAC 权限码（避免未播种导致全拒）。所有写操作均审计（见 {@link AuditAction} ALERT_*）。
 *
 * <p>租户隔离：{@code alert_rule} 不在 IGNORE 名单，{@code selectById/deleteById/updateById} 自动注入
 * {@code tenant_id}（来自当前访问上下文），跨租户规则天然查不到 → 直接按「不存在」拒绝。
 */
@Service
public class AlertRuleService {

    private final AlertRuleMapper ruleMapper;
    private final AuditService auditService;

    public AlertRuleService(AlertRuleMapper ruleMapper, AuditService auditService) {
        this.ruleMapper = ruleMapper;
        this.auditService = auditService;
    }

    private void assertManager() {
        AccessContext ctx = AccessContextHolder.require();
        if (!ctx.canManageMembers()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅组织管理员可管理预警规则");
        }
    }

    public List<AlertRule> list() {
        AccessContextHolder.require().requireTenantId();
        return ruleMapper.selectList(new LambdaQueryWrapper<AlertRule>().orderByDesc(AlertRule::getCreatedAt));
    }

    @Transactional
    public AlertRule create(AlertRuleForm form) {
        assertManager();
        Long tid = AccessContextHolder.require().requireTenantId();
        AlertRule rule = new AlertRule();
        rule.setTenantId(tid);
        rule.setName(form.name());
        rule.setDescription(form.description());
        rule.setMetricKey(form.metricKey());
        rule.setOperator(form.operator());
        rule.setThreshold(form.threshold());
        rule.setSeverity(form.severity() == null ? "WARN" : form.severity());
        rule.setEnabled(form.enabled() == null || form.enabled());
        String channel = form.notifyChannel() == null ? "LOG" : form.notifyChannel();
        String target = blankToNull(form.notifyTarget());
        validateNotify(channel, target);
        rule.setNotifyChannel(channel);
        rule.setNotifyTarget(target);
        rule.setWindowMinutes(form.windowMinutes() == null ? 1440 : form.windowMinutes());
        rule.setAutoAttribution(form.autoAttribution() != null && form.autoAttribution());
        rule.setAttributionDimension(blankToNull(form.attributionDimension()));
        rule.setCreatedBy(AccessContextHolder.require().uid());
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.insert(rule);
        auditService.record(AuditAction.ALERT_RULE_CREATE, AuditAction.RESULT_OK, tid, null,
                "ALERT_RULE", String.valueOf(rule.getId()), Map.of("name", rule.getName()));
        return rule;
    }

    @Transactional
    public AlertRule update(Long id, AlertRuleForm form) {
        assertManager();
        Long tid = AccessContextHolder.require().requireTenantId();
        AlertRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "预警规则不存在或无权访问");
        }
        if (form.name() != null) {
            rule.setName(form.name());
        }
        if (form.description() != null) {
            rule.setDescription(form.description());
        }
        if (form.metricKey() != null) {
            rule.setMetricKey(form.metricKey());
        }
        if (form.operator() != null) {
            rule.setOperator(form.operator());
        }
        if (form.threshold() != null) {
            rule.setThreshold(form.threshold());
        }
        if (form.severity() != null) {
            rule.setSeverity(form.severity());
        }
        if (form.enabled() != null) {
            rule.setEnabled(form.enabled());
        }
        // 渠道与目标需一起校验：update 是"按需覆盖"，若只改了渠道，生效目标要取库里已有的值再判断
        String nextChannel = form.notifyChannel() != null ? form.notifyChannel() : rule.getNotifyChannel();
        String nextTarget = form.notifyTarget() != null ? blankToNull(form.notifyTarget()) : rule.getNotifyTarget();
        validateNotify(nextChannel, nextTarget);
        if (form.notifyChannel() != null) {
            rule.setNotifyChannel(form.notifyChannel());
        }
        if (form.notifyTarget() != null) {
            // 显式传空串 = 清空目标（与 attributionDimension 同款语义）
            rule.setNotifyTarget(blankToNull(form.notifyTarget()));
        }
        if (form.windowMinutes() != null) {
            rule.setWindowMinutes(form.windowMinutes());
        }
        if (form.autoAttribution() != null) {
            rule.setAutoAttribution(form.autoAttribution());
        }
        if (form.attributionDimension() != null) {
            // 显式传空串表示"清空维度、回落到指标默认维度"，故此处不再过滤空值
            rule.setAttributionDimension(blankToNull(form.attributionDimension()));
        }
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);
        auditService.record(AuditAction.ALERT_RULE_UPDATE, AuditAction.RESULT_OK, tid, null,
                "ALERT_RULE", String.valueOf(id), Map.of("name", rule.getName()));
        return rule;
    }

    @Transactional
    public void delete(Long id) {
        assertManager();
        Long tid = AccessContextHolder.require().requireTenantId();
        AlertRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "预警规则不存在或无权访问");
        }
        ruleMapper.deleteById(id);
        auditService.record(AuditAction.ALERT_RULE_DELETE, AuditAction.RESULT_OK, tid, null,
                "ALERT_RULE", String.valueOf(id), Map.of("name", rule.getName()));
    }

    /** 空白串归一为 null：避免前端传 "" 导致"维度被设成空串"而非"回落到指标默认维度"。 */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 通知渠道与目标的一致性校验：选了 EMAIL 却不填收件人，这条规则<b>永远发不出去</b>
     * ——属于"配置看起来正常、通知其实从未送达"的静默失败，在入口就拦掉，别等到告警发生时才发现。
     *
     * <p>注意这里只校验已接入渠道（EMAIL）；SMS/WEBHOOK 尚未接入，选了会由
     * {@code AlertNotifierRouter} 回落到兜底渠道，故不在此处要求填目标。
     */
    private static void validateNotify(String channel, String target) {
        if ("EMAIL".equalsIgnoreCase(channel == null ? "" : channel.trim())
                && (target == null || target.isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "通知渠道为「邮件」时必须填写收件人邮箱");
        }
    }

    @Transactional
    public AlertRule setEnabled(Long id, boolean enabled) {
        assertManager();
        AlertRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "预警规则不存在或无权访问");
        }
        rule.setEnabled(enabled);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);
        return rule;
    }
}
