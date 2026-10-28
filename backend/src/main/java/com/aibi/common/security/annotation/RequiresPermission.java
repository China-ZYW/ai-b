package com.aibi.common.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 功能权限点校验注解（L2 方法级门禁，见《角色权限体系与组织协同设计》6.2）。
 *
 * <p>由 {@code PermissionAspect} 拦截：从 {@code AccessContext} 解析当前身份的有效权限码集合，
 * 与 {@link #value()} 按 {@link #logical()} 比对，不满足即抛 {@code 40301}。
 *
 * <p>可标注在<b>类</b>（该 Controller 全部方法的兜底权限，如"读权限"）或<b>方法</b>（覆盖类级）上；
 * 方法级优先于类级，与文档示例一致（类级 {@code datasource:config:read}、写方法覆盖为
 * {@code datasource:config:write}）。
 *
 * <p>{@link #platformOnly()} 用于平台域接口：要求上下文 {@code tenantId==0}，
 * 防止组织身份越权访问 {@code admin:*}/{@code ops:*} 接口（3.6① 防跨域提权）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 需要的权限点码（{@code module:resource:action}）；配合 {@link #logical()} 决定 AND/OR。 */
    String[] value();

    /** 多权限点的组合方式，默认 AND（全部具备）。 */
    Logical logical() default Logical.AND;

    /** 平台域接口：要求 {@code ctx.isPlatform()}（{@code tenantId==0}），否则直接拒绝。 */
    boolean platformOnly() default false;
}
