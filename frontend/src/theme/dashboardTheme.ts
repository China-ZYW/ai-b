/**
 * 数据大屏 / 驾驶舱主题预设（问题5 + 问题6）。
 *
 * 一套主题 = 一揽子联动的令牌：整屏背景、卡片背景/边框、文字层级、强调色，
 * 以及图表色板与坐标轴/提示框配色。切换主题时，大屏背景与其中每个 widget 的
 * 图表配色同步变化，解决「图表配色不随大屏背景 UI 一致」的问题。
 *
 * theme 字段以字符串 key 持久化在后端 dashboard.theme；旧的 'light'/'dark'
 * 仍然有效（向后兼容），未知/空值回落到 DEFAULT_THEME_KEY。
 */
import { ref } from 'vue'
import type { DashboardStyleConfig } from '@/types'

/** 主题令牌：背景 / 卡片 / 文字 / 图表配色一揽子。 */
export interface DashboardTheme {
  /** 持久化 key（写入 dashboard.theme）。 */
  key: string
  /** 展示名。 */
  label: string
  /** 是否深色系（决定 loading 遮罩、明细表反色、按钮底色等）。 */
  dark: boolean
  /** 整屏 / 画布背景（CSS background，可渐变）。 */
  screenBg: string
  /** 顶部标题栏背景。 */
  headerBg: string
  /** 卡片背景。 */
  cardBg: string
  /** 卡片边框色。 */
  cardBorder: string
  /** 主标题文字色。 */
  textPrimary: string
  /** 次要文字色（meta / 日期）。 */
  textSecondary: string
  /** 弱文字色（占位提示）。 */
  textMuted: string
  /** 强调色（LIVE 徽章、按钮高光、时钟）。 */
  accent: string
  /** 图表色板（多系列依次取用）。 */
  chartPalette: string[]
  /** 图表坐标轴线色。 */
  axisLine: string
  /** 图表坐标轴标签色。 */
  axisLabel: string
  /** 图表分隔线色。 */
  splitLine: string
  /** tooltip 背景色。 */
  tooltipBg: string
  /** tooltip 边框色。 */
  tooltipBorder: string
  /** tooltip 文字色。 */
  tooltipText: string
  /** 大屏级自定义字体族（可选覆盖，未设=继承系统字体）。 */
  fontFamily?: string
  /** 大屏级字号比例（可选覆盖，1=默认；缩放标题/时钟/卡片标题等关键文本）。 */
  fontScale?: number
  /** 大屏级卡片圆角 px（可选覆盖）。 */
  cardRadius?: number
  /** 是否显示卡片外边框（可选覆盖）：false=隐藏边框；true/缺省=显示主题边框色。 */
  showCardBorder?: boolean

  /* -------------------- L1/L2 装饰与动效令牌（可选，缺省关闭） -------------------- */

  /** 装饰风格：'tech' 时渲染科技感装饰（卡片四角标 / 霓虹发光 / 扫描光、大屏网格背景、顶栏科技条）；缺省 'none'。 */
  deco?: string
  /** 卡片边框霓虹发光（box-shadow 外发光）。 */
  glow?: boolean
  /** 卡片四角科技装饰角标。 */
  corner?: boolean
  /** 卡片顶部横向扫描光带（循环动效）。 */
  scan?: boolean
  /** 大屏容器动态网格背景（CSS 背景位移动画 + 顶部辉光）。 */
  grid?: boolean
  /** 图表与数据流动增强（折线发光、柱状错峰生长、数据流动管道动效）。 */
  flow?: boolean
}

/* ------------------------------ 颜色工具 ------------------------------ */

function clamp(n: number): number {
  return Math.max(0, Math.min(255, Math.round(n)))
}

/** #RGB / #RRGGBB → [r,g,b]。非法输入按黑色处理。 */
export function hexToRgb(hex: string): [number, number, number] {
  let h = (hex || '').trim().replace('#', '')
  if (h.length === 3) {
    h = h
      .split('')
      .map((c) => c + c)
      .join('')
  }
  const num = Number.parseInt(h, 16)
  if (Number.isNaN(num)) return [0, 0, 0]
  return [(num >> 16) & 255, (num >> 8) & 255, num & 255]
}

/** 把 hex 转为带透明度的 rgba() 字符串。 */
export function rgba(hex: string, alpha: number): string {
  const [r, g, b] = hexToRgb(hex)
  return `rgba(${r},${g},${b},${alpha})`
}

