package com.aibi.module.rbac.dto;

/**
 * 自定义角色创建/更新表单（《组织治理专项设计》§2.5/§2.7）。
 *
 * <p>创建时 {@code name}/{@code baseRoleCode} 必填；更新时各字段均可为空（空=不改）。
 * 校验集中在 {@code CustomRoleService}（名称非空、base 可授予、不可改 base、data_scope 合法），
 * 故此表单不挂 jakarta 校验注解，避免更新时被「非空」校验误伤。
 *
 * <p>{@code dataScope} 写入值须为合法 {@code DataScope} 且非跨租户范围；v1 实际收敛仍按 base 角色的
 * data_scope（自定义角色的收窄列入 P2，见设计文档 §2.6），此处仅登记合法值。
 */
public record CustomRoleForm(
        String name,
        String baseRoleCode,
        String description,
        Integer enabled,
        String dataScope) {
}
