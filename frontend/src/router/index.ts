import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { clearAuth, getToken } from '@/utils/storage'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'
import { applyMenuMeta } from '@/router/menuGroups'
import { setUiThemeScope } from '@/theme/appTheme'

/**
 * 路由表。
 * - 认证页 / 公开分享页 meta.public=true，免登录放行；
 * - 其余页面套用 AppLayout（顶栏 + 侧栏），需登录。
 * 视图组件全部懒加载（路由级分包），配合 vite manualChunks 优化首屏。
 */
const routes: RouteRecordRaw[] = [
  {
    // 官网主页（产品介绍 / 套餐定价 / 交流群与联系方式占位）。公开页，占据域名根路径。
    path: '/',
    name: 'landing',
    component: () => import('@/views/LandingView.vue'),
    meta: { public: true, standalone: true, title: 'AI 数据助理 · 对话式问数平台' }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true, standalone: true, title: '登录' }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { public: true, standalone: true, title: '注册' }
  },
  {
    path: '/s/:token',
    name: 'public-share',
    component: () => import('@/views/share/PublicShareView.vue'),
    meta: { public: true, standalone: true, title: '分享' }
  },
  {
    // 公开数据大屏（问题2，凭 token 免登录、实时刷新）。meta.public=true 放行。
    path: '/share/screen/:token',
    name: 'public-screen',
    component: () => import('@/views/screen/PublicScreenView.vue'),
    meta: { public: true, standalone: true, title: '数据大屏', fullscreen: true }
  },
  {
    // 对话问数嵌入（第三方页面以 iframe 免登录嵌入）：凭 token 匿名实时问数。
    // meta.public=true 放行；fullscreen 隐藏备案悬挂条与外围框架，便于被第三方页面嵌入。
    path: '/embed/chat/:token',
    name: 'embed-chat',
    component: () => import('@/views/embed/EmbedChatView.vue'),
    meta: { public: true, standalone: true, title: '智能问数', fullscreen: true }
  },
  {
    // 组织邀请落地页（§9.2 P3）：凭 token 匿名预览邀请概要并引导登录/注册。meta.public=true 免登录放行。
    // 接受链路（accept）已开放：已登录用户可点「接受邀请」调用 POST /api/org/invitations/accept?token=xxx 入组。
    path: '/org/join',
    name: 'org-join',
    component: () => import('@/views/org/OrgJoinView.vue'),
    meta: { public: true, standalone: true, title: '加入组织' }
  },
  {
    // 产品应用（对话问数 / 驾驶舱 / 指标库等）：整体迁移至 /app 前缀，根路径 / 让给官网。
    path: '/app',
    component: () => import('@/components/layout/AppLayout.vue'),
    redirect: '/app/chat',
    children: [
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/chat/ChatView.vue'),
        meta: { title: '对话问数', icon: 'ChatDotRound', perm: 'chat:query:read' }
      },
      {
        path: 'datasource',
        name: 'datasource',
        component: () => import('@/views/datasource/DataSourceView.vue'),
        meta: { title: '数据源', icon: 'Coin', perm: 'datasource:config:read' }
      },
      {
        path: 'metrics',
        name: 'metrics',
        component: () => import('@/views/metric/MetricView.vue'),
        meta: { title: '指标库', icon: 'TrendCharts', perm: 'metric:item:read' }
      },
      {
        path: 'dashboards',
        name: 'dashboards',
        component: () => import('@/views/dashboard/DashboardListView.vue'),
        meta: { title: '驾驶舱', icon: 'DataBoard', perm: 'dashboard:item:read' }
      },
      {
        path: 'dashboards/:id/edit',
        name: 'dashboard-edit',
        component: () => import('@/views/dashboard/DashboardEditorView.vue'),
        meta: { title: '驾驶舱编辑', hidden: true, perm: 'dashboard:item:write' }
      },
      {
        // 高级查询 / SQL 编辑器：权限复用 chat:query:execute（发起问数）与 history:query:read，
        // 不新申请权限码——避免抬 perm_version 逼全员重登，而这几个语义已被既有角色覆盖。
        path: 'sql',
        name: 'sql-editor',
        component: () => import('@/views/query/SqlEditorView.vue'),
        meta: { title: '高级查询', icon: 'Document', perm: 'chat:query:execute' }
      },
      {
        // 定时报表（T12-1）：同款复用既有权限码不新建；入口在高级查询模板行，此处为管理页。
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/report/ReportsView.vue'),
        meta: { title: '定时报表', icon: 'AlarmClock', perm: 'history:query:read' }
      },
      {
        path: 'history',
        name: 'history',
        component: () => import('@/views/history/HistoryView.vue'),
        meta: { title: '查询历史', icon: 'Clock', perm: 'history:query:read' }
      },
      {
        path: 'favorite',
        name: 'favorite',
        component: () => import('@/views/favorite/FavoriteView.vue'),
        meta: { title: '我的收藏', icon: 'Star', perm: 'favorite:item:read' }
      },
      {
        path: 'shares',
        name: 'shares',
        component: () => import('@/views/share/MySharesView.vue'),
        meta: { title: '分享管理', icon: 'Share', perm: 'share:link:read' }
      },
      {
        path: 'tuning',
        name: 'tuning',
        component: () => import('@/views/tuning/TuningView.vue'),
        meta: { title: '问数调优', icon: 'MagicStick', perm: 'tuning:panel:read' }
      },
      {
        // 结构对账（Schema Drift）：检测下游元数据与物理表结构之间的漂移，并支持一键清理孤儿。
        // 权限点复用 semantic:meta:*（检测 read / 清理 delete），与语义层治理同属一个职责域，不新增权限码。
        path: 'schema-drift',
        name: 'schema-drift',
        component: () => import('@/views/drift/SchemaDriftView.vue'),
        meta: { title: '结构对账', icon: 'Connection', perm: 'semantic:meta:read' }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/admin/ProfileView.vue'),
        meta: { title: '个人中心', icon: 'User', perm: 'profile:self:read' }
      },
      {
        // 组织设置（§9.2）：成员管理。member:member:read 组织矩阵 YYNNNN → 仅 OWNER/ADMIN 可见；
        // 后端强制租户范围（/api/org/members），护栏（不可移除自己/越级/最后一个 OWNER）在服务层。
        path: 'org-members',
        name: 'org-members',
        component: () => import('@/views/org/OrgMembersView.vue'),
        meta: { title: '组织成员', icon: 'UserFilled', perm: 'member:member:read' }
      },
      {
        // 组织设置（§9.2）：邀请管理。member:invite:read 组织矩阵 YYNNNN → 仅 OWNER/ADMIN 可见；
        // 令牌只存哈希、明文仅创建时返回一次；接受链路已实现（orgInvitationApi.accept / OrgJoinView）。
        path: 'org-invitations',
        name: 'org-invitations',
        component: () => import('@/views/org/OrgInvitationsView.vue'),
        meta: { title: '组织邀请', icon: 'Promotion', perm: 'member:invite:read' }
      },
      {
        // 组织设置（§9.2）：组织内审计日志。audit:log:read 组织矩阵 YYNNNN → 仅 OWNER/ADMIN 可见；
        // 后端强制租户范围（/api/org/audit），与平台后台跨租户视图 /admin/audit 对照。
        path: 'org-audit',
        name: 'org-audit',
        component: () => import('@/views/org/OrgAuditView.vue'),
        meta: { title: '组织审计', icon: 'Tickets', perm: 'audit:log:read' }
      },
      {
        // 组织设置（§9.2）：智能预警。alert:rule:read 组织矩阵 YYNNNN → 仅 OWNER/ADMIN 可见；
        // 后端 /api/org/alerts 强制租户范围 + 方法级 @RequiresPermission 与 assertManager 双重门禁。
        path: 'org-alerts',
        name: 'org-alerts',
        component: () => import('@/views/org/OrgAlertsView.vue'),
        meta: { title: '智能预警', icon: 'Bell', perm: 'alert:rule:read' }
      },
      {
        // 站内信收件箱（P1 第 8 个告警渠道 INAPP 的配套入口）：人人可读"自己的"信。
        // 权限码 message:inbox:read 组织矩阵 YYYYYY → 全员可见；后端 /api/messages 按 uid+tenantId 强制收敛。
        path: 'messages',
        name: 'messages',
        component: () => import('@/views/org/MessageCenterView.vue'),
        meta: { title: '站内信', icon: 'Comment', perm: 'message:inbox:read' }
      },
      {
        // 组织设置（P1 组织角色可编辑，《组织治理专项设计》§2）：自定义角色 CRUD + 权限收窄 + 有效权限预览。
        // 权限码 org:customrole:write（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）；后端 /api/org/custom-roles 强制租户范围。
        path: 'org-custom-roles',
        name: 'org-custom-roles',
        component: () => import('@/views/org/OrgCustomRoleView.vue'),
        meta: { title: '角色权限', icon: 'Key', perm: 'org:customrole:write' }
      },
      {
        // 组织设置（数据权限 A，《组织治理专项设计》§3）：为成员/自定义角色配置数据源可见性与字段脱敏。
        // 权限码 org:customrole:write（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）；后端 /api/org/data-acl 强制租户范围。
        path: 'org-data-acl',
        name: 'org-data-acl',
        component: () => import('@/views/org/OrgDataAclView.vue'),
        meta: { title: '数据授权', icon: 'Lock', perm: 'org:customrole:write' }
      },
      {
        path: 'manual',
        name: 'manual',
        component: () => import('@/views/manual/ManualView.vue'),
        meta: { title: '用户手册', icon: 'Document', perm: 'manual:doc:read' }
      },
  {
    // 对话问数嵌入（第三方页面）：把某数据源以匿名令牌开放给外部站点 iframe 嵌入。
    // 权限码 chat:embed:write（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）——会对外开放数据，故收紧授权。
    path: 'embed',
    name: 'chat-embed',
    component: () => import('@/views/embed/EmbedManageView.vue'),
    meta: { title: '对话嵌入', icon: 'Link', perm: 'chat:embed:write' }
  }
]

  },
  {
    // 平台后台（独立 AdminLayout 外壳）。父路由粗粒度门禁：仅平台域四角色可进入；
    // 各子页再按 admin:*:read 细分（§9.2），菜单由 useMenus('/admin') 同源过滤。
    path: '/admin',
    component: () => import('@/components/layout/AdminLayout.vue'),
    redirect: '/admin/overview',
    meta: { roles: ['SUPER_ADMIN', 'OPERATOR', 'AUDITOR', 'SUPPORT'] },
    children: [
      {
        path: 'overview',
        name: 'admin-overview',
        component: () => import('@/views/admin/AdminOverviewView.vue'),
        meta: { title: '平台概览', icon: 'Odometer', perm: 'admin:overview:read', group: 'ops' }
      },
      {
        path: 'tenants',
        name: 'admin-tenants',
        component: () => import('@/views/admin/TenantAdminView.vue'),
        meta: { title: '租户管理', icon: 'OfficeBuilding', perm: 'admin:tenant:read', group: 'ops' }
      },
      {
        // 运营组（§9.2）：席位与配额。admin:tenant:write 矩阵 YYNN → SUPER_ADMIN / OPERATOR 可见。
        path: 'seats',
        name: 'admin-seats',
        component: () => import('@/views/admin/SeatAdminView.vue'),
        meta: { title: '席位与配额', icon: 'Ticket', perm: 'admin:tenant:write', group: 'ops' }
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserAdminView.vue'),
        meta: { title: '账号管理', icon: 'UserFilled', perm: 'admin:user:read', group: 'ops' }
      },
      {
        path: 'llm',
        name: 'admin-llm',
        component: () => import('@/views/admin/LlmConfigView.vue'),
        meta: { title: '大模型配置', icon: 'Cpu', perm: 'admin:llm:read', group: 'ops' }
      },
      {
        path: 'config',
        name: 'admin-config',
        component: () => import('@/views/admin/GlobalConfigView.vue'),
        meta: { title: '平台默认配置', icon: 'Setting', perm: 'admin:config:read', group: 'ops' }
      },
      {
        // 通知配置（平台级）：SMTP / 短信 / 钉钉 / 企微 / 飞书 / PushPlus。复用 admin:config:* 权限域，
        // 页面内密钥不回显、保存即生效（通知实现发送时现读配置）。
        path: 'notify',
        name: 'admin-notify',
        component: () => import('@/views/admin/NotifyConfigView.vue'),
        meta: { title: '通知配置', icon: 'Message', perm: 'admin:config:read', group: 'ops' }
      },
      {
        // 运营组（§9.2）：内容运营。驾驶舱模板全局 CRUD + 手册入口。admin:content:write 矩阵 YYNN，
        // 与后端 AdminDashboardTemplateController 四端点权限点一致；与 manual 同受众，故相邻放置。
        path: 'content',
        name: 'admin-content',
        component: () => import('@/views/admin/ContentOpsView.vue'),
        meta: { title: '内容运营', icon: 'Files', perm: 'admin:content:write', group: 'ops' }
      },
      {
        // 对外 MCP 令牌（见《对外 MCP 能力方案》）：按组织+用户签发个人访问令牌，供外部 AI 客户端
        // 以 Bearer 调用 POST /mcp。明文只在创建弹窗出现一次；吊销立即生效。
        path: 'mcp',
        name: 'admin-mcp',
        component: () => import('@/views/admin/McpPatAdminView.vue'),
        meta: { title: 'MCP 令牌', icon: 'Connection', perm: 'admin:mcp:read', group: 'ops' }
      },
      {
        path: 'manual',
        name: 'admin-manual',
        component: () => import('@/views/admin/ManualAdminView.vue'),
        meta: { title: '用户手册', icon: 'Document', perm: 'admin:manual:publish', group: 'ops' }
      },
      {
        // 治理组（§9.2）：权限字典与接口权限矩阵共用 admin:rbac:read，只读投影自后端单一事实源。
        path: 'rbac',
        name: 'admin-rbac',
        component: () => import('@/views/admin/RbacDictView.vue'),
        meta: { title: '权限字典', icon: 'Key', perm: 'admin:rbac:read', group: 'governance' }
      },
      {
        path: 'api-matrix',
        name: 'admin-api-matrix',
        component: () => import('@/views/admin/ApiMatrixView.vue'),
        meta: { title: '接口权限矩阵', icon: 'Grid', perm: 'admin:rbac:read', group: 'governance' }
      },
      {
        // 治理组唯一写侧页（§9.2）：平台角色授予/回收。admin:role:assign 矩阵 YNNN → 仅 SUPER_ADMIN 可见。
        path: 'platform-roles',
        name: 'admin-platform-roles',
        component: () => import('@/views/admin/PlatformRolesView.vue'),
        meta: { title: '平台角色', icon: 'Avatar', perm: 'admin:role:assign', group: 'governance' }
      },
      {
        // 治理组（§9.2）：审计日志。admin:audit:read 矩阵 YNYN → SUPER_ADMIN / AUDITOR，只读跨租户检索。
        path: 'audit',
        name: 'admin-audit',
        component: () => import('@/views/admin/AuditLogView.vue'),
        meta: { title: '审计日志', icon: 'Tickets', perm: 'admin:audit:read', group: 'governance' }
      },
      {
        // 治理组（§10.2 审计落地之二）：操作日志。复用 admin:audit:read 权限点，只读跨租户检索 audit_log。
        // 与「审计日志」(auth_audit_log 仅登录/权限事件) 互补，覆盖每一次 HTTP 操作。
        path: 'operation-log',
        name: 'admin-operation-log',
        component: () => import('@/views/admin/OperationLogView.vue'),
        meta: { title: '操作日志', icon: 'Document', perm: 'admin:audit:read', group: 'governance' }
      }
    ]
  },
  {
    // 全屏数据大屏（独立于 AppLayout，深色克莱因蓝主题，按 refreshSeconds 轮询刷新）。需登录。
    path: '/screen/:id',
    name: 'screen',
    component: () => import('@/views/screen/ScreenView.vue'),
    meta: { title: '数据大屏', fullscreen: true }
  },
  {
    // 无权访问页（P4）：角色/权限点门禁不通过时重定向到此。需登录但无权限点要求，避免自身再被拦造成死循环。
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { title: '无权访问' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]

/**
 * 治理类菜单套餐因子（见《菜单角色权限矩阵与套餐配额分析》#1）：FREE 隐藏组织治理类菜单，
 * 消除「可见不可用」割裂。免费版 customRoleLimit=0，进去是空页/被拦，故仅 PRO 及以上展示。
 * 这里集中注入 {@code meta.planMin='PRO'}，避免逐条路由散改；{@link useMenus} 据此过滤菜单。
 * 后端对应治理能力仍有强校验（前端仅改善体验，不是安全边界）。
 */
const GOVERNANCE_ROUTES = new Set([
  'org-members',
  'org-invitations',
  'org-audit',
  'org-alerts',
  'org-custom-roles',
  'org-data-acl'
])

function applyPlanGates(rs: RouteRecordRaw[]): void {
  for (const r of rs) {
    if (r.name && GOVERNANCE_ROUTES.has(String(r.name))) {
      r.meta = { ...r.meta, planMin: 'PRO' }
    }
    if (r.children?.length) applyPlanGates(r.children)
  }
}
applyPlanGates(routes)

/**
 * 菜单分组/序位回填：分组定义集中在 `router/menuGroups.ts` 的 `APP_MENU_META`，
 * 这里统一注入 `meta.group` / `meta.order`（与 applyPlanGates 同一手法，避免散写 21 个路由对象）。
 * 侧栏再由 useMenus 按分组产出分段结构，新增菜单只需在注册表加一行。
 */
applyMenuMeta(routes)

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 全局前置守卫（P4 升级，见《角色权限体系与组织协同设计》7.3）：
 * - 无令牌访问受保护页 → 跳登录并记录 redirect；
 * - 首屏/刷新后权限集未加载 → 先拉取 loadMe + permissions（失败即视为登录态失效）；
 * - 角色门禁（含存量 meta.role 兼容）与权限点门禁不通过 → 跳 /403；
 * - 前端门禁只改善体验，后端 /api/** 另有强校验（前端不是安全边界）。
 */
router.beforeEach(async (to) => {
  const userStore = useUserStore()
  const permStore = usePermissionStore()

  if (to.meta.public) {
    if (getToken() && (to.name === 'login' || to.name === 'register')) {
      return { path: userStore.homePath }
    }
    return true
  }
  if (!getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 首屏或刷新后：权限集合尚未加载 → 先拉取（失败即视为登录态失效）
  if (!permStore.loaded) {
    try {
      await Promise.all([userStore.loadMe(), permStore.load()])
    } catch {
      clearAuth()
      permStore.reset()
      return { path: '/login', query: { redirect: to.fullPath, reason: 'expired' } }
    }
  }

  // 角色门禁（含存量 meta.role='SUPER_ADMIN' 兼容：等价于 roles:[role]）
  const needRoles = to.meta.roles ?? (to.meta.role ? [to.meta.role] : undefined)
  if (needRoles?.length && !permStore.hasRole(...needRoles)) {
    return { path: '/403', query: { from: to.fullPath } }
  }

  // 权限点门禁（默认 OR，meta.permLogical='AND' 时需全部命中）
  const needPerms = to.meta.perm ? [to.meta.perm] : to.meta.perms
  if (needPerms?.length) {
    const ok =
      to.meta.permLogical === 'AND'
        ? permStore.hasAll(...needPerms)
        : permStore.hasAny(...needPerms)
    if (!ok) return { path: '/403', query: { from: to.fullPath } }
  }

  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string) || ''
  document.title = title ? `${title} · AI 数据助理` : 'AI 数据助理'
  // 明暗主题作用域：对外展示面（官网/登录注册/公开分享/公开大屏/嵌入式问数/邀请落地页）
  // 恒为浅色——它们的配色是"给外部访客看的设计"，不该被某个账号的主题偏好改变，
  // 尤其 /embed/chat 是嵌在客户站点 iframe 里的，跟着账号变黑属于意外行为。
  // 放在 afterEach 统一判定而不是各页面自管，避免漏一个入口就出现"某个公开页变黑"。
  const standalone = to.matched.some((r) => r.meta?.standalone === true)
  setUiThemeScope(!standalone)
})

export default router
