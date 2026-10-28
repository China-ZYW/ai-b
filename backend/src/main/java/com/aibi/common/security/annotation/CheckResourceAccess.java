package com.aibi.common.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源归属校验注解（见《角色权限体系与组织协同设计》6.2 / 4.3 数据范围）。
 *
 * <p>与 {@link RequiresPermission} 正交：后者判定"有没有这类操作的能力"，本注解判定
 * "对<b>这一个具体资源</b>有没有权限"——从方法入参提取资源 id，交由 {@code PermissionAspect}
 * 结合数据范围与 {@code resource_acl} 判定归属。典型用于 {@code @DeleteMapping("/{id}")}
 * 这类"能力具备、但资源可能属于别的团队/别人"的接口。
 *
 * <p><b>落地状态</b>：逐资源 ACL 行级判定已由 {@code ResourceAclService} 接入
 * （{@code PermissionAspect.checkResourceAccess} 调用 {@code canAccess}）；资源创建时由各自 Service
 * 经 {@code ResourceAclService.grant} 登记 principal 授权行（{@code tenant_id}/{@code resource_type}/
 * {@code resource_id}/{@code principal_type}/{@code principal_id}/{@code permission}），无授权行的
 * 历史资源按租户隔离 fail-open 放行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CheckResourceAccess {

    /** 资源类型：DATASOURCE / METRIC / DASHBOARD / SHARE 等，决定去哪张表加载归属。 */
    String type();

    /** 承载资源 id 的方法参数名（简单入参场景），默认 {@code id}。 */
    String idParam() default "id";

    /** 需要的操作级别：READ / WRITE / MANAGE，默认 READ。 */
    String permission() default "READ";

    /** 复杂入参用 SpEL 提取 id，如 {@code "#form.datasourceId()"}；非空时优先于 {@link #idParam()}。 */
    String idSpEL() default "";
}