/** 把 hex 向白色靠拢 ratio（0~1）得到更亮的颜色，用于柱状渐变高光。 */
export function lighten(hex: string, ratio: number): string {
  const [r, g, b] = hexToRgb(hex)
  return `rgb(${clamp(r + (255 - r) * ratio)},${clamp(g + (255 - g) * ratio)},${clamp(b + (255 - b) * ratio)})`
}

/* ------------------------------ 预设主题 ------------------------------ */

const PRESETS: DashboardTheme[] = [
  {
    key: 'dark',
    label: '深邃蓝',
    dark: true,
    screenBg: 'radial-gradient(1200px 700px at 50% -10%, #0b2a7a 0%, #04123a 55%, #020c26 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(0,47,167,0.35), rgba(0,194,255,0.12), rgba(0,47,167,0.35))',
    cardBg: 'linear-gradient(160deg, rgba(30,90,232,0.16), rgba(0,22,79,0.42))',
    cardBorder: 'rgba(74,144,245,0.28)',
    textPrimary: '#eaf1ff',
    textSecondary: 'rgba(207,224,255,0.72)',
    textMuted: 'rgba(154,190,255,0.55)',
    accent: '#00c2ff',
    chartPalette: ['#00C2FF', '#4A90F5', '#6A5CFF', '#16C784', '#FF9F43', '#FF6B8B', '#3DDC97'],
    axisLine: 'rgba(120,170,255,0.28)',
    axisLabel: 'rgba(207,224,255,0.72)',
    splitLine: 'rgba(120,170,255,0.14)',
    tooltipBg: 'rgba(6,22,60,0.92)',
    tooltipBorder: 'rgba(90,150,240,0.40)',
    tooltipText: '#eaf1ff'
  },
  {
    key: 'light',
    label: '简洁白',
    dark: false,
    screenBg: 'linear-gradient(180deg, #f5f7fc 0%, #eef1f8 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(0,47,167,0.06), rgba(0,194,255,0.05), rgba(0,47,167,0.06))',
    cardBg: '#ffffff',
    cardBorder: '#e4e8f2',
    textPrimary: '#141a2e',
    textSecondary: '#6b7488',
    textMuted: '#9aa3b8',
    accent: '#002FA7',
    chartPalette: ['#002FA7', '#1E5AE8', '#4A90F5', '#00C2FF', '#6A5CFF', '#16C784', '#FF9F43'],
    axisLine: '#E4E8F2',
    axisLabel: '#6B7488',
    splitLine: '#EEF1F8',
    tooltipBg: 'rgba(255,255,255,0.96)',
    tooltipBorder: '#E4E8F2',
    tooltipText: '#141A2E'
  },
  {
    key: 'tech',
    label: '科技青',
    dark: true,
    screenBg: 'radial-gradient(1200px 700px at 50% -10%, #06364a 0%, #04222f 55%, #02141c 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(0,180,200,0.30), rgba(0,229,208,0.10), rgba(0,180,200,0.30))',
    cardBg: 'linear-gradient(160deg, rgba(0,200,220,0.14), rgba(2,32,44,0.46))',
    cardBorder: 'rgba(0,220,230,0.26)',
    textPrimary: '#e6fbff',
    textSecondary: 'rgba(190,235,245,0.72)',
    textMuted: 'rgba(150,215,230,0.55)',
    accent: '#00e5d0',
    chartPalette: ['#00E5D0', '#00B4FF', '#39D3FF', '#7CF5C4', '#FFD166', '#FF7AA2', '#4ADE80'],
    axisLine: 'rgba(0,220,230,0.26)',
    axisLabel: 'rgba(190,235,245,0.72)',
    splitLine: 'rgba(0,220,230,0.12)',
    tooltipBg: 'rgba(3,28,38,0.92)',
    tooltipBorder: 'rgba(0,210,220,0.40)',
    tooltipText: '#e6fbff'
  },
  {
    key: 'emerald',
    label: '翡翠绿',
    dark: true,
    screenBg: 'radial-gradient(1200px 700px at 50% -10%, #064635 0%, #03301f 55%, #01180f 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(16,185,129,0.30), rgba(52,211,153,0.10), rgba(16,185,129,0.30))',
    cardBg: 'linear-gradient(160deg, rgba(16,185,129,0.14), rgba(2,40,28,0.46))',
    cardBorder: 'rgba(52,211,153,0.26)',
    textPrimary: '#e7fbf2',
    textSecondary: 'rgba(190,240,220,0.72)',
    textMuted: 'rgba(150,220,190,0.55)',
    accent: '#34d399',
    chartPalette: ['#34D399', '#10B981', '#A3E635', '#22D3EE', '#FBBF24', '#F472B6', '#4ADE80'],
    axisLine: 'rgba(52,211,153,0.26)',
    axisLabel: 'rgba(190,240,220,0.72)',
    splitLine: 'rgba(52,211,153,0.12)',
    tooltipBg: 'rgba(2,32,22,0.92)',
    tooltipBorder: 'rgba(52,211,153,0.40)',
    tooltipText: '#e7fbf2'
  },
  {
    key: 'sunset',
    label: '暮光橙',
    dark: true,
    screenBg: 'radial-gradient(1200px 700px at 50% -10%, #5a1e2a 0%, #3a1020 55%, #1c0710 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(255,120,80,0.30), rgba(255,180,90,0.10), rgba(255,120,80,0.30))',
    cardBg: 'linear-gradient(160deg, rgba(255,140,90,0.14), rgba(50,16,26,0.48))',
    cardBorder: 'rgba(255,150,110,0.26)',
    textPrimary: '#fff0e9',
    textSecondary: 'rgba(255,220,205,0.72)',
    textMuted: 'rgba(255,190,170,0.55)',
    accent: '#ff9f43',
    chartPalette: ['#FF9F43', '#FF6B8B', '#FFD166', '#FF7AA2', '#FB923C', '#F472B6', '#FBBF24'],
    axisLine: 'rgba(255,150,110,0.26)',
    axisLabel: 'rgba(255,220,205,0.72)',
    splitLine: 'rgba(255,150,110,0.12)',
    tooltipBg: 'rgba(44,14,22,0.92)',
    tooltipBorder: 'rgba(255,150,110,0.40)',
    tooltipText: '#fff0e9'
  },
  {
    key: 'purple',
    label: '暗夜紫',
    dark: true,
    screenBg: 'radial-gradient(1200px 700px at 50% -10%, #3a1a6e 0%, #22103f 55%, #120722 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(140,90,255,0.32), rgba(200,120,255,0.10), rgba(140,90,255,0.32))',
    cardBg: 'linear-gradient(160deg, rgba(140,90,255,0.16), rgba(38,16,72,0.48))',
    cardBorder: 'rgba(170,130,255,0.28)',
    textPrimary: '#f1eaff',
    textSecondary: 'rgba(225,210,255,0.72)',
    textMuted: 'rgba(195,175,255,0.55)',
    accent: '#a855f7',
    chartPalette: ['#A855F7', '#6A5CFF', '#EC4899', '#22D3EE', '#F472B6', '#818CF8', '#C084FC'],
    axisLine: 'rgba(170,130,255,0.26)',
    axisLabel: 'rgba(225,210,255,0.72)',
    splitLine: 'rgba(170,130,255,0.12)',
    tooltipBg: 'rgba(28,14,52,0.92)',
    tooltipBorder: 'rgba(170,130,255,0.40)',
    tooltipText: '#f1eaff'
  },
  {
    key: 'cyber',
    label: '赛博霓虹',
    dark: true,
    screenBg:
      'radial-gradient(900px 620px at 12% 0%, rgba(0,229,255,0.20) 0%, transparent 46%), radial-gradient(860px 600px at 88% 100%, rgba(255,45,149,0.18) 0%, transparent 46%), linear-gradient(160deg, #04060f 0%, #0a1024 58%, #04060f 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(0,229,255,0.30), rgba(255,45,149,0.16), rgba(0,229,255,0.30))',
    cardBg: 'linear-gradient(160deg, rgba(0,229,255,0.10), rgba(9,14,30,0.66))',
    cardBorder: 'rgba(0,229,255,0.32)',
    textPrimary: '#eafcff',
    textSecondary: 'rgba(178,234,255,0.76)',
    textMuted: 'rgba(136,198,232,0.56)',
    accent: '#00e5ff',
    chartPalette: ['#00E5FF', '#FF2D95', '#7C5CFF', '#3DDC97', '#FFD166', '#FF7A6B', '#22D3EE'],
    axisLine: 'rgba(0,229,255,0.30)',
    axisLabel: 'rgba(178,234,255,0.76)',
    splitLine: 'rgba(0,229,255,0.14)',
    tooltipBg: 'rgba(6,12,28,0.94)',
    tooltipBorder: 'rgba(0,229,255,0.44)',
    tooltipText: '#eafcff',
    deco: 'tech',
    glow: true,
    corner: true,
    scan: true,
    grid: true,
    flow: true
  },
  {
    key: 'nebula',
    label: '星云深空',
    dark: true,
    screenBg:
      'radial-gradient(1000px 680px at 50% -8%, rgba(124,92,255,0.26) 0%, transparent 52%), radial-gradient(760px 620px at 82% 88%, rgba(0,194,255,0.16) 0%, transparent 50%), linear-gradient(170deg, #060a1c 0%, #0d1230 55%, #060a1c 100%)',
    headerBg:
      'linear-gradient(90deg, rgba(124,92,255,0.30), rgba(0,194,255,0.14), rgba(124,92,255,0.30))',
    cardBg: 'linear-gradient(160deg, rgba(124,92,255,0.10), rgba(10,16,44,0.66))',
    cardBorder: 'rgba(140,150,255,0.30)',
    textPrimary: '#eef0ff',
    textSecondary: 'rgba(198,206,255,0.76)',
    textMuted: 'rgba(150,165,225,0.56)',
    accent: '#8b9cff',
    chartPalette: ['#7C5CFF', '#00C2FF', '#C084FC', '#3DDC97', '#FBBF24', '#FF7AA2', '#4ADE80'],
    axisLine: 'rgba(150,160,255,0.28)',
    axisLabel: 'rgba(198,206,255,0.76)',
    splitLine: 'rgba(150,160,255,0.13)',
    tooltipBg: 'rgba(10,14,36,0.94)',
    tooltipBorder: 'rgba(150,160,255,0.42)',
    tooltipText: '#eef0ff',
    deco: 'tech',
    glow: true,
    corner: true,
    scan: true,
    grid: true,
    flow: true
  }
]

