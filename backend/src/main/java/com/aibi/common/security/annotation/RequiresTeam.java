package com.aibi.common.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 团队上下文校验注解（见《角色权限体系与组织协同设计》6.2）。
 *
 * <p>标注在需要"当前团队"的接口上：若 {@code AccessContext.currentTeamId()} 为空，
 * 按 {@link #allowFallbackToDefault()} 决定是回落到 {@code tenant.default_team_id} 还是直接拒绝
 * （{@code 40306}，前端据此弹出团队选择器）。回落成功时会把默认团队写回当前线程上下文，
 * 使后续数据范围收敛与 {@code team_id} 归属一致。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresTeam {

    /** {@code true}=未选团队时回落 {@code tenant.default_team_id}；{@code false}=未选团队直接拒绝。 */
    boolean allowFallbackToDefault() default true;
}
