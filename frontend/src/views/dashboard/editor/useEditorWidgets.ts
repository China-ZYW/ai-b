/**
 * 组件（widget）操作协作 composable（T7.1 自 DashboardEditorView.vue 拆出，逻辑逐字搬移）：
 * 指标拖入/点击加入、widget 命令菜单（绑定/换绑/类型转换/删除）、widget 级标注与视觉样式回写。
 */
import { ElMessageBox } from 'element-plus'
import type { ChartStyle, MetricVO, WidgetStyle } from '@/types'
import { DEFAULT_COLS, MARGIN, clampInt, clampPct, round1, sizeFor } from './editorTypes'
import type { FreeLayoutItem } from './editorTypes'
import type { EditorCore } from './useEditorCore'
import type { EditorLayout } from './useEditorLayout'

export function useEditorWidgets(core: EditorCore, lay: EditorLayout) {
  const { form, layout, metaMap, widgetData, metrics, dragMetric, nextKey, fetchWidget, metaOf } = core
  const { freeCanvas, editorRowHeight } = lay

  /* ---------------- 加入指标 ---------------- */
  function placeMetric(m: MetricVO, x: number, y: number): void {
    const { w, h } = sizeFor(m.chartSpec)
    const cols = form.cols || DEFAULT_COLS
    const cx = Math.min(Math.max(0, x), Math.max(0, cols - w))
    const i = nextKey()
    const item: FreeLayoutItem = { i, x: cx, y: Math.max(0, y), w, h, minW: 2, minH: 2 }
    if (freeCanvas.value) {
      // P1 修复：百分比高度相对实际 maxRow（含本组件），不能用固定 20 行
      const maxRow = layout.value.reduce((m, it) => Math.max(m, it.y + it.h), 0) || 1
      const newMaxRow = Math.max(maxRow, y + h)
      item.fx = round1((cx / cols) * 100)
      item.fw = round1((w / cols) * 100)
      item.fy = round1((Math.max(0, y) / newMaxRow) * 100)
      item.fh = round1((h / newMaxRow) * 100)
    }
    layout.value.push(item)
    metaMap[i] = { metricId: m.id, title: (m.alias || m.name), chartStyle: null, style: null }
    void fetchWidget(i, m.id)
  }
  function addMetric(m: MetricVO): void {
    const maxY = layout.value.reduce((acc, it) => Math.max(acc, it.y + it.h), 0)
    placeMetric(m, 0, maxY)
  }
  function onDragStart(m: MetricVO, e: DragEvent): void {
    dragMetric.value = m
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'copy'
      e.dataTransfer.setData('text/plain', String(m.id))
    }
  }
  function onDrop(e: DragEvent): void {
    const m = dragMetric.value
    if (!m) return
    const rect = core.gridWrap.value?.getBoundingClientRect()
    const { w, h } = sizeFor(m.chartSpec)
    const cols = form.cols || DEFAULT_COLS
    const i = nextKey()
    if (freeCanvas.value) {
      // 自由画布：落点用鼠标相对容器的百分比坐标（fx/fy），尺寸按网格盒近似成百分比
      const fx = rect ? clampPct(((e.clientX - rect.left) / rect.width) * 100) : 0
      const fy = rect ? clampPct(((e.clientY - rect.top) / rect.height) * 100) : 0
      // P1 修复：同步回写的网格 y/h 与 fh 用实际 maxRow 换算（非固定 20 行）
      const maxRow = layout.value.reduce((m, it) => Math.max(m, it.y + it.h), 0) || 1
      layout.value.push({
        i,
        x: clampInt((fx / 100) * cols, 0, cols - 2),
        y: clampInt((fy / 100) * maxRow, 0, 9999),
        w,
        h,
        minW: 2,
        minH: 2,
        fx,
        fy,
        fw: round1((w / cols) * 100),
        fh: round1((h / maxRow) * 100)
      })
    } else {
      let x = 0
      let y = 0
      if (rect) {
        const colWidth = rect.width / cols
        x = Math.floor((e.clientX - rect.left) / colWidth)
        y = Math.floor((e.clientY - rect.top) / (editorRowHeight.value + MARGIN))
      }
      placeMetric(m, x, y)
      dragMetric.value = null
      return
    }
    metaMap[i] = { metricId: m.id, title: m.alias || m.name, chartStyle: null, style: null }
    void fetchWidget(i, m.id)
    dragMetric.value = null
  }

  /* ---------------- widget 操作 ---------------- */
  function onWidgetCommand(iKey: string, cmd: string): void {
    if (cmd.startsWith('bind:')) {
      const mid = Number(cmd.slice(5))
      const m = metrics.value.find((mm) => mm.id === mid)
      if (!m) return
      // 换绑指标时丢弃旧标注（轴名/单位是按上一个指标的度量写的，沿用会标错口径）；
      // 但视觉样式与指标无关（卡片/标题/圆角），保留。
      metaMap[iKey] = { metricId: m.id, title: (m.alias || m.name), chartStyle: null, style: metaMap[iKey]?.style ?? null }
      void fetchWidget(iKey, m.id)
    } else if (cmd === 'unbind') {
      if (metaMap[iKey]) {
        metaMap[iKey].metricId = null
        metaMap[iKey].chartStyle = null
      }
      widgetData[iKey] = { result: null, chartSpec: null, loading: false }
    } else if (cmd === 'editInfo') {
      openInfoEditorRef(iKey)
    } else if (cmd === 'toInfo') {
      openInfoEditorRef(iKey)
    } else if (cmd === 'toChart') {
      const m = metaMap[iKey]
      if (!m) return
      // 转回数据图表：丢弃信息图/图片/列表，留空待绑定指标（保留视觉样式）。
      m.kind = 'chart'
      m.infographic = null
      m.image = null
      m.list = null
      m.metricId = null
    } else if (cmd === 'toList') {
      openListEditorRef(iKey)
    } else if (cmd === 'editImage') {
      openImageEditorRef(iKey)
    } else if (cmd === 'editList') {
      openListEditorRef(iKey)
    } else if (cmd === 'remove') {
      layout.value = layout.value.filter((it) => String(it.i) !== iKey)
      delete metaMap[iKey]
      delete widgetData[iKey]
    }
  }

  // 弹窗协作类（useEditorDialogs）在编排器里回填（见 useEditor.ts），避免 core↔dialogs 循环依赖。
  let openInfoEditorRef = (_iKey: string): void => undefined
  let openImageEditorRef = (_iKey: string): void => undefined
  let openListEditorRef = (_iKey: string): void => undefined
  function bindDialogOpeners(o: {
    openInfoEditor: (iKey: string) => void
    openImageEditor: (iKey: string) => void
    openListEditor: (iKey: string) => void
  }): void {
    openInfoEditorRef = o.openInfoEditor
    openImageEditorRef = o.openImageEditor
    openListEditorRef = o.openListEditor
  }

  /**
   * 直接删除组件（工具条上从「⋮」菜单提出来的独立按钮）：二次确认后移除本格 widget。
   * 删除只改本地 layout/meta，需再点「保存」才落库；未保存前刷新页面可丢弃。
   */
  function removeWidget(iKey: string): void {
    const meta = metaMap[iKey]
    const name = meta?.title || (meta?.kind === 'infographic' ? '信息图组件' : '数据图表组件')
    ElMessageBox.confirm(`确定删除「${name}」？删除后需点击「保存」才会落库。`, '删除组件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
      .then(() => onWidgetCommand(iKey, 'remove'))
      .catch(() => {
        // 用户取消：不动作
      })
  }

  function refreshWidget(iKey: string): void {
    void fetchWidget(iKey, metaOf(iKey).metricId)
  }

  /**
   * widget 级图表标注覆盖（null 表示清除，回落指标自身的 chart_spec 与租户默认）。
   * 只存本地 layout，点“保存”后随 {@code dashboard.layout} JSON 落库。
   */
  function onWidgetStyle(iKey: string, style: ChartStyle | null): void {
    if (!metaMap[iKey]) return
    metaMap[iKey].chartStyle = style
  }

  /**
   * widget 级视觉样式覆盖（null 表示清除，回落大屏主题）。只存本地 layout，点“保存”后随 dashboard.layout 落库。
   */
  function onWidgetVisual(iKey: string, style: WidgetStyle | null): void {
    if (!metaMap[iKey]) return
    metaMap[iKey].style = style
  }

  /**
   * widget 级组件标题（指标别名）：空=回落指标名称。只存本地 layout，保存时随 dashboard.layout 落库。
   */
  function onWidgetTitle(iKey: string, title: string): void {
    if (!metaMap[iKey]) return
    const t = title.trim()
    metaMap[iKey].title = t || null
  }

  return {
    placeMetric,
    addMetric,
    onDragStart,
    onDrop,
    onWidgetCommand,
    removeWidget,
    refreshWidget,
    onWidgetStyle,
    onWidgetVisual,
    onWidgetTitle,
    bindDialogOpeners
  }
}

export type EditorWidgets = ReturnType<typeof useEditorWidgets>
