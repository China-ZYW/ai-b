<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { usePermissionStore } from '@/stores/permission'
import { useDatasourceStore } from '@/stores/datasource'
import { useConfigStore } from '@/stores/config'
import { useMenus } from '@/composables/useMenus'
import OrgSwitcher from '@/components/OrgSwitcher.vue'
import MessageBell from '@/components/layout/MessageBell.vue'
import ThemeToggle from '@/components/layout/ThemeToggle.vue'
import { fetchThemes } from '@/theme/dashboardTheme'
import { planLabel } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const permStore = usePermissionStore()
const dsStore = useDatasourceStore()
const configStore = useConfigStore()

/**
 * 侧栏导航：由路由表驱动 + 权限码过滤，并按 `router/menuGroups.ts` 注册表产出**分组层级**
 * （原先 21 项平铺无层级）。新增菜单只需在注册表加一行，不必再改本组件。
 */
const { sections } = useMenus('/app')

const activePath = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) || 'AI 数据助理')
const quotaText = computed(() => {
  const q = userStore.quota
  if (!q) return ''
  if (q.limit < 0) return '不限量'
  return `今日剩余 ${q.remaining}/${q.limit}`
})

onMounted(() => {
  void dsStore.load().catch(() => undefined)
  void userStore.refreshQuota()
  void userStore.loadTenants() // 多组织身份：拉取可切换组织列表（驱动顶栏切换器）
  void fetchThemes() // 认证态拉取主题令牌（大屏/图表样式配置化）
  void configStore.load() // 预加载结果展示配置，ResultPanel 首屏即用
})

async function onLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  userStore.logout()
  await router.push('/login')
}

function onCommand(cmd: string): void {
  if (cmd === 'logout') void onLogout()
  else if (cmd === 'profile') void router.push('/profile')
  else if (cmd === 'admin') void router.push('/admin')
}
</script>

<template>
  <div class="app-layout" :class="{ collapsed: appStore.sidebarCollapsed }">
    <!-- ============ 侧栏（克莱因蓝深色渐变） ============ -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo">
          <el-icon :size="22"><DataAnalysis /></el-icon>
        </div>
        <div v-show="!appStore.sidebarCollapsed" class="brand-text">
          <span class="brand-name">AI 数据助理</span>
          <span class="brand-sub">对话式问数</span>
        </div>
      </div>

      <nav class="nav">
        <div v-for="g in sections" :key="g.key" class="nav-group">
          <!-- 折叠态隐藏分组标题，改以细分割线维持分组间距 -->
          <div v-show="!appStore.sidebarCollapsed" class="nav-group-label">{{ g.label }}</div>
          <div v-show="appStore.sidebarCollapsed" class="nav-group-sep" />
          <router-link
            v-for="item in g.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: activePath === item.path }"
          >
            <el-icon :size="18"><component :is="item.icon" /></el-icon>
            <span v-show="!appStore.sidebarCollapsed" class="nav-label">{{ item.title }}</span>
            <span v-if="activePath === item.path" class="nav-active-bar" />
          </router-link>
        </div>
      </nav>

      <div class="sidebar-footer">
        <div v-show="!appStore.sidebarCollapsed" class="plan-chip">
          <el-icon :size="14"><Medal /></el-icon>
          <span>{{ planLabel(userStore.plan) }}</span>
        </div>
        <button class="collapse-btn" @click="appStore.toggleSidebar()">
          <el-icon :size="18">
            <component :is="appStore.sidebarCollapsed ? 'Expand' : 'Fold'" />
          </el-icon>
        </button>
      </div>
    </aside>

    <!-- ============ 主区 ============ -->
    <div class="main">
      <!-- 协助模式横幅（P4，见《角色权限体系与组织协同设计》7.8）：影子令牌只读，顶栏常驻红色告知 -->
      <div v-if="permStore.assistMode" class="assist-banner">
        <el-icon :size="15"><WarningFilled /></el-icon>
        <span>平台技术支持正在协助查看本组织数据（只读，全部写操作已禁用）</span>
      </div>
      <header class="topbar">
        <div class="topbar-left">
          <h1 class="page-title">{{ currentTitle }}</h1>
        </div>
        <div class="topbar-right">
          <!-- 数据源快选 -->
          <div class="ds-select">
            <el-icon :size="15" class="ds-icon"><Coin /></el-icon>
            <el-select
              :model-value="dsStore.currentId"
              placeholder="选择数据源"
              size="default"
              style="width: 190px"
              @update:model-value="(v: number) => dsStore.select(v)"
            >
              <el-option
                v-for="d in dsStore.list"
                :key="d.id"
                :label="d.name"
                :value="d.id"
              />
            </el-select>
          </div>

          <!-- 配额 -->
          <el-tooltip v-if="quotaText" :content="`套餐：${planLabel(userStore.plan)}`" placement="bottom">
            <div class="quota-badge">
              <el-icon :size="14"><Odometer /></el-icon>
              <span>{{ quotaText }}</span>
            </div>
          </el-tooltip>

          <!-- 平台管理入口（平台域角色可见：超管/运营/审计/支持） -->
          <el-tooltip v-if="permStore.isPlatform" content="进入平台后台" placement="bottom">
            <button class="admin-entry" @click="router.push('/admin')">
              <el-icon :size="15"><Setting /></el-icon>
              <span>平台管理</span>
            </button>
          </el-tooltip>

          <!-- 多组织切换器（仅多组织身份显示，见 C 缺口实现） -->
          <OrgSwitcher />

          <!-- 站内信铃铛（未读角标 + 下拉速览） -->
          <MessageBell />

          <!-- 界面明暗主题（账号级持久化：浅色/深色/跟随系统） -->
          <ThemeToggle />

          <!-- 用户菜单 -->
          <el-dropdown trigger="click" @command="onCommand">
            <div class="user-chip">
              <div class="avatar">{{ userStore.displayName.charAt(0).toUpperCase() }}</div>
              <span class="uname truncate">{{ userStore.displayName }}</span>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="permStore.isPlatform" command="admin">
                  <el-icon><Setting /></el-icon> 平台管理
                </el-dropdown-item>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon> 个人中心
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  width: 100%;
  overflow: hidden;
  /* 让出底部悬挂的 ICP 备案条（34px 高）：容器底部留白，
  // 侧栏/主区随之缩短，菜单栏不触底，与备案条之间留有空隙；备案条整屏满宽。 */
  padding-bottom: 34px;
  box-sizing: border-box;
}

