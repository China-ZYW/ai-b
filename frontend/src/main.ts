import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { vPerm } from './directives/perm'
import { initAppTheme } from './theme/appTheme'

// 样式引入顺序：Element Plus 基础 → EP 深色变量 → 克莱因蓝主题覆写 → 全局工具类
// dark/css-vars.css 以 html.dark 为选择器提供 EP 组件级深色令牌；必须在 element.css 之前，
// 否则本项目的克莱因蓝覆写会盖在深色变量之上，深色模式下按钮/输入框仍是浅色那套
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/element.css'
import './styles/global.css'

const app = createApp(App)

// 注册全部 Element Plus 图标为全局组件（模板中直接用 <el-icon><ChatDotRound/></el-icon>）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 按钮级权限指令 v-perm（P4，见《角色权限体系与组织协同设计》7.5）
app.directive('perm', vPerm)

// 明暗主题必须在挂载前初始化：读本地镜像 + 绑定系统深浅监听，避免首帧后补 class 造成的闪烁
initAppTheme()

/**
 * 版本热更新兜底：部署后原子切换 dist 会删除旧哈希 chunk，旧标签页（其 index.html 仍引用
 * 已删除的 import 路径）懒加载菜单路由时会 404 → "Failed to fetch dynamically imported
 * module"，且路由 Promise 被静默吞掉，表现为"菜单点了没反应、路由跳不动"。
 *
 * <p>此处统一捕获该类分块加载失败：弹一次"检测到新版本"提示，短暂延迟后整页刷新拉取新
 * index.html（配合 nginx 对 index.html 的 no-cache、对 /assets 的 immutable 长缓存，
 * 刷新后即恢复正常）。这样线上部署不再"弄死"已经打开的标签页。
 */
function isChunkLoadError(e: unknown): boolean {
  const msg = e instanceof Error ? e.message : String(e ?? '')
  return (
    msg.includes('Failed to fetch dynamically imported module') ||
    msg.includes('Importing a module script failed') ||
    msg.includes('error loading dynamically imported module')
  )
}
let chunkReloading = false
function handleChunkLoadFailure(reason: unknown): void {
  if (!isChunkLoadError(reason) || chunkReloading) return
  chunkReloading = true
  ElMessage.warning({ message: '检测到新版本，页面即将自动刷新…', duration: 1600 })
  window.setTimeout(() => window.location.reload(), 1700)
}
router.onError(handleChunkLoadFailure)
window.addEventListener('unhandledrejection', (e) => handleChunkLoadFailure(e.reason))

app.mount('#app')
