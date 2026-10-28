import { get, post } from './request'
import type { DataAclClearForm, DataAclForm, DataAclItemVO } from '@/types'

/**
 * 数据权限 A 管理接口（`/api/org/data-acl`，《组织治理专项设计》§3）。
 *
 * 组织管理员为「成员直授」或「自定义角色继承」配置三类数据授权：
 *  - **数据源可见性**（传 `tableName`/`columnName` 均为空）；
 *  - **表级可见性**（传 `tableName`）：HIDDEN 时该表从 schema 省略，且执行前解析 SQL 命中该表会被拦截；
 *  - **字段脱敏**（传 `columnName`）。
 * 后端按入参维度分发，三者共用同一组 set/clear 端点。
 * 功能权限复用 `org:customrole:write`（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）；
 * 租户范围由后端从登录上下文强制收敛，不接受 tenantId 入参。
 * 合并取最宽（成员直授 ∪ 角色继承）与 fail-closed 由后端 `DataMaskGate` 在出口施加，前端仅呈现。
 */
export const dataAclApi = {
  /** 某成员的全部数据授权（数据源可见性 + 字段脱敏）。 */
  listUser: (uid: number) => get<DataAclItemVO[]>(`/org/data-acl/user/${uid}`),
  /** 某自定义角色继承的全部数据授权。 */
  listRole: (customRoleId: number) => get<DataAclItemVO[]>(`/org/data-acl/role/${customRoleId}`),
  /** 设置一条授权（整体替换语义：删旧插新）。 */
  set: (data: DataAclForm) => post<void>('/org/data-acl/set', data),
  /** 清除一条授权（恢复默认：可见 / 按语义层默认掩码）。 */
  clear: (data: DataAclClearForm) => post<void>('/org/data-acl/clear', data)
}
