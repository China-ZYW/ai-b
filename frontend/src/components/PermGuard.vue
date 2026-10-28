<script setup lang="ts">
import { computed } from 'vue'
import { usePermissionStore } from '@/stores/permission'

/**
 * 按钮级权限包裹组件（P4，见《角色权限体系与组织协同设计》7.5）。
 *
 * 相比 `v-if="can('x')"` 的直接移除，本组件在 `mode="disable"` 下渲染禁用态占位 + tooltip，
 * 保留功能可发现性（数据源页等管理员入口更适合“看得见但点不动”而非凭空消失）。
 * 判据同源于 permission store，天然响应式（切换团队/权限变更后自动重算）。
 */
const props = withDefaults(
  defineProps<{
    /** 单个权限点。 */
    code?: string
    /** 多个权限点。 */
    codes?: string[]
    /** 多权限点判定逻辑，默认 OR。 */
    logical?: 'AND' | 'OR'
    /** hide=无权时不渲染；disable=无权时渲染禁用态占位 + tooltip。 */
    mode?: 'hide' | 'disable'
    /** disable 模式的提示文案。 */
    tip?: string
  }>(),
  {
    code: undefined,
    codes: () => [],
    logical: 'OR',
    mode: 'hide',
    tip: '当前角色无权执行此操作'
  }
)

const permStore = usePermissionStore()

const allowed = computed<boolean>(() => {
  const list = props.code ? [props.code] : props.codes
  if (!list.length) return true // 未声明权限点默认放行
  return props.logical === 'AND' ? permStore.hasAll(...list) : permStore.hasAny(...list)
})
</script>

<template>
  <slot v-if="allowed" />
  <el-tooltip v-else-if="mode === 'disable'" :content="tip" placement="top">
    <span class="perm-guard-disabled"><slot /></span>
  </el-tooltip>
</template>

<style scoped>
.perm-guard-disabled {
  display: inline-flex;
  opacity: 0.5;
  cursor: not-allowed;
  pointer-events: auto;
}
.perm-guard-disabled :deep(button),
.perm-guard-disabled :deep(.el-button) {
  pointer-events: none;
}
</style>
