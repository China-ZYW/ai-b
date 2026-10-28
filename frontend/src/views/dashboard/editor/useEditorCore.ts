/**
 * 编辑器共享状态基座（T7.1 自 DashboardEditorView.vue 拆出，逻辑逐字搬移）：
 * 表单/布局/widget 数据等所有协作 composable 公用的 ref/reactive，
 * 以及主题样式派生（th/canvasStyle/颜色双向绑定）与大屏背景、Logo 操作。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi } from '@/api/dashboard'
import { metricApi } from '@/api/metric'
import { useActionGuard } from '@/utils/actionGuard'
import { FONT_OPTIONS, SCALE_OPTIONS, mergedTheme, themeOptions, themeVersion } from '@/theme/dashboardTheme'
import type { DashboardStyleConfig, MetricVO } from '@/types'
import {
  BG_PRESETS,
  CANVAS_PAD,
  DEFAULT_COLS,
  emptyStyle,
  normalizeStyle,
  prunedStyle,
  sizeFor
} from './editorTypes'
import type { FreeLayoutItem, EditorForm, WidgetMeta, WidgetState } from './editorTypes'

export function useEditorCore() {
  const loading = ref(false)
  const { busy: saving, run: runSave } = useActionGuard()
  const layout = ref<FreeLayoutItem[]>([])
  const metaMap = reactive<Record<string, WidgetMeta>>({})
  const widgetData = reactive<Record<string, WidgetState>>({})
  const metrics = ref<MetricVO[]>([])
  const metricKeyword = ref('')
  const gridWrap = ref<HTMLElement | null>(null)
  /** 画布内容区高度（不含 .grid-canvas 的 14px padding），由 ResizeObserver 实时更新，用于大屏槽位模式动态行高。 */
  const gridWrapH = ref(0)
  let wrapObserver: ResizeObserver | null = null
  const dragMetric = ref<MetricVO | null>(null)
  /** 主题选择器选项（内置 + 远端自定义）；fetchThemes 完成后刷新。 */
  const themeOpts = ref(themeOptions())
  let seq = 0
  /** 组件 key 生成器：w{时间戳}{序号}，同毫秒内以 seq 去重。 */
  function nextKey(): string {
    return `w${Date.now()}${seq++}`
  }

  const form = reactive<EditorForm>({
    name: '',
    description: '',
    theme: 'dark',
    cols: DEFAULT_COLS,
    refreshSeconds: 30,
    isScreen: 0,
    background: '',
    styleConfig: emptyStyle()
  })

  /** 表单样式 → 带类型的 styleConfig（供主题合并预览）。 */
  const previewStyle = computed<DashboardStyleConfig | null>(() => prunedStyle(form.styleConfig))

  /** 当前主题令牌：所选主题 + 自定义背景 + 自定义样式覆盖（驱动画布预览与 widget 渲染）。 */
  const th = computed(() => {
    void themeVersion.value // 响应式依赖：远端主题 hydration 完成后自动重解析
    return mergedTheme(form.theme, form.background, previewStyle.value)
  })
  const canvasStyle = computed(() => {
    const s: Record<string, string> = {
      background: th.value.screenBg,
      '--sc-scale': String(th.value.fontScale ?? 1)
    }
    if (th.value.fontFamily) s.fontFamily = th.value.fontFamily
    return s
  })

  /** 颜色选择器双向绑定：表单底层存空串（未设），对外暴露 string|null（el-color-picker 约定）。 */
  const colorCardBorder = computed<string | null>({
    get: () => form.styleConfig.cardBorder || null,
    set: (v) => {
      form.styleConfig.cardBorder = v ?? ''
    }
  })
  const colorAccent = computed<string | null>({
    get: () => form.styleConfig.accent || null,
    set: (v) => {
      form.styleConfig.accent = v ?? ''
    }
  })
  const colorTextPrimary = computed<string | null>({
    get: () => form.styleConfig.textPrimary || null,
    set: (v) => {
      form.styleConfig.textPrimary = v ?? ''
    }
  })
  const colorTextSecondary = computed<string | null>({
    get: () => form.styleConfig.textSecondary || null,
    set: (v) => {
      form.styleConfig.textSecondary = v ?? ''
    }
  })
  const colorTextMuted = computed<string | null>({
    get: () => form.styleConfig.textMuted || null,
    set: (v) => {
      form.styleConfig.textMuted = v ?? ''
    }
  })

  /** 「显示指标边框」开关：与 styleConfig.showCardBorder 双向同步（缺省 true=显示）。 */
  const showCardBorder = computed<boolean>({
    get: () => form.styleConfig.showCardBorder !== false,
    set: (v: boolean) => {
      form.styleConfig.showCardBorder = v
    }
  })

  /* ---------------- 大屏背景（范围 A+B） ---------------- */
  const bgInput = ref(form.background)
  function applyBgPreset(v: string): void {
    form.background = v
    bgInput.value = v
  }
  function applyBgCustom(): void {
    form.background = bgInput.value.trim()
  }
  function beforeBgUpload(file: File): boolean {
    if (!/\.(png|jpe?g|webp|gif|svg)$/i.test(file.name)) {
      ElMessage.error('仅支持图片：png/jpg/jpeg/webp/gif/svg')
      return false
    }
    if (file.size > 5 * 1024 * 1024) {
      ElMessage.error('图片不能超过 5MB')
      return false
    }
    return true
  }
  function uploadBg(req: { file: File }): void {
    dashboardApi.uploadMaterial(req.file)
      .then((res) => {
        form.background = `url(${res.url})`
        bgInput.value = form.background
        ElMessage.success('背景图已上传')
      })
      .catch(() => {
        // 拦截器已提示
      })
  }

  /** 顶栏 Logo 双向绑定（空串=未设，写入 styleConfig.logo）。 */
  const logoUrl = computed<string>({
    get: () => form.styleConfig.logo || '',
    set: (v: string) => {
      form.styleConfig.logo = v.trim()
    }
  })
  /** 上传 Logo 图片（复用大屏素材上传接口），返回 URL 写入 styleConfig.logo。 */
  function uploadLogo(req: { file: File }): void {
    dashboardApi
      .uploadMaterial(req.file)
      .then((res) => {
        form.styleConfig.logo = res.url
        ElMessage.success('Logo 已上传')
      })
      .catch(() => {
        // 拦截器已提示
      })
  }

  const filteredMetrics = computed(() => {
    const k = metricKeyword.value.trim().toLowerCase()
    if (!k) return metrics.value
    return metrics.value.filter((m) => m.name.toLowerCase().includes(k))
  })

  function metaOf(i: number | string): WidgetMeta {
    return metaMap[String(i)] || { metricId: null, title: '', chartStyle: null, style: null, kind: null, image: null, list: null }
  }
  function dataOf(i: number | string): WidgetState {
    return widgetData[String(i)] || { result: null, chartSpec: null, loading: false }
  }

  async function fetchWidget(iKey: string, metricId: number | null): Promise<void> {
    if (metricId == null) {
      widgetData[iKey] = { result: null, chartSpec: null, loading: false }
      return
    }
    widgetData[iKey] = { ...(widgetData[iKey] || { result: null, chartSpec: null }), loading: true }
    try {
      const data = await metricApi.refresh(metricId)
      widgetData[iKey] = { result: data.result, chartSpec: data.chartSpec ?? null, loading: false }
    } catch {
      widgetData[iKey] = { result: null, chartSpec: null, loading: false }
    }
  }

  /** 挂载画布尺寸观察器：大屏槽位模式需按画布高度动态算行高（铺满视口，与预览一致）。 */
  function observeCanvas(): void {
    if (!gridWrap.value) return
    gridWrapH.value = gridWrap.value.clientHeight - CANVAS_PAD * 2
    wrapObserver = new ResizeObserver((entries) => {
      for (const e of entries) gridWrapH.value = e.contentRect.height
    })
    wrapObserver.observe(gridWrap.value)
  }
  function unobserveCanvas(): void {
    wrapObserver?.disconnect()
    wrapObserver = null
  }

  return {
    loading,
    saving,
    runSave,
    layout,
    metaMap,
    widgetData,
    metrics,
    metricKeyword,
    gridWrap,
    gridWrapH,
    dragMetric,
    themeOpts,
    nextKey,
    form,
    previewStyle,
    th,
    canvasStyle,
    colorCardBorder,
    colorAccent,
    colorTextPrimary,
    colorTextSecondary,
    colorTextMuted,
    showCardBorder,
    bgInput,
    applyBgPreset,
    applyBgCustom,
    beforeBgUpload,
    uploadBg,
    logoUrl,
    uploadLogo,
    filteredMetrics,
    metaOf,
    dataOf,
    fetchWidget,
    observeCanvas,
    unobserveCanvas,
    // 模板侧常量（TopBar 预设面板 / 视图布局使用）
    BG_PRESETS,
    FONT_OPTIONS,
    SCALE_OPTIONS,
    sizeFor,
    emptyStyle,
    normalizeStyle
  }
}

export type EditorCore = ReturnType<typeof useEditorCore>
