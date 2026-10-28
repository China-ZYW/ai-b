import { get } from './request'
import type { ApiMatrixVO, PermissionVO, RolePermissionMatrix, RoleVO } from '@/types'

/**
 * 平台后台 · 权限治理只读接口（/api/admin/rbac，§9.2 治理组）。
 *
 * 全部需 `admin:rbac:read`（后端 platformOnly 强校，仅平台域身份可达）。只读投影：
 * 权限点字典、角色字典、角色→权限点矩阵、接口权限矩阵（§6.4 扫描结果）。
 * 字典的编辑属 `admin:rbac:write`，不在本 API 范围内。
 */
export const adminRbacApi = {
  /** 权限点字典（全部已启用项）。 */
  permissions: () => get<PermissionVO[]>('/admin/rbac/permissions'),
  /** 角色字典（平台/组织/团队三层）。 */
  roles: () => get<RoleVO[]>('/admin/rbac/roles'),
  /** 角色→权限点矩阵。 */
  matrix: () => get<RolePermissionMatrix>('/admin/rbac/matrix'),
  /** 接口权限矩阵（含 fail-closed 域未标注告警统计）。 */
  apiMatrix: () => get<ApiMatrixVO>('/admin/rbac/api-matrix')
}
