<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useMenus } from '@/composables/useMenus'
import ThemeToggle from '@/components/layout/ThemeToggle.vue'

/**
 * 平台后台外壳：克莱因蓝深色侧栏（区别于业务端 AppLayout）+ 顶栏“平台管理”。
 * 仅平台域角色可进入（路由父 meta.roles 粗粒度 + 子页 meta.perm 细分 + 后端 /api/admin/** 强校）。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/**
 * 侧栏导航：`/admin` 路由驱动 + admin:*:read 权限码过滤；分组定义已上移到 `router/menuGroups.ts`
 * 注册表（§9.2 运营组 / 治理组），此处不再重复维护分组清单，与业务端共用同一套分段渲染。
 */
const { sections } = useMenus('/admin')

const activePath = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) || '平台管理')

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
  else if (cmd === 'app') void router.push('/app/chat')
}
</script>

<template>
  <div class="admin-layout">
    <!-- ============ 侧栏（深色克莱因蓝） ============ -->
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo">
          <el-icon :size="22"><Setting /></el-icon>
        </div>
        <div class="brand-text">
          <span class="brand-name">平台管理</span>
          <span class="brand-sub">超级管理员控制台</span>
        </div>
      </div>

      <nav class="nav">
        <div v-for="g in sections" :key="g.key" class="nav-group">
          <div class="nav-group-label">{{ g.label }}</div>
          <router-link
            v-for="item in g.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ active: activePath === item.path }"
          >
            <el-icon :size="18"><component :is="item.icon" /></el-icon>
            <span class="nav-label">{{ item.title }}</span>
            <span v-if="activePath === item.path" class="nav-active-bar" />
          </router-link>
        </div>
      </nav>

      <div class="sidebar-footer">
        <button class="back-btn" @click="router.push('/app/chat')">
          <el-icon :size="16"><Back /></el-icon>
          <span>返回业务端</span>
        </button>
      </div>
    </aside>

    <!-- ============ 主区 ============ -->
    <div class="main">
      <header class="topbar">
        <div class="topbar-left">
          <h1 class="page-title">{{ currentTitle }}</h1>
          <el-tag type="danger" effect="dark" size="small" round class="role-tag">SUPER ADMIN</el-tag>
        </div>
        <div class="topbar-right">
          <!-- 界面明暗主题（账号级持久化：浅色/深色/跟随系统） -->
          <ThemeToggle />

          <el-dropdown trigger="click" @command="onCommand">
            <div class="user-chip">
              <div class="avatar">{{ userStore.displayName.charAt(0).toUpperCase() }}</div>
              <span class="uname truncate">{{ userStore.displayName }}</span>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="app">
                  <el-icon><ChatDotRound /></el-icon> 返回业务端
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
.admin-layout {
  display: flex;
  height: 100vh;
  width: 100%;
  overflow: hidden;
  /* 让出底部悬挂的 ICP 备案条（34px 高）：侧栏随之缩短，菜单栏不触底 */
  padding-bottom: 34px;
  box-sizing: border-box;
}

/* ---------- 侧栏 ---------- */
.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  background: linear-gradient(185deg, #00103a 0%, #00164f 45%, #002fa7 100%);
  display: flex;
  flex-direction: column;
  position: relative;
  box-shadow: 4px 0 24px rgba(0, 12, 48, 0.32);
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
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.28);
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
  color: rgba(255, 255, 255, 0.6);
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
.nav-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.nav-group + .nav-group {
  margin-top: 14px;
}
.nav-group-label {
  padding: 0 14px 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1px;
  color: rgba(255, 255, 255, 0.38);
  text-transform: uppercase;
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
  background: linear-gradient(135deg, rgba(0, 194, 255, 0.28), rgba(255, 255, 255, 0.1));
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
.sidebar-footer {
  padding: 14px 12px;
}
.back-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px;
  border-radius: var(--radius-md);
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s ease;
}
.back-btn:hover {
  background: rgba(255, 255, 255, 0.18);
}

/* ---------- 主区 ---------- */
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-page);
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
.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-title {
  font-size: 17px;
  font-weight: 700;
  margin: 0;
  color: var(--text-primary);
}
.role-tag {
  letter-spacing: 0.5px;
  font-weight: 700;
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
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
  max-width: 120px;
}
.content {
  flex: 1;
  overflow: auto;
  position: relative;
  padding: 20px 22px;
}
</style>
