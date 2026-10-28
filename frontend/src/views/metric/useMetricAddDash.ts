/**
 * 加入驾驶舱（T7.3 自 MetricView 逐字抽离）：选择一个已有驾驶舱，
 * 把指标追加到其布局底部（网格尺寸与后端 packGrid 一致），保存后跳转编辑器。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi } from '@/api/dashboard'
import type { DashboardVO, MetricVO, WidgetLayout } from '@/types'
import { sizeFor } from './metricUtils'
import type { MetricCore } from './useMetricCore'

export function useMetricAddDash(core: MetricCore) {
  const addVisible = ref(false)
  const addLoading = ref(false)
  const addMetric = ref<MetricVO | null>(null)
  const dashboards = ref<DashboardVO[]>([])
  const pickedDashId = ref<number | null>(null)

  async function openAdd(m: MetricVO): Promise<void> {
    addMetric.value = m
    pickedDashId.value = null
    addVisible.value = true
    addLoading.value = true
    try {
      dashboards.value = await dashboardApi.list()
    } catch {
      // 拦截器已提示
    } finally {
      addLoading.value = false
    }
  }

  const adding = ref(false)
  async function confirmAdd(): Promise<void> {
    const dash = dashboards.value.find((d) => d.id === pickedDashId.value)
    const m = addMetric.value
    if (!dash || !m) {
      ElMessage.warning('请选择要加入的驾驶舱')
      return
    }
    adding.value = true
    try {
      const layout: WidgetLayout[] = dash.layout ? dash.layout.map((w) => ({ ...w })) : []
      const maxY = layout.reduce((acc, w) => Math.max(acc, w.y + w.h), 0)
      const { w, h } = sizeFor(m.chartSpec)
      layout.push({
        i: `w${Date.now()}`,
        metricId: m.id,
        x: 0,
        y: maxY,
        w,
        h,
        title: m.alias || m.name
      })
      await dashboardApi.update(dash.id, {
        name: dash.name,
        description: dash.description ?? null,
        theme: dash.theme ?? null,
        cols: dash.cols ?? 12,
        refreshSeconds: dash.refreshSeconds ?? null,
        isScreen: dash.isScreen ?? 0,
        layout
      })
      ElMessage.success(`已加入「${dash.name}」`)
      addVisible.value = false
      void core.router.push(`/dashboards/${dash.id}/edit`)
    } catch {
      // 拦截器已提示
    } finally {
      adding.value = false
    }
  }

  return { addVisible, addLoading, addMetric, dashboards, pickedDashId, adding, openAdd, confirmAdd }
}
