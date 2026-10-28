/**
 * 驾驶舱编辑器共享类型与纯函数（T7.1 自 DashboardEditorView.vue 拆出，逻辑逐字搬移）。
 * 本模块为 views/dashboard/editor 模块私有，不对外提供公共 API。
 */
import type { LayoutItem } from 'grid-layout-plus'
import type { ChartSpec, ChartStyle, DashboardStyleConfig, InfographicSpec, ImageWidgetSpec, ListWidgetSpec, QueryResult, WidgetStyle } from '@/types'

export const ROW_HEIGHT = 62
export const MARGIN = 12
export const DEFAULT_COLS = 12
/** 与 .grid-canvas { padding:14px } 对齐：contentRect 不含 padding，初始值用 clientHeight 减两倍补正。 */
export const CANVAS_PAD = 14
/** 大屏槽位模式动态行高下限，防止超大屏 + 多行时行被压得不可读。 */
export const MIN_ROW_H = 40

export interface WidgetMeta {
  metricId: number | null
  /** 组件标题（指标别名）；空=回落指标名称。 */
  title: string | null
  /**
   * widget 级图表标注覆盖（轴名/单位/数值格式/图例），随 layout 落库。
   * 只影响本格子的呈现，不改动指标自身的 chart_spec；同一指标被多个 widget 引用时可各自标注不同。
   */
  chartStyle: ChartStyle | null
  /** widget 级视觉样式覆盖（卡片背景/边框/标题色/圆角/强调色），随 layout 落库；与指标绑定无关，换绑指标时保留。 */
  style: WidgetStyle | null
  /** 组件类型：chart=数据图表（默认，绑定指标取数）；infographic=信息图（内容驱动，不取数）；image=图片/图标（自由放置，不取数）；list=列表榜单（绑定指标取数）。缺省 chart。 */
  kind?: 'chart' | 'infographic' | 'image' | 'list' | null
  /** 信息图配置，仅 kind=infographic 时生效。 */
  infographic?: InfographicSpec | null
  /** 图片/图标配置，仅 kind=image 时生效。 */
  image?: ImageWidgetSpec | null
  /** 列表组件配置，仅 kind=list 时生效。 */
  list?: ListWidgetSpec | null
}
export interface WidgetState {
  result: QueryResult | null
  chartSpec: ChartSpec | null
  loading: boolean
}

/** 布局单元：grid-layout-plus 的 {@link LayoutItem} 扩展自由画布百分比坐标（fx/fy/fw/fh 可空，0~100）。 */
export type FreeLayoutItem = LayoutItem & { fx?: number | null; fy?: number | null; fw?: number | null; fh?: number | null }

/** 编辑器表单态样式：全部用字符串承载（空串=未设），提交/预览时再转成带类型的 styleConfig。 */
export interface StyleFormState {
  headerBg: string
  cardBg: string
  cardBorder: string
  accent: string
  textPrimary: string
  textSecondary: string
  textMuted: string
  fontFamily: string
  fontScale: string
  cardRadius: string
  /** 是否显示指标卡片外边框（false=隐藏）。 */
  showCardBorder: boolean
  /** 自由画布模式开关（V4.44）：true=百分比绝对定位画布，弃用 12 列整数网格。 */
  free: boolean
  /** 顶栏 Logo 图片地址（空串=未设，跟随平台默认）。 */
  logo: string
}

/** 编辑器顶部表单态（与原视图 reactive form 同构）。 */
export interface EditorForm {
  name: string
  description: string
  theme: string
  cols: number
  refreshSeconds: number
  isScreen: number
  background: string
  styleConfig: StyleFormState
}

/** 空样式表单（全部未设 = 跟随主题）。 */
export function emptyStyle(): StyleFormState {
  return {
    headerBg: '',
    cardBg: '',
    cardBorder: '',
    accent: '',
    textPrimary: '',
    textSecondary: '',
    textMuted: '',
    fontFamily: '',
    fontScale: '',
    cardRadius: '',
    showCardBorder: true,
    free: false,
    logo: ''
  }
}

