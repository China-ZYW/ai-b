/**
 * 驾驶舱编辑器编排 composable（T7.1）：组合 core/layout/widgets/dialogs/ai/persistence 六个协作单元，
 * 以 reactive 聚合（自动解包各 ref）后经 provide 下发给 editor/ 子组件，避免海量 props 透传。
 * 视图本体只保留生命周期编排（load/observeCanvas）。
 */
import { inject, provide, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchThemes, themeOptions } from '@/theme/dashboardTheme'
import { useEditorCore } from './useEditorCore'
import { useEditorLayout } from './useEditorLayout'
import { useEditorWidgets } from './useEditorWidgets'
import { useEditorDialogs } from './useEditorDialogs'
import { useEditorAi } from './useEditorAi'
import { useEditorPersistence } from './useEditorPersistence'

export function useDashboardEditor() {
  const route = useRoute()
  const router = useRouter()
  const id = Number(route.params.id)

  const core = useEditorCore()
  const lay = useEditorLayout(core)
  const widgets = useEditorWidgets(core, lay)
  const dialogs = useEditorDialogs(core)
  const ai = useEditorAi(core)
  const persist = useEditorPersistence(core, lay, id, router)

  // widget 命令菜单里的「编辑内容/转信息图/转列表/编辑图片」走弹窗协作类：编排器回填，断开 widgets↔dialogs 依赖。
  widgets.bindDialogOpeners({
    openInfoEditor: dialogs.openInfoEditor,
    openImageEditor: dialogs.openImageEditor,
    openListEditor: dialogs.openListEditor
  })

  const merged = {
    id,
    route,
    router,
    ...core,
    ...lay,
    ...widgets,
    ...dialogs,
    ...ai,
    ...persist,
    /** 挂载后拉取远端主题并刷新选择器选项（视图 onMounted 调用）。 */
    async hydrateThemes(): Promise<void> {
      await fetchThemes()
      core.themeOpts.value = themeOptions()
    }
  }

  // reactive 聚合：模板/子组件里 ctx.xxx 自动解包 ref，computed 读写透传（如 ctx.freeCanvas 可 v-model）。
  const ctx = reactive(merged)
  provide(EditorCtxKey, ctx)
  return ctx
}

/** 编辑器模块内共享上下文（reactive 聚合后、ref 已解包的视图态）。 */
export type EditorCtx = ReturnType<typeof useDashboardEditor>

// 裸 symbol key：泛型在 inject 侧指定，避免 InjectionKey<EditorCtx> 与 useDashboardEditor 返回类型互相推导成环。
export const EditorCtxKey = Symbol('DashboardEditorCtx')

/** 子组件侧获取编辑器上下文（必须在 DashboardEditorView 子树内使用）。 */
export function useEditorCtx(): EditorCtx {
  const ctx = inject<EditorCtx | undefined>(EditorCtxKey)
  if (!ctx) throw new Error('useEditorCtx 必须在 DashboardEditorView 子树内调用')
  return ctx
}
