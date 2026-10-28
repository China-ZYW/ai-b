package com.aibi.module.rbac.dto;

import java.util.Set;

/**
 * 自定义角色有效权限预览（《组织治理专项设计》§2.7 {@code GET /{id}/effective}）。
 *
 * <p>有效权限 = {@code basePermissions} ∪ {@code granted}(仅 base 内) − {@code denied}，即成员持有该角色后
 * 实际拿到的权限码集合。四项全量返回便于前端逐项对比「base 有哪些 / 被放开哪些 / 被收窄哪些 / 最终生效哪些」。
 */
public record CustomRoleEffectiveVO(
        Long id,
        String baseRoleCode,
        Set<String> basePermissions,
        Set<String> granted,
        Set<String> denied,
        Set<String> effective) {
}