/** key → 主题令牌。 */
export const THEME_MAP: Record<string, DashboardTheme> = Object.fromEntries(
  PRESETS.map((t) => [t.key, t])
)

/** 缺省主题 key（大屏默认深色）。 */
export const DEFAULT_THEME_KEY = 'dark'

/** 主题选择器选项（含 3 色样本，供 UI 预览）。 */
export const THEME_OPTIONS: { key: string; label: string; dark: boolean; swatch: string[] }[] =
  PRESETS.map((t) => ({
    key: t.key,
    label: t.label,
    dark: t.dark,
    swatch: t.chartPalette.slice(0, 3)
  }))

/* --------------------- 配置化升级 V2：API 优先 + 进程内缓存 + 内置兜底 --------------------- */

/** 远端（theme_config）主题缓存：code → 令牌。由 {@link hydrateThemes} 填充。 */
const REMOTE_THEMES: Record<string, DashboardTheme> = {}

/**
 * 主题缓存版本号（响应式）。远端主题异步 hydration 完成后自增，
 * 供消费方 computed 建立响应式依赖，实现「缓存未命中先返回内置、后台刷新后自动重解析」。
 */
export const themeVersion = ref(0)

/** 已发起过 hydration 的标记（避免重复拉取）。 */
let hydrating: Promise<void> | null = null

