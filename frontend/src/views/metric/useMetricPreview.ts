/**
 * 指标预览与分享（T7.3 自 MetricView 逐字抽离）。
 * 预览：打开对话框时实时重跑已存 SQL；分享：先取结果快照再打开 ShareDialog。
 * 指标以已存 SQL 取数，不消耗大模型额度、不计入问数配额。
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { metricApi } from '@/api/metric'
import type { ChartSpec, MetricVO, QueryResult } from '@/types'
import type { MetricCore } from './useMetricCore'

export function useMetricPreview(core: MetricCore) {
  /* ---------------- 预览（实时刷新） ---------------- */
  const previewVisible = ref(false)
  const previewLoading = ref(false)
  const previewMetric = ref<MetricVO | null>(null)
  const previewResult = ref<QueryResult | null>(null)
  const previewSpec = ref<ChartSpec | null>(null)

  async function openPreview(m: MetricVO): Promise<void> {
    previewMetric.value = m
    previewSpec.value = m.chartSpec ?? null
    previewResult.value = null
    previewVisible.value = true
    previewLoading.value = true
    try {
      const data = await metricApi.refresh(m.id)
      previewResult.value = data.result
      if (data.chartSpec) previewSpec.value = data.chartSpec
    } catch {
      // 拦截器已提示
    } finally {
      previewLoading.value = false
    }
  }

  /* ---------------- 分享（结果快照） ---------------- */
  const shareVisible = ref(false)
  const shareTarget = ref<{
    datasourceId: number | null
    question: string
    sql: string | null
    result: QueryResult | null
    chart: ChartSpec | null
  } | null>(null)

  /**
   * 分享指标：先实时重跑已存 SQL 取到当前结果快照，再打开分享对话框生成公开链接。
   * 与「对话问数」结果分享一致（复用 ShareDialog / shareApi）；指标以已存 SQL 取数，不消耗大模型额度。
   */
  async function openShare(m: MetricVO): Promise<void> {
    if (m.datasourceId == null) {
      ElMessage.warning('该指标缺少数据源，无法分享')
      return
    }
    try {
      const data = await metricApi.refresh(m.id)
      if (!data.result) {
        ElMessage.warning('该指标暂无可分享的结果')
        return
      }
      shareTarget.value = {
        datasourceId: m.datasourceId,
        question: m.question || m.name,
        sql: m.sql ?? data.result.sql ?? null,
        result: data.result,
        chart: data.chartSpec ?? m.chartSpec ?? null
      }
      shareVisible.value = true
    } catch {
      // 拦截器已提示
    }
  }

  /* ---------------- 删除 ---------------- */
  async function removeMetric(m: MetricVO): Promise<void> {
    try {
      await ElMessageBox.confirm(`确定删除指标「${m.name}」吗？已加入的驾驶舱槽位将变为空。`, '删除确认', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
    try {
      await metricApi.remove(m.id)
      ElMessage.success('已删除')
      await core.load()
    } catch {
      // 拦截器已提示
    }
  }

  return { previewVisible, previewLoading, previewMetric, previewResult, previewSpec, openPreview,
    shareVisible, shareTarget, openShare, removeMetric }
}
