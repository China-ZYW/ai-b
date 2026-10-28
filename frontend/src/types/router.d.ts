/**
 * vue-router 的 RouteMeta 模块增强（P4，见《角色权限体系与组织协同设计》7.3）。
 *
 * Vue 3 的模块增强必须写在独立模块文件中（含顶层 import/export 才被视为模块），
 * 故单列本文件而非塞进 env.d.ts。`meta` 从单一 `role` 升级为可组合字段，
 * 并保留 `role` 作为存量兼容（等价于 `roles:[role]`），避免一次性改动全部 admin 路由。
 */
export {}

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（顶栏与 document.title）。 */
    title?: string
    /** 菜单图标（Element Plus 图标组件名）。 */
    icon?: string
    /** 不在动态菜单中展示（详情页、编辑页等）。 */
    hidden?: boolean
    /** 免登录放行（认证页、公开分享页）。 */
    public?: boolean
    /** 全屏无外壳（数据大屏）。 */
    fullscreen?: boolean
    /** 单个权限点（最常用）：命中才放行/展示。 */
    perm?: string
    /** 多个权限点，配合 permLogical。 */
    perms?: string[]
    /** perms 的判定逻辑，默认 OR。 */
    permLogical?: 'AND' | 'OR'
    /** 角色码集合（OWNER 专属页等“整角色”语义）。 */
    roles?: string[]
    /** 需已选团队上下文（组织/团队页用；当前组织域页面尚未落地，暂不挂载）。 */
    requireTeam?: boolean
    /** @deprecated 兼容存量，等价于 roles:[role]。 */
    role?: string
    /** 菜单所需最低套餐：低于此档时前端隐藏该菜单，消除「可见不可用」割裂（后端仍有强校验）。FREE 不写即不限。 */
    planMin?: 'FREE' | 'PRO' | 'ENTERPRISE' | 'FLAGSHIP'
  }
}
