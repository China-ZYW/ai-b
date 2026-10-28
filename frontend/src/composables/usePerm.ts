import { usePermissionStore } from '@/stores/permission'

/**
 * 按钮级权限判定的组合式入口（P4，见《角色权限体系与组织协同设计》7.5）。
 *
 * 模板内联 `v-if="can('chat:query:export')"` 是按钮门禁的**首选**手段（天然响应式）；
 * 需要“无权时展示禁用态而非移除”时用 `<PermGuard>`，静态工具条/第三方插槽内不便 `v-if` 时用 `v-perm` 指令。
 * 三者判据同源（都走 permission store），此处只是把常用判定收敛成简短别名，避免各页反复 `usePermissionStore()`。
 */
export function usePerm() {
  const permStore = usePermissionStore()

  /** 是否具备单个权限点。 */
  const can = (code: string): boolean => permStore.has(code)
  /** 是否具备任一权限点（OR）。 */
  const canAny = (...list: string[]): boolean => permStore.hasAny(...list)
  /** 是否具备全部权限点（AND）。 */
  const canAll = (...list: string[]): boolean => permStore.hasAll(...list)
  /** 是否具备任一角色码（平台/组织/团队角色合并判定）。 */
  const canRole = (...list: string[]): boolean => permStore.hasRole(...list)

  return { permStore, can, canAny, canAll, canRole }
}