/** 把后端 ThemeConfig（tokens JSON）转为前端 DashboardTheme 并写入缓存。 */
function toDashboardTheme(tc: { code: string; name?: string | null; dark?: number | null; tokens: string }): DashboardTheme | null {
  try {
    const tokens = JSON.parse(tc.tokens) as DashboardTheme
    return { ...tokens, key: tc.code, label: tc.name || tc.code, dark: tc.dark === 1 }
  } catch {
    return null
  }
}

/** 用一批后端主题填充缓存（供已登录/公开两条链路复用）。 */
export function hydrateThemes(
  list: { code: string; name?: string | null; dark?: number | null; tokens: string }[]
): void {
  let changed = false
  for (const tc of list || []) {
    const theme = toDashboardTheme(tc)
    if (theme) {
      REMOTE_THEMES[theme.key] = theme
      changed = true
    }
  }
  if (changed) themeVersion.value++
}

/**
 * 拉取并缓存后端主题（API 优先）。同步签名 {@link resolveTheme} 在缓存未命中时先返回内置预设，
 * 本函数在后台刷新缓存；失败静默回落内置，绝不影响渲染。
 *
 * @param usePublic 公开页（免认证）传 true，走 /api/public/theme
 */
export async function fetchThemes(usePublic = false): Promise<void> {
  if (hydrating) return hydrating
  hydrating = (async () => {
    try {
      const { themeApi, publicThemeApi } = await import('@/api/theme')
      const list = usePublic ? await publicThemeApi.list() : await themeApi.list()
      hydrateThemes(list)
    } catch {
      // 静默回落内置预设
    } finally {
      hydrating = null
    }
  })()
  return hydrating
}

