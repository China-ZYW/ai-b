<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import IcpFooter from '@/components/common/IcpFooter.vue'

// 应用启动：若本地已有令牌，静默拉取最新用户资料与配额（令牌失效会被拦截器清掉）
const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

/**
 * 顶部导航进度条：路由切换（尤其懒加载分块）时给出即时反馈，避免"点了没反应"的卡顿感。
 * active=爬升到 ~70%，done=瞬间到 100% 并淡出。
 */
const navCls = ref('')
let navTimer: number | undefined
router.beforeEach((to, from) => {
  if (to.path === from.path) return true
  window.clearTimeout(navTimer)
  navCls.value = 'active'
  return true
})
function finishNav() {
  navCls.value = 'done'
  navTimer = window.setTimeout(() => {
    navCls.value = ''
  }, 450)
}
router.afterEach(finishNav)
router.onError(finishNav)

onMounted(() => {
  // 公开页（分享 / 公开大屏 / 邀请落地等）免登录，跳过启动期登录态探测：
  // 否则残留的失效令牌会让 /api/user/me 返回 401，被响应拦截器整页跳转登录页，
  // 破坏「公开页凭链接免登录访问」的契约（路由守卫已放行，但本组件是全局根组件，不受其约束）。
  if (userStore.isLoggedIn && !route.meta.public) {
    void userStore.loadMe().catch(() => undefined)
    void userStore.refreshQuota()
  }
})
</script>

<template>
  <!-- 顶部导航进度条（提升路由切换的即时反馈，缓解"不丝滑"观感） -->
  <div class="nav-progress" :class="navCls" />

  <router-view v-slot="{ Component }">
    <transition name="fade-slide" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>

  <!-- 全站底部悬挂 ICP 备案条 -->
  <IcpFooter />
</template>
