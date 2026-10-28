import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { DashboardFilter } from '@/types'

/**
 * 驾驶舱联动下钻状态（V4.44）：跨 widget / 跨视图（大屏、公开分享、编辑器预览）共享的整屏筛选条件。
 *
 * <p>用户点击任一 widget 的维度成员后，{@link setFilter} 写入 {@code {dim, value}}；所有订阅本 store 的
 * 渲染点用同一 filter 重新调用 {@code dashboardApi.data(id, filter)}，后端按维度 SQL 切片，实现"点一个、其余跟着变"。
 *
 * <p>范围（scope）为整屏：filter 不含 metricId，作用于屏内全部 widget。{@link clearFilter} 清除后回到全量视图。
 * 单指标预览（MetricView）不订阅本 store——它不属驾驶舱布局上下文。
 */
export const useDashboardLinkageStore = defineStore('dashboardLinkage', () => {
  const activeFilter = ref<DashboardFilter | null>(null)

  const hasFilter = computed(() => activeFilter.value != null && !!activeFilter.value.dim)

  /** 写入/更新整屏下钻筛选；传 null 等价于清除。 */
  function setFilter(f: DashboardFilter | null): void {
    activeFilter.value = f && f.dim ? f : null
  }

  /** 清除整屏下钻筛选，回到全量视图。 */
  function clearFilter(): void {
    activeFilter.value = null
  }

  return { activeFilter, hasFilter, setFilter, clearFilter }
})
