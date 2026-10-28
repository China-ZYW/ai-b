/**
 * 数据源管理页编排（T7.3 自 DataSourceView 拆分）：
 * 组合三个协作 composable 为单一 reactive 上下文并 provide，子组件经 useDsCtx 注入。
 * 注入裸 symbol key（与 Metric 同款先例），避免 composable ↔ 组件的循环导入。
 */
import { inject, provide, reactive } from 'vue'
import { useDsCore } from './useDsCore'
import { useDsForm } from './useDsForm'
import { useDsActions } from './useDsActions'

export const DsCtxKey = Symbol('DataSourceCtx')

export function useDataSource() {
  const core = useDsCore()
  const form = useDsForm()
  const actions = useDsActions()
  const ctx = reactive({ ...core, ...form, ...actions })
  provide(DsCtxKey, ctx)
  return ctx
}

/** reactive 自动解包 ref，类型取 ReturnType（与 Metric/Editor 同款先例），规避 InjectionKey 与 ReturnType 推导成环。 */
export type DataSourceCtx = ReturnType<typeof useDataSource>

export function useDsCtx(): DataSourceCtx {
  const ctx = inject<DataSourceCtx | undefined>(DsCtxKey)
  if (!ctx) throw new Error('useDsCtx 必须在 useDataSource() 所在的组件树内调用')
  return ctx
}
