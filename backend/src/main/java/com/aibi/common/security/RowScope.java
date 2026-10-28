package com.aibi.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 行级数据范围标注：声明某 Mapper（类级）或方法（方法级）的查询结果需按当前访问上下文的
 * {@link com.aibi.common.tenant.DataScope} 收敛可见行。
 *
 * <p>仅当上下文数据范围为 {@code SELF} 且标注了 {@link #uidColumn()} 时，{@link AibiDataScopeHandler}
 * 会在 SQL 上追加 {@code uidColumn = #{当前uid}}；其余范围（TENANT/ALL/CUSTOM/TEAM*）本轮暂不追加
 * 行级条件（退化为仅租户隔离，见设计文档 4.3 的"暂未排期"项），避免误伤或越权。
 *
 * <p>未标注的 Mapper/方法：拦截器不注入任何额外条件，仅保留 {@code tenant_id} 租户隔离。
 *
 * <p><b>命名说明</b>：与枚举 {@code com.aibi.common.tenant.DataScope} 同名易冲突，故本注解取
 * {@code RowScope}；语义上它就是"行级范围"的声明点。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RowScope {

    /** SELF 范围使用的"创建者列"名（如 {@code query_history} 的 {@code uid}）。 */
    String uidColumn() default "";

    /** TEAM/TEAM_AND_CHILD 范围使用的"团队列"名（本轮未启用，预留接口）。 */
    String teamColumn() default "";
}
