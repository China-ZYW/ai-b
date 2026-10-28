package com.aibi.module.org.dto;

/**
 * 成员持有的「自定义角色」授予摘要（《组织治理专项设计》§2）。
 *
 * <p>{@code user_role} 对自定义角色授予行存 {@code role_code}=base 码 + {@code custom_role_id}=引用。
 * 仅凭 base 码无法区分「内置角色授予」与「自定义角色授予」，也无法回收后者
 * （{@code revokeRole} 按 {@code custom_role_id} 精确收敛）。本摘要把 {@code customRoleId} 透出给前端，
 * 使其能正确展示「自定义角色」标签并按 id 精确回收。
 *
 * @param id           自定义角色 id（{@code custom_role.id}，回收时按此精确定位）
 * @param name         自定义角色名称
 * @param baseRoleCode 派生自的内置组织角色码
 */
public record MemberCustomRoleVO(Long id, String name, String baseRoleCode) {
}
