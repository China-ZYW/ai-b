package com.aibi.common.security;

import com.aibi.common.exception.BizException;
import com.aibi.common.security.annotation.Logical;
import com.aibi.common.security.annotation.RequiresPermission;
import com.aibi.common.security.annotation.RequiresRole;
import com.aibi.common.security.annotation.RoleLevel;
import com.aibi.module.audit.service.AuditService;
import com.aibi.module.auth.mapper.TenantMapper;
import com.aibi.module.rbac.service.PermissionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PermissionAspect} 单测（T3 基线，纯 Mockito：不起 Spring、不连库）。
 *
 * <p>锁定四类判定语义：权限点 AND/OR 组合、方法级注解覆盖类级、ASSIST 影子令牌硬拒绝清单前置
 * （红线 2012，拒绝必须先于权限集查询）、{@code platformOnly} 防跨域提权；角色注解按
 * {@link RoleLevel} 只比对对应层角色集。每次拒绝都断言走统一出口
 * {@code deny}（抛 403 + 写 PERM_CHECK_DENIED 审计留痕）。
 */
class PermissionAspectTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final com.aibi.module.acl.service.ResourceAclService aclService =
            mock(com.aibi.module.acl.service.ResourceAclService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PermissionAspect aspect =
            new PermissionAspect(permissionService, tenantMapper, aclService, auditService);

    @AfterEach
    void tearDown() {
        AccessContextHolder.clear();
    }

    // ==================== 夹具：被切面拦截的"控制器" ====================

    @RequiresPermission("class:code:read")
    static class PermFixture {
        @RequiresPermission({"a:b:read", "a:b:export"})
        public void andPerm() {
        }

        @RequiresPermission(value = {"x:y:z", "x:q:w"}, logical = Logical.OR)
        public void orPerm() {
        }

        @RequiresPermission("member:invite:write")
        public void memberPerm() {
        }

        @RequiresPermission(value = "admin:user:write", platformOnly = true)
        public void platformPerm() {
        }

        /** 无方法级注解 → 应回落类级 class:code:read。 */
        public void plain() {
        }
    }

    static class NoAnnotationFixture {
        public void open() {
        }
    }

    @RequiresRole(value = {"OWNER", "ADMIN"}, level = RoleLevel.TENANT)
    static class RoleFixture {
        /** 无方法级注解 → 类级 TENANT 层 OR。 */
        public void inherited() {
        }

        @RequiresRole(value = {"plat:a", "plat:b"}, level = RoleLevel.PLATFORM)
        public void platformRole() {
        }

        @RequiresRole(value = {"r1", "r2"}, level = RoleLevel.TENANT, logical = Logical.AND)
        public void andRole() {
        }
    }

    // ==================== 工具 ====================

    private void setCtx(Long tenantId, TokenScope scope, Set<String> orgRoles, Set<String> platformRoles) {
        AccessContextHolder.set(AccessContext.builder()
                .uid(1L).username("tester").tenantId(tenantId)
                .tokenScope(scope).orgRoles(orgRoles).platformRoles(platformRoles)
                .build());
    }

    private ProceedingJoinPoint pjp(Object target, String methodName) throws Throwable {
        ProceedingJoinPoint p = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        Method m = target.getClass().getDeclaredMethod(methodName);
        when(p.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(m);
        when(p.getTarget()).thenReturn(target);
        when(p.proceed()).thenReturn("OK");
        return p;
    }

    // ==================== 权限点：AND / OR / 类级回落 ====================

    @Test
    void andPermissionGrantedProceeds() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of(), Set.of());
        when(permissionService.hasAll(any(),
                org.mockito.ArgumentMatchers.eq("a:b:read"),
                org.mockito.ArgumentMatchers.eq("a:b:export"))).thenReturn(true);
        assertEquals("OK", aspect.checkPermission(pjp(new PermFixture(), "andPerm")));
    }

    @Test
    void andPermissionDeniedThrowsAndAudits() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of(), Set.of());
        when(permissionService.hasAll(any(), any())).thenReturn(false);
        BizException ex = assertThrows(BizException.class,
                () -> aspect.checkPermission(pjp(new PermFixture(), "andPerm")));
        assertTrue(ex.getMessage().contains("缺少权限点"));
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("PERM_CHECK_DENIED"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void orLogicalUsesHasAny() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of(), Set.of());
        when(permissionService.hasAny(any(),
                org.mockito.ArgumentMatchers.eq("x:y:z"),
                org.mockito.ArgumentMatchers.eq("x:q:w"))).thenReturn(true);
        assertEquals("OK", aspect.checkPermission(pjp(new PermFixture(), "orPerm")));
        // AND 路径未被走过：若切面误用 hasAll（同样两码）会命中下面的 never 断言
        verify(permissionService, never()).hasAll(any(),
                org.mockito.ArgumentMatchers.eq("x:y:z"),
                org.mockito.ArgumentMatchers.eq("x:q:w"));
    }

    @Test
    void classLevelAnnotationIsFallbackWhenMethodHasNone() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of(), Set.of());
        when(permissionService.hasAll(any(), any())).thenReturn(true);
        assertEquals("OK", aspect.checkPermission(pjp(new PermFixture(), "plain")));
        verify(permissionService).hasAll(any(),
                org.mockito.ArgumentMatchers.eq("class:code:read"));
    }

    @Test
    void noAnnotationAnywhereProceedsUnharmed() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of(), Set.of());
        assertEquals("OK", aspect.checkPermission(pjp(new NoAnnotationFixture(), "open")));
        verifyNoInteractions(permissionService);
    }

    // ==================== ASSIST 红线：先于权限集判定 ====================

    @Test
    void assistTokenHardDeniedBeforePermissionLookup() throws Throwable {
        setCtx(1L, TokenScope.ASSIST, Set.of(), Set.of());
        BizException ex = assertThrows(BizException.class,
                () -> aspect.checkPermission(pjp(new PermFixture(), "memberPerm")));
        assertTrue(ex.getMessage().contains("协助令牌"));
        // 硬拒绝清单命中必须直接拒，连权限服务都不查（字典误配也拦得住）
        verifyNoInteractions(permissionService);
    }

    @Test
    void assistTokenOutsideDenyListStillChecksPermission() throws Throwable {
        setCtx(1L, TokenScope.ASSIST, Set.of(), Set.of());
        when(permissionService.hasAll(any(),
                org.mockito.ArgumentMatchers.eq("a:b:read"),
                org.mockito.ArgumentMatchers.eq("a:b:export"))).thenReturn(true);
        assertEquals("OK", aspect.checkPermission(pjp(new PermFixture(), "andPerm")));
    }

    // ==================== platformOnly 防跨域提权 ====================

    @Test
    void platformOnlyRejectsTenantIdentity() throws Throwable {
        setCtx(5L, TokenScope.NORMAL, Set.of(), Set.of());
        BizException ex = assertThrows(BizException.class,
                () -> aspect.checkPermission(pjp(new PermFixture(), "platformPerm")));
        assertTrue(ex.getMessage().contains("平台身份"));
        verifyNoInteractions(permissionService);
    }

    @Test
    void platformOnlyAcceptsPlatformIdentity() throws Throwable {
        setCtx(0L, TokenScope.NORMAL, Set.of(), Set.of());
        when(permissionService.hasAll(any(),
                org.mockito.ArgumentMatchers.eq("admin:user:write"))).thenReturn(true);
        assertEquals("OK", aspect.checkPermission(pjp(new PermFixture(), "platformPerm")));
    }

    @Test
    void unauthenticatedIsRejected() {
        AccessContextHolder.clear();
        BizException ex = assertThrows(BizException.class, () -> {
            ProceedingJoinPoint p = pjp(new PermFixture(), "andPerm");
            aspect.checkPermission(p);
        });
        assertTrue(ex.getMessage().contains("未认证"));
    }

    // ==================== 角色：分层比对 + AND/OR ====================

    @Test
    void roleTenantOrMatchesAnyOwnedRole() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of("ADMIN"), Set.of());
        assertEquals("OK", aspect.checkRole(pjp(new RoleFixture(), "inherited")));
    }

    @Test
    void roleDeniedWhenNoMatchAndAudited() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of("MEMBER"), Set.of());
        BizException ex = assertThrows(BizException.class,
                () -> aspect.checkRole(pjp(new RoleFixture(), "inherited")));
        assertTrue(ex.getMessage().contains("缺少角色"));
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("PERM_CHECK_DENIED"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void rolePlatformLevelOnlyComparesPlatformRoles() throws Throwable {
        // 组织域角色持有 plat:a 不应命中——层级隔离防跨层同名误判
        setCtx(1L, TokenScope.NORMAL, Set.of("plat:a"), Set.of());
        assertThrows(BizException.class,
                () -> aspect.checkRole(pjp(new RoleFixture(), "platformRole")));

        setCtx(0L, TokenScope.NORMAL, Set.of(), Set.of("plat:a"));
        assertEquals("OK", aspect.checkRole(pjp(new RoleFixture(), "platformRole")));
    }

    @Test
    void roleAndLogicRequiresAllRoles() throws Throwable {
        setCtx(1L, TokenScope.NORMAL, Set.of("r1"), Set.of());
        assertThrows(BizException.class,
                () -> aspect.checkRole(pjp(new RoleFixture(), "andRole")));

        setCtx(1L, TokenScope.NORMAL, Set.of("r1", "r2"), Set.of());
        assertEquals("OK", aspect.checkRole(pjp(new RoleFixture(), "andRole")));
    }
}
