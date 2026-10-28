/**
 * 指标分组管理（T7.3 自 MetricView 逐字抽离）：移动指标到分组、
 * 分组新建/重命名/删除对话框状态机。分组 CRUD 走 metricGroupApi，
 * 移动走专用端点 moveGroup（不并入全量 update，避免 payload 不含 groupId 时清空归属）。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { metricApi, metricGroupApi } from '@/api/metric'
import type { DashboardGroupForm, DashboardMoveForm, MetricVO } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'
import { descendantGroupIds, type GroupTreeNode } from './metricUtils'
import type { MetricCore } from './useMetricCore'

export function useMetricGroups(core: MetricCore) {
  /** 移动指标到指定分组（groupId 空=移出分组）。走专用端点，不触发全量 update。 */
  async function moveMetric(m: MetricVO, groupId: number | null): Promise<void> {
    try {
      const payload: DashboardMoveForm = { groupId }
      await metricApi.moveGroup(m.id, payload)
      ElMessage.success('已更新分组')
      await core.load()
    } catch {
      // 拦截器已提示
    }
  }

  /* ---------------- 分组管理 ---------------- */
  const groupDialogVisible = ref(false)
  const groupFormMode = ref<'create' | 'edit'>('create')
  const groupEditId = ref<number | null>(null)
  const groupForm = reactive<{ name: string; parentId: number | null }>({ name: '', parentId: null })
  const { busy: groupSubmitting, run: runGroupSubmit } = useActionGuard()

  /** 上级分组可选列表：编辑时排除自身及后代（防成环）。 */
  const groupParentOptions = computed<{ id: number; name: string }[]>(() => {
    const exclude = new Set<number>()
    if (groupFormMode.value === 'edit' && groupEditId.value != null) {
      for (const id of descendantGroupIds(core.groups.value, groupEditId.value)) exclude.add(id)
    }
    return core.groups.value.filter((g) => !exclude.has(g.id)).map((g) => ({ id: g.id, name: g.name }))
  })

  function openCreateGroup(parentId: number | null): void {
    groupFormMode.value = 'create'
    groupEditId.value = null
    groupForm.name = ''
    groupForm.parentId = parentId
    groupDialogVisible.value = true
  }

  function openEditGroup(node: GroupTreeNode): void {
    groupFormMode.value = 'edit'
    groupEditId.value = node.id as number
    groupForm.name = node.name
    groupForm.parentId = node.parentId
    groupDialogVisible.value = true
  }

  async function submitGroup(): Promise<void> {
    const name = groupForm.name.trim()
    if (!name) {
      ElMessage.warning('请输入分组名称')
      return
    }
    await runGroupSubmit(async () => {
      try {
        const payload: DashboardGroupForm = { name, parentId: groupForm.parentId }
        if (groupFormMode.value === 'create') {
          await metricGroupApi.create(payload)
          ElMessage.success('分组已创建')
        } else {
          await metricGroupApi.update(groupEditId.value!, payload)
          ElMessage.success('分组已更新')
        }
        groupDialogVisible.value = false
        await core.load()
      } catch {
        // 拦截器已提示
      }
    })
  }

  async function removeGroup(node: GroupTreeNode): Promise<void> {
    try {
      await ElMessageBox.confirm(
        `确定删除分组「${node.name}」吗？仅空分组（无子分组、无指标）可删除，有成员时请先移出。`,
        '删除分组',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    try {
      await metricGroupApi.remove(node.id as number)
      ElMessage.success('已删除')
      // 若正停留在被删分组，回退到「全部」
      if (core.selectedKey.value === node.id) core.selectedKey.value = 'all'
      await core.load()
    } catch {
      // 拦截器已提示
    }
  }

  return {
    moveMetric,
    groupDialogVisible,
    groupFormMode,
    groupEditId,
    groupForm,
    groupSubmitting,
    groupParentOptions,
    openCreateGroup,
    openEditGroup,
    submitGroup,
    removeGroup
  }
}