/* ---------- 侧栏 ---------- */
.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  background: var(--grad-sidebar);
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
  position: relative;
  box-shadow: 4px 0 24px rgba(0, 22, 79, 0.18);
}
.app-layout.collapsed .sidebar {
  width: var(--sidebar-w-mini);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 18px;
  height: var(--topbar-h);
}
.brand-logo {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: 11px;
  background: linear-gradient(135deg, #ffffff 0%, #cfe0ff 100%);
  color: var(--ikb);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  overflow: hidden;
}
.brand-name {
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}
.brand-sub {
  color: rgba(255, 255, 255, 0.62);
  font-size: 11px;
  white-space: nowrap;
}

.nav {
  flex: 1;
  padding: 10px 12px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
/* 侧栏分组：标题 + 其下菜单项（层级由 useMenus 按注册表的 sections 驱动） */
.nav-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.nav-group + .nav-group {
  margin-top: 8px;
}
.nav-group-label {
  padding: 4px 14px 2px;
  color: rgba(255, 255, 255, 0.42);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  white-space: nowrap;
}
/* 折叠态：只留一条细分割线代替标题，避免只看到一堆图标时失去分组感 */
.nav-group-sep {
  height: 1px;
  margin: 6px 10px 2px;
  background: rgba(255, 255, 255, 0.14);
}
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border-radius: var(--radius-md);
  color: rgba(255, 255, 255, 0.72);
  font-size: 14px;
  font-weight: 500;
  transition: background 0.2s ease, color 0.2s ease;
  white-space: nowrap;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.nav-item.active {
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.22), rgba(255, 255, 255, 0.1));
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.18);
}
.nav-active-bar {
  position: absolute;
  left: -12px;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 22px;
  border-radius: 0 4px 4px 0;
  background: linear-gradient(180deg, #00c2ff, #ffffff);
}
.app-layout.collapsed .nav-item {
  justify-content: center;
  padding: 11px 0;
}

.sidebar-footer {
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}
.plan-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}
.collapse-btn {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: rgba(255, 255, 255, 0.85);
  width: 34px;
  height: 34px;
  border-radius: 9px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s ease;
}
.collapse-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

/* ---------- 主区 ---------- */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-page);
}
.assist-banner {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 16px;
  background: linear-gradient(90deg, #d92d20, #f04438);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.3px;
}
.topbar {
  height: var(--topbar-h);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  background: var(--grad-topbar);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--border-light);
  box-shadow: 0 2px 12px rgba(0, 31, 112, 0.04);
  z-index: 5;
}
.page-title {
  font-size: 17px;
  font-weight: 700;
  margin: 0;
  color: var(--text-primary);
  white-space: nowrap;
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.ds-select {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ds-icon {
  color: var(--ikb);
}
.quota-badge {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--ikb-pale);
  color: var(--ikb);
  font-size: 12px;
  font-weight: 600;
}
.admin-entry {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 999px;
  border: none;
  cursor: pointer;
  background: var(--grad-btn);
  white-space: nowrap;
  flex-shrink: 0;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
  transition: filter 0.2s ease;
}
.admin-entry:hover {
  filter: brightness(1.08);
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px 4px 4px;
  border-radius: 999px;
  transition: background 0.2s ease;
  outline: none;
}
.user-chip:hover {
  background: var(--ikb-ghost);
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--grad-brand);
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  box-shadow: var(--shadow-brand);
}
.uname {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-regular);
  max-width: 100px;
}

.content {
  flex: 1;
  overflow: hidden;
  position: relative;
}
</style>
