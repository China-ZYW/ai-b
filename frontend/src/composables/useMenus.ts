import { computed } from 'vue'
import { useRouter, type RouteRecordRaw } from 'vue-router'
import { usePermissionStore } from '@/stores/permission'
import { useUserStore } from '@/stores/user'
import {
  ADMIN_MENU_GROUPS,
  APP_MENU_GROUPS,
  MENU_GROUP_OTHER,
  type MenuGroupDef
} from '@/router/menuGroups'

/** 套餐等级（用于 meta.planMin 比较，低档隐藏治理类菜单）。 */
const PLAN_RANK: Record<string, number> = { FREE: 0, PRO: 1, ENTERPRISE: 2, FLAGSHIP: 3 }

/** 动态菜单节点。 */
export interface MenuNode {
  path: string
  title: string
  icon?: string
  /** 分组标识（来自 route.meta.group，由 router/menuGroups.ts 注册表回填）。 */
  group?: string
  /** 组内排序号（来自 route.meta.order，小的在前）。 */
  order?: number
  children?: MenuNode[]
}

/** 侧栏分段：一个分组标题 + 其下的菜单项。 */
export interface MenuSection {
  key: string
  label: string
  items: MenuNode[]
}

/**
 * 路由表驱动 + 权限过滤的动态菜单（P4，见《角色权限体系与组织协同设计》7.4）。
 *
 * <p>取代 `AppLayout.vue` / `AdminLayout.vue` 里写死的 `navItems` 数组——过去新增页面要同时改路由表和
 * 该数组（已存在不一致：路由有 `manual` 但 navItems 没有）。此处以路由 children 为数据源，
 * 按 `meta.perm/perms/roles` 与套餐因子过滤：未声明权限点的菜单默认展示；子项全被过滤掉的父项也隐藏。
 *
 * <p>除扁平的 {@link menus} 外，另产出按分组注册表的分段结构 {@link sections}：
 * 分组定义集中在 `router/menuGroups.ts`（业务端 6 组 / 平台后台 2 组），业务端侧栏据此渲染层级，
 * 平台后台沿用同一套渲染。**新增菜单只需在注册表加一行，不必再改任何布局组件。**
 *
 * @param rootPath 菜单根路由路径（业务端 `/app`，平台后台 `/admin`）。
 */
export function useMenus(rootPath = '/') {
  const router = useRouter()
  const permStore = usePermissionStore()
  const userStore = useUserStore()

  // 套餐等级（plan 未加载时按 FREE 处理，避免 PRO 菜单在首屏闪烁；loadMe 已在守卫中 await）。
  const planRank = computed(() => PLAN_RANK[(userStore.plan ?? 'FREE').toUpperCase()] ?? 0)

  const build = (routes: RouteRecordRaw[], basePath = ''): MenuNode[] =>
    routes
      .filter((r) => !r.meta?.hidden && !r.meta?.public)
      .filter((r) => {
        if (r.meta?.roles?.length && !permStore.hasRole(...r.meta.roles)) return false
        const perms = r.meta?.perm ? [r.meta.perm] : r.meta?.perms
        if (!perms?.length) return true // 未声明权限点的菜单默认展示
        return permStore.hasAny(...perms)
      })
      .filter((r) => {
        // 套餐因子：低于 meta.planMin 的菜单在前端隐藏（后端仍有强校验，前端仅改善体验）。
        const min = r.meta?.planMin
        if (min && planRank.value < (PLAN_RANK[min] ?? 0)) return false
        return true
      })
      .map((r) => {
        const path = r.path.startsWith('/') ? r.path : `${basePath}/${r.path}`.replace(/\/+/g, '/')
        const children = r.children?.length ? build(r.children, path) : undefined
        return {
          path,
          title: (r.meta?.title as string) ?? '',
          icon: r.meta?.icon as string | undefined,
          group: r.meta?.group as string | undefined,
          order: typeof r.meta?.order === 'number' ? (r.meta.order as number) : undefined,
          children
        }
      })
      .filter((n) => n.title && (!n.children || n.children.length))

  const menus = computed<MenuNode[]>(() => {
    // 显式触碰 codes 建立响应式依赖：load() 里是整体替换 codes.value，setter 会触发依赖；
    // 保留此行以防将来改成局部变更（codes.value.add）时菜单静默不刷新。
    void permStore.codes
    const root = router.options.routes.find((r) => r.path === rootPath)
    // 以 rootPath 作为子路由的拼接前缀：'/app' 时 'chat'→'/app/chat'，'/admin' 时 'overview'→'/admin/overview'。
    // 双斜杠由 build 内的 replace(/\/+/g,'/') 收敛，故根路径也安全。
    return root?.children ? build(root.children, rootPath) : []
  })

  /**
   * 按分组注册表把扁平菜单整理为分段结构：段顺序 = 注册表数组顺序，段内顺序 = meta.order（升序），
   * 未登记分组的项归入「其他」并排在最后；空段不渲染。
   *
   * <p>注意依赖项：`sections` 由 `menus` 推导，权限/套餐变化会经 `menus` 传导，无需再触碰 codes。
   */
  const sections = computed<MenuSection[]>(() => {
    const defs: MenuGroupDef[] = rootPath.startsWith('/admin')
      ? ADMIN_MENU_GROUPS
      : APP_MENU_GROUPS

    // 按 group 归桶；未登记 → 兜底「其他」
    const buckets = new Map<string, MenuNode[]>()
    for (const n of menus.value) {
      const key = n.group ?? MENU_GROUP_OTHER.key
      const list = buckets.get(key)
      if (list) list.push(n)
      else buckets.set(key, [n])
    }

    // 组内排序：有 order 的按号升序，未登记的沉到该组末尾
    const cmp = (a: MenuNode, b: MenuNode): number => (a.order ?? Number.MAX_SAFE_INTEGER) - (b.order ?? Number.MAX_SAFE_INTEGER)
    const sectionOf = (d: MenuGroupDef): MenuSection | null => {
      const list = buckets.get(d.key)
      if (!list?.length) return null
      return { key: d.key, label: d.label, items: [...list].sort(cmp) }
    }

    const out: MenuSection[] = []
    for (const d of defs) {
      const s = sectionOf(d)
      if (s) out.push(s)
    }
    const other = sectionOf(MENU_GROUP_OTHER)
    if (other) out.push(other)
    return out
  })

  return { menus, sections }
}
