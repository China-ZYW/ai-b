/**
 * 指标库编排器（T7.3）：组装共享态 core 与 5 个协作 composable，
 * 聚合为 reactive ctx 后 provide 下发；子组件通过 useMetricCtx() inject 消费。
 *
 * 沿用 DashboardEditor 拆分（T7.1）的配方与四个坑的解法：
 * - reactive(merged) 自动解包 ref/computed，子组件模板 ctx.xxx 直接 v-model 可写；
 * - 上下文 key 用裸 Symbol（避免 InjectionKey<ReturnType> 与函数体互相引用成环）；
 * - el-form 的实例 ref 由组件模板回调式 :ref 写回 ctx.editFormRef（透穿到内部 ref）。
 */
import { inject, provide, reactive } from 'vue'
import { useMetricCore } from './useMetricCore'
import { useMetricGroups } from './useMetricGroups'
import { useMetricPreview } from './useMetricPreview'
import { useMetricEdit } from './useMetricEdit'
import { useMetricAddDash } from './useMetricAddDash'
import { useMetricAi } from './useMetricAi'

export function useMetric() {
  const core = useMetricCore()
  const groups = useMetricGroups(core)
  const preview = useMetricPreview(core)
  const edit = useMetricEdit(core)
  const addDash = useMetricAddDash(core)
  const ai = useMetricAi(core)

  const ctx = reactive({
    ...core,
    ...groups,
    ...preview,
    ...edit,
    ...addDash,
    ...ai
  })
  provide(MetricCtxKey, ctx)
  return ctx
}

export type MetricCtx = ReturnType<typeof useMetric>

/** 裸 symbol：类型在 inject 侧断，规避 InjectionKey 与 ReturnType 推导成环。 */
export const MetricCtxKey = Symbol('MetricViewCtx')

export function useMetricCtx(): MetricCtx {
  const ctx = inject<MetricCtx | undefined>(MetricCtxKey)
  if (!ctx) throw new Error('useMetricCtx 必须在 MetricView（useMetric 已执行）子树内调用')
  return ctx
}
