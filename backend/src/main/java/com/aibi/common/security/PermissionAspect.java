package com.aibi.common.security;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.common.security.annotation.CheckResourceAccess;
import com.aibi.common.security.annotation.Logical;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.security.annotation.RequiresRole;
import com.aibi.common.security.annotation.RequiresTeam;
import com.aibi.common.security.annotation.RoleLevel;
import com.aibi.module.audit.AuditAction;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.entity.Tenant;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.acl.service.ResourceAclService;
import com.aibi.module.rbac.service.PermissionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * 权限切面（L2 方法级门禁，见《角色权限体系与组织协同设计》6.2 / 4.4 判定管线④）。
 *
 * <p>{@code @Order(20)}：早于业务方法、晚于事务边界建立，确保拒绝时不产生任何写副作用。
 * 拦截四类注解——{@link RequiresPermission}（权限点）、{@link RequiresRole}（角色）、
 * {@link RequiresTeam}（团队上下文）、{@link CheckResourceAccess}（资源归属）。
 *
 * <p><b>ASSIST 硬拒绝清单前置</b>（红线 2012）：代客协助影子令牌在权限点判定之前先过硬编码拒绝清单，
 * 即使权限字典被误配也无法越界；{@link PermissionService} 亦会从有效权限集中剥离同类码（纵深防御）。
 *
 * <p><b>审计钩子</b>：拒绝事件（{@code PERM_CHECK_DENIED}/{@code DATA_SCOPE_DENIED}/{@code ACL_DENIED}）
 * 经统一出口 {@link #deny} 同时写 warn 日志（含结构化标记，便于检索）与 {@code AuditService}
 * （{@code result=DENIED}，操作人/ip/ua 自动从当前上下文捕获），使每次 403 都留痕于 {@code auth_audit_log}。
 */
@Aspect
@Component
@Order(20)
public class PermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAspect.class);

    private final PermissionService permissionService;
    private final TenantMapper tenantMapper;
    private final ResourceAclService resourceAclService;
    private final AuditService auditService;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    public PermissionAspect(PermissionService permissionService, TenantMapper tenantMapper,
                            ResourceAclService resourceAclService, AuditService auditService) {
        this.permissionService = permissionService;
        this.tenantMapper = tenantMapper;
        this.resourceAclService = resourceAclService;
        this.auditService = auditService;
    }

    // ==================== 功能权限点 ====================

    /**
     * 方法级或类级 {@link RequiresPermission}（方法级优先）。
     *
     * <p><b>不用 {@code @annotation(rp) || @within(rp)} 的参数绑定</b>：Spring AOP 对"同一绑定名出现在
     * OR 两侧"的解析不可靠——命中时绑定形参可能为 {@code null}，切面内随即 NPE（本类早期版本正是如此，
     * 直到 fail-closed 域开始挂注解才暴露）。改为无绑定名点匹配 + 手动解析，语义明确且与
     * {@code ApiPermissionRegistry.resolvePermCodes} 的"方法级优先、回落类级"口径一致。
     */
    @Around("@annotation(com.aibi.common.security.annotation.RequiresPermission)"
            + " || @within(com.aibi.common.security.annotation.RequiresPermission)")
    public Object checkPermission(ProceedingJoinPoint pjp) throws Throwable {
        RequiresPermission rp = resolveRequiresPermission(pjp);
        if (rp == null) {
            return pjp.proceed();
        }
        AccessContext ctx = AccessContextHolder.require();

        // 红线：ASSIST 影子令牌触碰硬拒绝清单，无需再看权限集，直接拒
        if (ctx.isAssist()) {
            for (String code : rp.value()) {
                if (PermissionService.assistDenied(code)) {
                    return deny("PERM_CHECK_DENIED", ctx,
                            "协助令牌禁止调用该操作: " + code);
                }
            }
        }
        if (rp.platformOnly() && !ctx.isPlatform()) {
            return deny("PERM_CHECK_DENIED", ctx, "非平台身份访问平台接口");
        }

        boolean ok = rp.logical() == Logical.AND
                ? permissionService.hasAll(ctx, rp.value())
                : permissionService.hasAny(ctx, rp.value());
        if (!ok) {
            return deny("PERM_CHECK_DENIED", ctx, "缺少权限点 " + Arrays.toString(rp.value()));
        }
        return pjp.proceed();
    }

    /** 从连接点解析 {@link RequiresPermission}：方法级优先、回落目标类级；均无返回 {@code null}。 */
    private RequiresPermission resolveRequiresPermission(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresPermission rp = method.getAnnotation(RequiresPermission.class);
        if (rp != null) {
            return rp;
        }
        return userClass(pjp, method).getAnnotation(RequiresPermission.class);
    }

    // ==================== 角色 ====================

    /** 方法级或类级 {@link RequiresRole}：按 {@link RoleLevel} 只比对对应层的角色集（解析方式同 checkPermission）。 */
    @Around("@annotation(com.aibi.common.security.annotation.RequiresRole)"
            + " || @within(com.aibi.common.security.annotation.RequiresRole)")
    public Object checkRole(ProceedingJoinPoint pjp) throws Throwable {
        RequiresRole rr = resolveRequiresRole(pjp);
        if (rr == null) {
            return pjp.proceed();
        }
        AccessContext ctx = AccessContextHolder.require();
        Set<String> owned = switch (rr.level()) {
            case PLATFORM -> ctx.platformRoles();
            case TEAM -> ctx.teamRoles();
            case TENANT -> ctx.orgRoles();
        };
        boolean ok = rr.logical() == Logical.AND
                ? owned.containsAll(Arrays.asList(rr.value()))
                : Arrays.stream(rr.value()).anyMatch(owned::contains);
        if (!ok) {
            return deny("PERM_CHECK_DENIED", ctx,
                    "缺少角色 " + Arrays.toString(rr.value()) + "（层级 " + rr.level() + "）");
        }
        return pjp.proceed();
    }

    /** 从连接点解析 {@link RequiresRole}：方法级优先、回落目标类级；均无返回 {@code null}。 */
    private RequiresRole resolveRequiresRole(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequiresRole rr = method.getAnnotation(RequiresRole.class);
        if (rr != null) {
            return rr;
        }
        return userClass(pjp, method).getAnnotation(RequiresRole.class);
    }

    /** 目标用户类（剥离 CGLIB 代理子类），用于类级注解回落查找。 */
    private Class<?> userClass(ProceedingJoinPoint pjp, Method method) {
        Object target = pjp.getTarget();
        return target != null ? ClassUtils.getUserClass(target) : method.getDeclaringClass();
    }

    // ==================== 团队上下文 ====================

    /**
     * {@link RequiresTeam}：要求已选定团队；未选时按 {@code allowFallbackToDefault} 回落
     * {@code tenant.default_team_id} 并把结果写回当前线程上下文，仍无团队则拒绝（{@code 40306}）。
     */
    @Around("@annotation(rt)")
    public Object checkTeam(ProceedingJoinPoint pjp, RequiresTeam rt) throws Throwable {
        AccessContext ctx = AccessContextHolder.require();
        if (ctx.currentTeamId() != null) {
            return pjp.proceed();
        }
        if (rt.allowFallbackToDefault() && ctx.isTenantScope()) {
            Long defaultTeamId = resolveDefaultTeam(ctx.tenantId());
            if (defaultTeamId != null) {
                // 写回上下文，使后续数据范围收敛与 team_id 归属基于同一团队
                AccessContextHolder.set(ctx.withCurrentTeam(defaultTeamId));
                return pjp.proceed();
            }
        }
        throw new BizException(ErrorCode.TEAM_MISSING, "请先选择团队后再执行该操作");
    }

    private Long resolveDefaultTeam(Long tenantId) {
        try {
            Tenant tenant = tenantMapper.selectById(tenantId);
            return tenant == null ? null : tenant.getDefaultTeamId();
        } catch (Exception e) {
            log.warn("回落默认团队失败: tenant={}, err={}", tenantId, e.getMessage());
            return null;
        }
    }

    // ==================== 资源归属 ====================

    /**
     * {@link CheckResourceAccess}：从入参提取资源 id，ASSIST 令牌对 {@code WRITE}/{@code MANAGE} 硬拒绝，
     * 随后做逐资源行级 ACL 归属比对（{@code ResourceAclService.canAccess}）。
     *
     * <p><b>落地状态</b>：已完成 id 提取（{@code idParam}/{@code idSpEL}）、ASSIST 写红线，以及行级 ACL
     * 归属比对——资源创建时由各自 Service 经 {@code ResourceAclService.grant} 登记 principal 授权行，此处据
     * {@code (tenant_id, principal_type, principal_id, permission)} 判定越权（无授权行的历史资源按租户隔离 fail-open）。
     */
    @Around("@annotation(cra)")
    public Object checkResourceAccess(ProceedingJoinPoint pjp, CheckResourceAccess cra) throws Throwable {
        AccessContext ctx = AccessContextHolder.require();
        Object resourceId = resolveResourceId(pjp, cra);
        boolean mutating = "WRITE".equalsIgnoreCase(cra.permission()) || "MANAGE".equalsIgnoreCase(cra.permission());
        if (ctx.isAssist() && mutating) {
            return deny("ACL_DENIED", ctx,
                    "协助令牌禁止写资源: type=" + cra.type() + ", id=" + resourceId);
        }
        // 逐资源行级 ACL 归属比对（已由 ResourceAclService 接入）：未通过则拒绝
        if (resourceId != null && !resourceAclService.canAccess(ctx, cra.type(), resourceId, cra.permission())) {
            return deny("ACL_DENIED", ctx,
                    "无权访问资源: type=" + cra.type() + ", id=" + resourceId);
        }
        return pjp.proceed();
    }

    /** 按 {@code idSpEL}（优先）或 {@code idParam} 从方法入参提取资源 id；提取不到返回 {@code null}。 */
    private Object resolveResourceId(ProceedingJoinPoint pjp, CheckResourceAccess cra) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Object[] args = pjp.getArgs();
        String[] names = paramNameDiscoverer.getParameterNames(method);
        if (names == null) {
            return null;
        }
        if (cra.idSpEL() != null && !cra.idSpEL().isBlank()) {
            EvaluationContext ec = new StandardEvaluationContext();
            for (int i = 0; i < names.length && i < args.length; i++) {
                ec.setVariable(names[i], args[i]);
            }
            try {
                return spelParser.parseExpression(cra.idSpEL()).getValue(ec);
            } catch (Exception e) {
                log.warn("资源 id 的 SpEL 解析失败: expr={}, err={}", cra.idSpEL(), e.getMessage());
                return null;
            }
        }
        for (int i = 0; i < names.length && i < args.length; i++) {
            if (names[i].equals(cra.idParam())) {
                return args[i];
            }
        }
        return null;
    }

    // ==================== 统一拒绝出口 ====================

    /**
     * 统一拒绝出口：写审计（{@code AuditService}，{@code result=DENIED}）+ warn 留痕 + 抛
     * {@link BizException}（由 {@code GlobalExceptionHandler} 转 {@code 40301} JSON）。
     *
     * <p>审计写入异步、全程 try/catch 只告警，绝不因审计失败而改变拒绝语义或阻断响应。
     *
     * @param auditAction 审计动作标记（{@code PERM_CHECK_DENIED}/{@code ACL_DENIED} 等）
     */
    private Object deny(String auditAction, AccessContext ctx, String reason) {
        log.warn("[{}] 权限拒绝: uid={}, tenant={}, team={}, scope={}, reason={}",
                auditAction, ctx.uid(), ctx.tenantId(), ctx.currentTeamId(), ctx.tokenScope(), reason);
        auditService.record(auditAction, AuditAction.RESULT_DENIED, ctx.tenantId(), null,
                "PERMISSION", null, Map.of("reason", reason));
        throw new BizException(ErrorCode.FORBIDDEN, reason);
    }
}
