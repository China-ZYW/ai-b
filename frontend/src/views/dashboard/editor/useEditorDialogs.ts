/**
 * 信息图 / 图片 / 列表三类组件弹窗协作 composable（T7.1 自 DashboardEditorView.vue 拆出，逻辑逐字搬移）。
 * 弹窗状态机：picker（新建入口）→ editor（编辑确认）；新建模式确认前不落组件，取消不留空白占位。
 */
import { computed, ref } from 'vue'
import { INFO_DEFAULT_TEMPLATE } from '@/components/dashboard/infographicTemplates'
import type { ImageWidgetSpec, InfographicSpec, ListWidgetSpec } from '@/types'
import type { EditorCore } from './useEditorCore'

export function useEditorDialogs(core: EditorCore) {
  const { layout, metaMap, nextKey, fetchWidget } = core

  /* ---------------- 信息图组件 ---------------- */

  /** 添加入口画廊（模板缩略图）状态。 */
  const pickerVisible = ref(false)
  /** 信息图编辑器弹窗状态。 */
  const infoVisible = ref(false)
  /** 编辑器目标组件 key：空串表示「新建」模式。 */
  const infoTargetKey = ref('')
  /** 新建模式下，编辑器确认前暂不落组件，避免取消时残留空白占位。 */
  const infoCreating = ref(false)
  /** 新建模式传入编辑器的初始 spec（含用户选定的模板）。 */
  const pendingNewSpec = ref<InfographicSpec | null>(null)

  /** 编辑器当前 spec：新建取 pendingNewSpec，编辑取目标组件的 infographic。 */
  const infoSpec = computed<InfographicSpec | null>(() =>
    infoCreating.value ? pendingNewSpec.value : infoTargetKey.value ? metaMap[infoTargetKey.value]?.infographic ?? null : null
  )
  /** 编辑器当前标题（仅编辑模式同步组件标题）。 */
  const infoTitle = computed(() =>
    infoCreating.value ? '' : infoTargetKey.value ? metaMap[infoTargetKey.value]?.title ?? '' : ''
  )

  /** 打开「添加信息图」模板画廊。 */
  function openInfoPicker(): void {
    pickerVisible.value = true
  }

  /** 画廊选定模板：进入编辑器的新建模式（此时尚未创建组件）。 */
  function onPickTemplate(tpl: string): void {
    infoCreating.value = true
    pendingNewSpec.value = { template: tpl, theme: null, items: [] }
    infoTargetKey.value = ''
    infoVisible.value = true
  }

  /** 打开某组件的信息图编辑器（chart 组件切到信息图也会走这里）。 */
  function openInfoEditor(iKey: string): void {
    const m = metaMap[iKey]
    if (!m) return
    // 切到信息图：丢弃指标绑定与图表标注（信息图不取数），保留视觉样式。
    if (m.kind !== 'infographic') {
      m.kind = 'infographic'
      m.metricId = null
      m.chartStyle = null
      core.widgetData[iKey] = { result: null, chartSpec: null, loading: false }
      if (!m.infographic) m.infographic = { template: INFO_DEFAULT_TEMPLATE, theme: null, items: [] }
    }
    infoCreating.value = false
    infoTargetKey.value = iKey
    infoVisible.value = true
  }

  /**
   * 编辑器确认：
   * - 新建模式（画廊进来）：在画布底部追加组件并写入 spec；
   * - 编辑模式：写回目标组件。
   * 取消则什么都不落，因此新建时不会残留空白占位。
   */
  function onInfoConfirm(payload: { title: string; spec: InfographicSpec }): void {
    if (infoCreating.value || !infoTargetKey.value) {
      const maxY = layout.value.reduce((acc, it) => Math.max(acc, it.y + it.h), 0)
      const i = nextKey()
      layout.value.push({ i, x: 0, y: Math.max(0, maxY), w: 8, h: 6, minW: 2, minH: 2 })
      metaMap[i] = {
        metricId: null,
        title: payload.title || '信息图',
        chartStyle: null,
        style: null,
        kind: 'infographic',
        infographic: payload.spec
      }
    } else {
      const m = metaMap[infoTargetKey.value]
      if (!m) return
      m.kind = 'infographic'
      m.metricId = null
      m.chartStyle = null
      m.infographic = payload.spec
      if (payload.title) m.title = payload.title
    }
    infoVisible.value = false
    infoCreating.value = false
  }

  /* ---------------- 图片 / 图标组件 ---------------- */

  const imageVisible = ref(false)
  const imageTargetKey = ref('')
  const imageCreating = ref(false)

  /** 打开「添加图片/图标」对话框（新建模式）。 */
  function openImagePicker(): void {
    imageCreating.value = true
    imageTargetKey.value = ''
    imageVisible.value = true
  }

  /** 打开某组件的图片编辑器（chart/infographic 切到图片也会走这里）。 */
  function openImageEditor(iKey: string): void {
    const m = metaMap[iKey]
    if (!m) return
    if (m.kind !== 'image') {
      // 切到图片：丢弃指标绑定/图表标注/信息图，保留视觉样式。
      m.kind = 'image'
      m.metricId = null
      m.chartStyle = null
      m.infographic = null
      if (!m.image) m.image = { url: '' }
    }
    imageCreating.value = false
    imageTargetKey.value = iKey
    imageVisible.value = true
  }

  function onImageConfirm(spec: ImageWidgetSpec): void {
    if (imageCreating.value || !imageTargetKey.value) {
      const maxY = layout.value.reduce((acc, it) => Math.max(acc, it.y + it.h), 0)
      const i = nextKey()
      layout.value.push({ i, x: 0, y: Math.max(0, maxY), w: 4, h: 4, minW: 2, minH: 2 })
      metaMap[i] = { metricId: null, title: '', chartStyle: null, style: null, kind: 'image', image: spec }
    } else {
      const m = metaMap[imageTargetKey.value]
      if (!m) return
      m.kind = 'image'
      m.metricId = null
      m.chartStyle = null
      m.infographic = null
      m.image = spec
    }
    imageVisible.value = false
    imageCreating.value = false
  }

  /* ---------------- 列表榜单组件 ---------------- */

  const listVisible = ref(false)
  const listTargetKey = ref('')
  const listCreating = ref(false)

  /** 打开「添加列表」对话框（新建模式）。 */
  function openListPicker(): void {
    listCreating.value = true
    listTargetKey.value = ''
    listVisible.value = true
  }

  /** 打开某组件的列表编辑器（chart/infographic/image 切到列表也会走这里）。 */
  function openListEditor(iKey: string): void {
    const m = metaMap[iKey]
    if (!m) return
    if (m.kind !== 'list') {
      // 切到列表：丢弃指标绑定/图表标注/信息图/图片（列表会重新绑定一个指标取数），保留视觉样式。
      m.kind = 'list'
      m.metricId = null
      m.chartStyle = null
      m.infographic = null
      m.image = null
      if (!m.list) m.list = { primaryField: null, secondaryField: null, valueField: null, autoScroll: false, scrollSpeed: 30 }
    }
    listCreating.value = false
    listTargetKey.value = iKey
    listVisible.value = true
  }

  /**
   * 列表对话框确认：携带绑定指标 + 列配置。
   * - 新建模式：在画布底部追加组件并写入 spec（kind=list，保留视觉样式）。
   * - 编辑模式：写回目标组件（metricId 由对话框决定的绑定指标覆盖）。
   */
  function onListConfirm(payload: { metricId: number; title: string; spec: ListWidgetSpec }): void {
    if (listCreating.value || !listTargetKey.value) {
      const maxY = layout.value.reduce((acc, it) => Math.max(acc, it.y + it.h), 0)
      const i = nextKey()
      layout.value.push({ i, x: 0, y: Math.max(0, maxY), w: 6, h: 6, minW: 2, minH: 2 })
      metaMap[i] = {
        metricId: payload.metricId,
        title: payload.title || '',
        chartStyle: null,
        style: null,
        kind: 'list',
        list: payload.spec
      }
      void fetchWidget(i, payload.metricId)
    } else {
      const m = metaMap[listTargetKey.value]
      if (!m) return
      m.kind = 'list'
      m.metricId = payload.metricId
      m.chartStyle = null
      m.infographic = null
      m.image = null
      m.list = payload.spec
      if (payload.title) m.title = payload.title
      void fetchWidget(listTargetKey.value, payload.metricId)
    }
    listVisible.value = false
    listCreating.value = false
  }

  return {
    pickerVisible,
    infoVisible,
    infoTargetKey,
    infoCreating,
    infoSpec,
    infoTitle,
    openInfoPicker,
    onPickTemplate,
    openInfoEditor,
    onInfoConfirm,
    imageVisible,
    imageTargetKey,
    imageCreating,
    openImagePicker,
    openImageEditor,
    onImageConfirm,
    listVisible,
    listTargetKey,
    listCreating,
    openListPicker,
    openListEditor,
    onListConfirm
  }
}

export type EditorDialogs = ReturnType<typeof useEditorDialogs>
