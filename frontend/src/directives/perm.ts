import { watch, type Directive } from 'vue'
import { usePermissionStore } from '@/stores/permission'

/** 挂载了 v-perm 的元素上暂存 watch 停止句柄，unmounted 时释放，避免内存泄漏。 */
type PermElement = HTMLElement & { __permStop?: () => void }

/**
 * 按钮级权限指令（P4，见《角色权限体系与组织协同设计》7.5）。
 *
 * ⚠️ 自定义指令不会随 store 变化自动重算（Vue 只在组件重渲染时调 updated），故内部自行 watch
 *    权限 store 的 {@code revision}（每次 load/reset 递增），切换团队/权限变更/登出后主动重判，
 *    避免“权限已收回但按钮还在”。绝大多数场景仍首选 `v-if="can('x')"`（天然响应式），
 *    本指令用于静态工具条、第三方组件插槽内不便写 `v-if` 的场合。
 *
 * 修饰符 `.disable`：无权时置 disabled + tooltip 说明而非移除元素（保留功能可发现性）。
 *
 * @example <el-button v-perm="'datasource:config:write'">新增</el-button>
 * @example <el-button v-perm.disable="'org:org:write'">组织设置</el-button>
 */
export const vPerm: Directive<PermElement, string | string[]> = {
  mounted(el, binding) {
    const permStore = usePermissionStore()
    const apply = (): void => {
      const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
      const ok = codes.some((c) => permStore.has(c))
      if (ok) {
        el.style.removeProperty('display')
        el.removeAttribute('disabled')
        el.classList.remove('is-disabled')
        el.removeAttribute('title')
      } else if (binding.modifiers.disable) {
        el.setAttribute('disabled', 'disabled')
        el.classList.add('is-disabled')
        el.setAttribute('title', '当前角色无权执行此操作')
      } else {
        el.style.display = 'none'
      }
    }
    apply()
    el.__permStop = watch(() => permStore.revision, apply)
  },
  updated(el, binding) {
    // 组件重渲染时按最新绑定值重判（binding.value 可能变化）
    const permStore = usePermissionStore()
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    const ok = codes.some((c) => permStore.has(c))
    if (ok) {
      el.style.removeProperty('display')
      el.removeAttribute('disabled')
      el.classList.remove('is-disabled')
    } else if (binding.modifiers.disable) {
      el.setAttribute('disabled', 'disabled')
      el.classList.add('is-disabled')
    } else {
      el.style.display = 'none'
    }
  },
  unmounted(el) {
    el.__permStop?.()
    delete el.__permStop
  }
}
