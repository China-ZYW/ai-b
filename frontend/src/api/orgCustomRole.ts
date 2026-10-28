import { del, get, post, put } from './request'
import type {
  CustomRoleEffectiveVO,
  CustomRoleForm,
  CustomRolePermissionForm,
  CustomRoleVO
} from '@/types'

/**
 * 组织自定义角色接口（`/api/org/custom-roles`，《组织治理专项设计》§2）。
 *
 * 自定义角色派生自一个**可授予的内置组织角色**（base，不可为 OWNER），有效权限 =
 * `base ∪ GRANT(仅限 base 内) − DENY`（DENY 优先，不可反转）——即只能比 base 更窄，不可更宽。
 * 权限点 `org:customrole:write`（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）；租户范围由后端强制收敛。
 */
export const orgCustomRoleApi = {
  /** 本组织自定义角色列表（含 override 数、启用态）。 */
  list: () => get<CustomRoleVO[]>('/org/custom-roles'),
  /** 预览某角色的有效权限（base / granted / denied / effective 四项全量）。 */
  effective: (id: number) => get<CustomRoleEffectiveVO>(`/org/custom-roles/${id}/effective`),
  /** 新建（name + baseRoleCode 必填；套餐配额 / 越级护栏在后端）。 */
  create: (data: CustomRoleForm) => post<CustomRoleVO>('/org/custom-roles', data),
  /** 更新（改名/描述/启用停用/dataScope；base 不可改）。 */
  update: (id: number, data: CustomRoleForm) => put<CustomRoleVO>(`/org/custom-roles/${id}`, data),
  /** 整体替换权限覆盖（GRANT 超出 base 会被后端拒 ROLE_GRANT_ESCALATION）。 */
  setPermissions: (id: number, data: CustomRolePermissionForm) =>
    put<CustomRoleVO>(`/org/custom-roles/${id}/permissions`, data),
  /** 删除（仍有成员持有时后端拒 CUSTOM_ROLE_IN_USE）。 */
  remove: (id: number) => del<void>(`/org/custom-roles/${id}`)
}
