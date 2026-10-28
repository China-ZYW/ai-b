/**
 * 编辑器数据加载与保存协作 composable（T7.1 自 DashboardEditorView.vue 拆出，逻辑逐字搬移）：
 * load 拉取驾驶舱与指标并重建 layout/metaMap；save 以 metaMap 为准全量回写（防静默清空）。
 */
import { type Router } from 'vue-router'
import { ElMessage } from 'element-plus'
import { dashboardApi } from '@/api/dashboard'
import { metricApi } from '@/api/metric'
import type { WidgetLayout } from '@/types'
import { DEFAULT_COLS, normalizeStyle, prunedStyle, round1 } from './editorTypes'
import type { EditorCore } from './useEditorCore'
import type { EditorLayout } from './useEditorLayout'

export function useEditorPersistence(core: EditorCore, lay: EditorLayout, id: number, router: Router) {
  const { form, layout, metaMap, metrics, loading, bgInput, runSave, fetchWidget } = core
  const { freeCanvas } = lay

  async function load(): Promise<void> {
    if (!id || Number.isNaN(id)) {
      ElMessage.error('驾驶舱 ID 无效')
      void router.replace('/dashboards')
      return
    }
    loading.value = true
    try {
      const [d, ms] = await Promise.all([dashboardApi.get(id), metricApi.list()])
      metrics.value = ms
      form.name = d.name
      form.description = d.description || ''
      form.theme = d.theme || 'dark'
      form.cols = d.cols || DEFAULT_COLS
      form.refreshSeconds = d.refreshSeconds || 30
      form.isScreen = d.isScreen || 0
      form.background = d.background || ''
      bgInput.value = form.background
      form.styleConfig = normalizeStyle(d.styleConfig)
      const ws = d.layout || []
      const cols = form.cols || DEFAULT_COLS
      // P1 修复：老数据兜底换算必须用实际 maxRow（=网格总行高），不能用固定 20 行（*5）
      const maxRow = ws.reduce((m, w) => Math.max(m, (w.y || 0) + (w.h || 0)), 0) || 1
      layout.value = ws.map((w) => {
        // 自由画布：优先用已存的百分比坐标；老数据（自由画布但无 fx/fy/fw/fh）按网格盒兜底换算
        const fx = w.fx ?? null
        const fy = w.fy ?? null
        const fw = w.fw ?? null
        const fh = w.fh ?? null
        const isFree = !!form.styleConfig.free
        return {
          i: w.i,
          x: w.x,
          y: w.y,
          w: w.w,
          h: w.h,
          minW: 2,
          minH: 2,
          fx: isFree && fx == null ? round1((w.x / cols) * 100) : fx,
          fy: isFree && fy == null ? round1((w.y / maxRow) * 100) : fy,
          fw: isFree && fw == null ? round1((w.w / cols) * 100) : fw,
          fh: isFree && fh == null ? round1((w.h / maxRow) * 100) : fh
        }
      })
      for (const w of ws) {
        metaMap[w.i] = {
          metricId: w.metricId ?? null,
          title: w.title || '',
          chartStyle: w.chartStyle ?? null,
          style: w.style ?? null,
          // ★kind / infographic 必须一并回填。save() 是「以 metaMap 为准」的全量回写：
          //   漏掉这两个字段不只是"刷新后信息图不显示"，更会在下一次保存时把库里已有的
          //   kind/infographic 静默清空（全量覆盖的经典陷阱，与 MetricService.update 同源）。
          kind: w.kind ?? null,
          infographic: w.infographic ?? null,
          image: w.image ?? null,
          list: w.list ?? null
        }
      }
      // 逐块拉取实时数据
      for (const w of ws) {
        if (w.metricId != null) void fetchWidget(w.i, w.metricId)
      }
    } catch {
      // 拦截器已提示
    } finally {
      loading.value = false
    }
  }

  /* ---------------- 保存 / 预览 ---------------- */
  async function save(): Promise<void> {
    if (!form.name.trim()) {
      ElMessage.warning('请输入驾驶舱名称')
      return
    }
    const merged: WidgetLayout[] = layout.value.map((it) => {
      const k = String(it.i)
      const meta = metaMap[k]
      return {
        i: k,
        metricId: meta?.metricId ?? null,
        x: it.x,
        y: it.y,
        w: it.w,
        h: it.h,
        // 自由画布坐标：仅自由模式落库百分比坐标，否则写 null（渲染落点走 x/y/w/h 整数网格）
        fx: freeCanvas.value ? (it.fx ?? null) : null,
        fy: freeCanvas.value ? (it.fy ?? null) : null,
        fw: freeCanvas.value ? (it.fw ?? null) : null,
        fh: freeCanvas.value ? (it.fh ?? null) : null,
        title: meta?.title ?? null,
        chartStyle: meta?.chartStyle ?? null,
        style: meta?.style ?? null,
        kind: meta?.kind ?? null,
        infographic: meta?.infographic ?? null,
        image: meta?.image ?? null,
        list: meta?.list ?? null
      }
    })
    await runSave(async () => {
      try {
        await dashboardApi.update(id, {
          name: form.name.trim(),
          description: form.description.trim() || null,
          theme: form.theme,
          cols: form.cols,
          refreshSeconds: form.refreshSeconds,
          isScreen: form.isScreen,
          background: form.background.trim() || null,
          styleConfig: prunedStyle(form.styleConfig),
          layout: merged
        })
        ElMessage.success('布局已保存')
      } catch {
        // 拦截器已提示
      }
    })
  }

  function openScreen(): void {
    const url = router.resolve(`/screen/${id}`).href
    window.open(url, '_blank')
  }
  function goBack(): void {
    void router.push('/dashboards')
  }

  return { load, save, openScreen, goBack }
}

export type EditorPersistence = ReturnType<typeof useEditorPersistence>