/** 后端 styleConfig（可能只含部分字段的 JSON 对象）→ 表单态（缺失字段置空串）。 */
export function normalizeStyle(raw?: DashboardStyleConfig | null): StyleFormState {
  const b = emptyStyle()
  if (!raw || typeof raw !== 'object') return b
  b.headerBg = raw.headerBg ?? ''
  b.cardBg = raw.cardBg ?? ''
  b.cardBorder = raw.cardBorder ?? ''
  b.accent = raw.accent ?? ''
  b.textPrimary = raw.textPrimary ?? ''
  b.textSecondary = raw.textSecondary ?? ''
  b.textMuted = raw.textMuted ?? ''
  b.fontFamily = raw.fontFamily ?? ''
  b.fontScale = typeof raw.fontScale === 'number' ? String(raw.fontScale) : ''
  b.cardRadius = typeof raw.cardRadius === 'number' ? String(raw.cardRadius) : ''
  // 显示边框：仅显式 false 时视为关闭，缺省/其它值为显示。
  b.showCardBorder = raw.showCardBorder !== false
  b.free = !!raw.free
  b.logo = raw.logo ?? ''
  return b
}

/** 表单态 → 提交/预览用 styleConfig：剔除空/默认值；全空返回 null（=完全跟随主题）。 */
export function prunedStyle(s: StyleFormState): DashboardStyleConfig | null {
  const out: DashboardStyleConfig = {}
  const t = (v: string): string | null => (v && v.trim() ? v.trim() : null)
  const headerBg = t(s.headerBg)
  if (headerBg) out.headerBg = headerBg
  const cardBg = t(s.cardBg)
  if (cardBg) out.cardBg = cardBg
  const cardBorder = t(s.cardBorder)
  if (cardBorder) out.cardBorder = cardBorder
  const accent = t(s.accent)
  if (accent) out.accent = accent
  const textPrimary = t(s.textPrimary)
  if (textPrimary) out.textPrimary = textPrimary
  const textSecondary = t(s.textSecondary)
  if (textSecondary) out.textSecondary = textSecondary
  const textMuted = t(s.textMuted)
  if (textMuted) out.textMuted = textMuted
  const fontFamily = t(s.fontFamily)
  if (fontFamily) out.fontFamily = fontFamily
  const fs = Number(s.fontScale)
  if (s.fontScale !== '' && !Number.isNaN(fs) && fs !== 1) out.fontScale = fs
  const cr = Number(s.cardRadius)
  if (s.cardRadius !== '' && !Number.isNaN(cr)) out.cardRadius = cr
  // 显示边框：只在关闭时落库（打开=缺省，保持 JSON 精简）
  if (!s.showCardBorder) out.showCardBorder = false
  if (s.free) out.free = true
  const logo = t(s.logo)
  if (logo) out.logo = logo
  return Object.keys(out).length ? out : null
}

export const round1 = (v: number): number => Math.round(v * 10) / 10
export const clampInt = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, Math.round(v)))
export const clampPct = (v: number, lo = 0, hi = 100): number => Math.min(hi, Math.max(lo, v))

/* ---------------- 大屏背景（范围 A+B） ---------------- */
export const BG_PRESETS = [
  { label: '跟随主题', value: '' },
  { label: '纯黑', value: '#020c26' },
  { label: '纯白', value: '#f5f7fc' },
  { label: '深空蓝', value: 'radial-gradient(1200px 700px at 50% -10%, #0b2a7a 0%, #04123a 55%, #020c26 100%)' },
  { label: '暮光紫', value: 'radial-gradient(1200px 700px at 50% -10%, #3a1a6e 0%, #22103f 55%, #120722 100%)' },
  { label: '科技青', value: 'radial-gradient(1200px 700px at 50% -10%, #06364a 0%, #04222f 55%, #02141c 100%)' },
  { label: '翡翠绿', value: 'radial-gradient(1200px 700px at 50% -10%, #064635 0%, #03301f 55%, #01180f 100%)' }
]

/** 图表类型 → 网格尺寸，与后端 packGrid 一致（PIE 4×4 / TABLE 6×3 / 其余 6×4）。 */
export function sizeFor(spec?: ChartSpec | null): { w: number; h: number } {
  switch (spec?.type) {
    case 'PIE':
      return { w: 4, h: 4 }
    case 'TABLE':
      return { w: 6, h: 3 }
    default:
      return { w: 6, h: 4 }
  }
}