/** 当前可用主题选项（内置 + 远端去重），供主题选择器展示。 */
export function themeOptions(): { key: string; label: string; dark: boolean; swatch: string[] }[] {
  const merged: Record<string, { key: string; label: string; dark: boolean; swatch: string[] }> = {}
  for (const o of THEME_OPTIONS) merged[o.key] = o
  for (const t of Object.values(REMOTE_THEMES)) {
    merged[t.key] = { key: t.key, label: t.label, dark: t.dark, swatch: t.chartPalette.slice(0, 3) }
  }
  return Object.values(merged)
}

/** 按 key 解析主题：远端缓存优先 → 内置预设 → 默认深色（兼容旧 'light'/'dark'）。 */
export function resolveTheme(key?: string | null): DashboardTheme {
  if (key && REMOTE_THEMES[key]) return REMOTE_THEMES[key]
  if (key && THEME_MAP[key]) return THEME_MAP[key]
  return REMOTE_THEMES[DEFAULT_THEME_KEY] || THEME_MAP[DEFAULT_THEME_KEY]
}

/** 大屏字体预设（编辑器「字体」下拉）：value 为完整 CSS font-family。 */
export const FONT_OPTIONS: { label: string; value: string }[] = [
  { label: '跟随系统', value: '' },
  { label: '微软雅黑', value: "'Microsoft YaHei', 'PingFang SC', sans-serif" },
  { label: '思源黑体', value: "'Source Han Sans SC', 'Noto Sans SC', sans-serif" },
  { label: '苹方 / 等线', value: "'PingFang SC', 'Hiragino Sans GB', sans-serif" },
  { label: '宋体（衬线）', value: "'Songti SC', 'SimSun', serif" },
  { label: '等宽（数字对齐）', value: "'JetBrains Mono', 'Consolas', monospace" }
]

/** 大屏字号比例档位。 */
export const SCALE_OPTIONS: { label: string; value: number }[] = [
  { label: '小（0.9×）', value: 0.9 },
  { label: '标准（1.0×）', value: 1 },
  { label: '大（1.15×）', value: 1.15 },
  { label: '特大（1.3×）', value: 1.3 }
]

/** 取非空字符串（去首尾空白），否则 null。 */
function nonBlank(v?: string | null): string | null {
  return v && v.trim() ? v.trim() : null
}

/**
 * 合并大屏级覆盖：在解析出的主题令牌基础上，依次叠加
 * ① 整屏背景 {@code background}（覆盖 {@code screenBg}）；
 * ② 自定义样式 {@code styleConfig}（覆盖顶栏/卡片/边框/强调色/文字层级 + 字体族/字号比例/卡片圆角）。
 *
 * <p>两者均为空 → 原样返回主题（完全跟随主题）。返回新对象，绝不修改原预设/远端主题引用，避免串主题。
 */
export function mergedTheme(
  theme?: string | null,
  background?: string | null,
  style?: DashboardStyleConfig | null
): DashboardTheme {
  const base = resolveTheme(theme)
  const bg = nonBlank(background)
  if (!bg && !style) return base
  const out: DashboardTheme = { ...base }
  if (bg) out.screenBg = bg
  if (style) {
    const s = style
    const headerBg = nonBlank(s.headerBg)
    if (headerBg) out.headerBg = headerBg
    const cardBg = nonBlank(s.cardBg)
    if (cardBg) out.cardBg = cardBg
    const cardBorder = nonBlank(s.cardBorder)
    if (cardBorder) out.cardBorder = cardBorder
    const accent = nonBlank(s.accent)
    if (accent) out.accent = accent
    const textPrimary = nonBlank(s.textPrimary)
    if (textPrimary) out.textPrimary = textPrimary
    const textSecondary = nonBlank(s.textSecondary)
    if (textSecondary) out.textSecondary = textSecondary
    const textMuted = nonBlank(s.textMuted)
    if (textMuted) out.textMuted = textMuted
    const fontFamily = nonBlank(s.fontFamily)
    if (fontFamily) out.fontFamily = fontFamily
    if (typeof s.fontScale === 'number' && s.fontScale > 0) out.fontScale = s.fontScale
    if (typeof s.cardRadius === 'number' && s.cardRadius >= 0) out.cardRadius = s.cardRadius
    // 显示卡片边框：仅在显式 false 时隐藏（缺省 true 跟随主题边框色）。
    if (s.showCardBorder === false) out.showCardBorder = false
  }
  return out
}
