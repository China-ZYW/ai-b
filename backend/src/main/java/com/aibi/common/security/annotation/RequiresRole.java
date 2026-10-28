package com.aibi.common.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色校验注解（见《角色权限体系与组织协同设计》6.2）。
 *
 * <p>用于"整个角色"而非"单个权限点"的语义，如 OWNER 专属的转让/解散、平台 SUPER_ADMIN 专属的角色授予。
 * 与 {@link RequiresPermission} 的区别：后者比对展开后的权限码集合（细粒度、可被自定义角色组合），
 * 本注解直接比对角色码（粗粒度、表达"身份"而非"能力"）。
 *
 * <p>{@link #level()} 决定比对哪一层角色集，避免跨层同名码误命中；默认按 {@link Logical#OR}
 * （列出多个角色时任一即可，符合"OWNER 或 ADMIN 皆可"的常见语义）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {

    /** 需要的角色码；配合 {@link #logical()} 决定 AND/OR。 */
    String[] value();

    /** 角色所属层级，默认组织域（TENANT）。 */
    RoleLevel level() default RoleLevel.TENANT;

    /** 多角色的组合方式，默认 OR（任一即可）。 */
    Logical logical() default Logical.OR;
}
