package com.aibi.common.security.annotation;

/**
 * 多值权限/角色注解的逻辑组合方式（见《角色权限体系与组织协同设计》6.2）。
 *
 * <p>{@link #AND} 要求全部具备（如"同时需要读与导出"），{@link #OR} 要求任一具备
 * （如"OWNER 或 ADMIN 皆可管理成员"）。默认值随注解语义不同：权限点默认 AND（更严），
 * 角色默认 OR（更贴合"多种角色任一即可"的直觉）。
 */
public enum Logical {

    /** 必须同时具备 {@code value} 中的全部项。 */
    AND,

    /** 具备 {@code value} 中的任意一项即可。 */
    OR
}
