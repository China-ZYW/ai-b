import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 全局 UI 状态 store：侧栏折叠、当前数据源下拉可见性等纯前端状态。
 */
export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return { sidebarCollapsed, toggleSidebar }
})
