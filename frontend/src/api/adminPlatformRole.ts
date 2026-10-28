import { del, get, post } from './request'
import type { PlatformRoleGrantForm, PlatformRoleHolderVO } from '@/types'

/**
 * 平台后台 · 平台角色授予/回收接口（/api/admin/platform-roles，§9.2 治理组，唯一写侧治理页）。
 *
 * 全部需 `admin:role:assign`（后端 platformOnly 强校，权限矩阵 YNNN → 仅 SUPER_ADMIN）。
 * 授予/回收后由服务层 evict 版本缓存即时生效；护栏（不可回收自己/最后一个 SUPER_ADMIN）在后端强制。
 */
export const adminPlatformRoleApi = {
  /** 全部平台角色持有者（联 app_user 取账号信息）。 */
  holders: () => get<PlatformRoleHolderVO[]>('/admin/platform-roles'),
  /** 授予平台角色给某平台账号。 */
  grant: (data: PlatformRoleGrantForm) => post<PlatformRoleHolderVO>('/admin/platform-roles', data),
  /** 回收一条平台角色授予（按 user_role 行主键）。 */
  revoke: (id: number) => del<void>(`/admin/platform-roles/${id}`)
}
