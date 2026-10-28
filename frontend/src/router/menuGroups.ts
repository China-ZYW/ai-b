import type { RouteRecordRaw } from 'vue-router'

/**
 * 菜单分组注册表 —— 菜单结构的唯一事实源。
 *
 * <p>背景：`/app` 的侧栏原先是把路由 children **平铺**渲染（21 项一路排下来），既无层级，
 * 顺序也只能靠物理数组顺序维护；`/admin` 虽有分组，但分组定义硬编码在 `AdminLayout.vue` 里，
 * 与 `/app` 不共用。新增页面时往往要同时改路由表 + 某个布局组件，历史上因此出现过
 * 「路由有 manual、侧栏 navItems 没有」的不一致。
 *
 * <p>本模块把「分组有哪些、组顺序、每组的项顺序」集中到一处：
 * <ol>
 *   <li>**新增菜单**：写好路由 → 在 {@link APP_MENU_META} 加一行 `routeName: { group, order }` 即可，
 *       无需改动任何布局/菜单组件；
 *   <li>**调组顺序**：改 {@link APP_MENU_GROUPS} 数组顺序；
 *   <li>**组内调序**：改该项 `order`（刻意留 10/20/30 间隔，便于后续插入而不用重排）；
 *   <li>**新增分组**：往数组里插一条即可。
 * </ol>
 *
 * <p>路由 {@code meta} 的分组回填见 {@code router/index.ts} 的 `applyMenuMeta()`：
 * 沿用既有 `applyPlanGates` 的手法，在路由注册后**集中回填**，而不是逐条散写在 21 个路由对象里——
 * 这样既避免大段数组重写（历史上曾因重写丢过项导致功能整块消失），也让 `meta` 保持单一来源。
 */

/** 分组定义：数组顺序即侧栏组顺序。 */
export interface MenuGroupDef {
  /** 分组键（与 {@link APP_MENU_META} 的 group 对应）。 */
  key: string
  /** 侧栏展示的组标题。 */
  label: string
}

/** 单个菜单项的归组与组内排序信息。 */
export interface MenuItemMeta {
  /** 所属分组 key（须存在于分组数组中，否则归入「其他」）。 */
  group: string
  /** 组内排序号，小的在前；刻意留间隔便于插入。 */
  order: number
}

/**
 * 业务端（`/app`）侧栏分组。
 *
 * <p>按用户动线组织：先“取数”（问数探索），再“沉淀”（数据资产），然后“对外输出”（分享与集成），
 * 其后是管理员域（组织与治理 / 调优运维），最后是个人账户域。顺序刻意让高频能力靠前。
 */
export const APP_MENU_GROUPS: MenuGroupDef[] = [
  { key: 'workbench', label: '问数探索' },
  { key: 'assets', label: '数据资产' },
  { key: 'open', label: '分享与集成' },
  { key: 'org', label: '组织与治理' },
  { key: 'ops', label: '调优运维' },
  { key: 'personal', label: '个人与帮助' }
]

/**
 * 业务端路由 name → 归组/序位。
 *
 * <p>键是**路由 name**（不是 path）。`dashboard-edit` 等 `meta.hidden` 的路由无需登记——
 * 它们不进菜单，构建菜单时已被过滤。
 *
 * <p>order 以 10 递增留插入余量：例如将来在“对话问数”与“高级查询”之间插“数据集”，
 * 用 `order: 15` 即可，不必重排后面的条目。
 */
export const APP_MENU_META: Record<string, MenuItemMeta> = {
  // —— 问数探索：核心取数闭环 ——
  chat: { group: 'workbench', order: 10 },
  'sql-editor': { group: 'workbench', order: 20 },
  // 预留 30：查询模板 / 数据集 / 协作评论等“探索增强”能力
  history: { group: 'workbench', order: 40 },
  favorite: { group: 'workbench', order: 50 },

  // —— 数据资产：沉淀与治理对象 ——
  datasource: { group: 'assets', order: 10 },
  metrics: { group: 'assets', order: 20 },
  dashboards: { group: 'assets', order: 30 },
  // 预留 40：数据建模/语义视图；50：数据集成与同步

  // —— 分享与集成：对外输出通道 ——
  shares: { group: 'open', order: 10 },
  'chat-embed': { group: 'open', order: 20 },
  // 预留 30：开放 API / Webhook / 对外 MCP 自助接入

  // —— 组织与治理：管理员域（多数项带 meta.planMin='PRO' 套餐因子）——
  'org-members': { group: 'org', order: 10 },
  'org-invitations': { group: 'org', order: 20 },
  'org-custom-roles': { group: 'org', order: 30 },
  'org-data-acl': { group: 'org', order: 40 },
  'org-alerts': { group: 'org', order: 50 },
  'org-audit': { group: 'org', order: 60 },
  // 预留 70：审批流 / 工作流

  // —— 调优运维：让问数更准、让元数据不漂 ——
  tuning: { group: 'ops', order: 10 },
  'schema-drift': { group: 'ops', order: 20 },
  // 预留 30：任务调度 / LLM 用量 / 慢查询诊断

  // —— 个人与帮助 ——
  messages: { group: 'personal', order: 10 },
  profile: { group: 'personal', order: 20 },
  manual: { group: 'personal', order: 30 }
  // 预留 40：通知偏好 / 个人 API 令牌
}

/** 平台后台（`/admin`）侧栏分组（§9.2 运营组/治理组），集中存放以免散落在布局组件里。 */
export const ADMIN_MENU_GROUPS: MenuGroupDef[] = [
  { key: 'ops', label: '运营' },
  { key: 'governance', label: '治理' }
]

/** 未登记分组的菜单兜底分组（渲染在最后；为空时不渲染）。 */
export const MENU_GROUP_OTHER: MenuGroupDef = { key: '__other', label: '其他' }

/**
 * 把分组元数据回填到路由树（递归）。
 *
 * <p>与 `applyPlanGates` 同一手法：注册后统一改写 `meta`，避免逐条散写、也避免重写整个路由数组。
 * 已有 `meta` 不会被覆盖丢失，只是追加 `group` / `order` 两个字段。
 */
export function applyMenuMeta(routes: RouteRecordRaw[]): void {
  for (const r of routes) {
    if (r.name && APP_MENU_META[String(r.name)]) {
      const m = APP_MENU_META[String(r.name)]
      r.meta = { ...r.meta, group: m.group, order: m.order }
    }
    if (r.children?.length) applyMenuMeta(r.children)
  }
}
