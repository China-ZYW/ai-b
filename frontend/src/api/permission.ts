import { get } from './request'
import type { PermissionsView } from '@/types'

/**
 * 权限下发接口（P4）。前端登录/刷新后拉取一次当前上下文的有效权限快照，
 * 据此驱动动态菜单、按钮显隐与数据范围提示。需携带 Bearer 令牌（认证即可，无额外权限点）。
 */
export const permissionApi = {
  /** 当前上下文的有效权限码、菜单码、数据范围、角色、团队、席位余量。 */
  current: () => get<PermissionsView>('/auth/permissions')
}
