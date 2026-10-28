import { computed, ref } from 'vue'

/**
 * 应用界面（UI）明暗主题：账号级偏好 + 跟随系统。
 *
 * <p><b>与大屏主题是两套东西</b>：{@code theme/dashboardTheme.ts} 管的是<b>大屏/驾驶舱画布</b>的
 * 配色（8 套预设，存在 {@code dashboard.theme}）；本模块管的是<b>产品外壳</b>（侧栏/顶栏/卡片/表格/
 * 弹窗）的明暗，存在 {@code app_user.ui_theme}，随账号走。两者互不干扰：深色外壳下照样可以放一套
 * 浅色大屏，反之亦然。
 *
 * <p><b>三态</b>：{@code light} / {@code dark} / {@code system}（默认）。{@code system} 实时跟随
 * 操作系统的 {@code prefers-color-scheme}，用户在系统里切深/浅时本应用立即跟随，无需刷新。
 *
 * <p><b>落盘两级</b>：localStorage 只是<b>首屏镜像</b>（见 index.html 内联脚本，用于消除深浅闪烁），
 * 真正的权威值在后端 {@code app_user.ui_theme}；由 {@code stores/user.setUiTheme()} 写后端并同步镜像。
 * 未登录 / 后端不可达时以镜像为准，表现退化为"本机记住"，不会报错。
 *
 * <p><b>作用域</b>：明暗只作用于产品外壳（{@code /app}、{@code /admin}）。官网落地页、登录注册、
 * 公开分享页 / 公开大屏 / 嵌入式问数（{@code /embed/chat}）是对外展示面，配色由各自页面（或大屏主题）
 * 决定，不受账号主题影响——否则嵌在客户站点 iframe 里的窗口会随账号设置变黑，属于意外行为。
 */
export type UiThemeMode = 'light' | 'dark' | 'system'
export type ResolvedUiTheme = 'light' | 'dark'

/** localStorage 键（首屏镜像，与 index.html 内联脚本必须保持一致）。 */
export const UI_THEME_STORAGE_KEY = 'aibi.ui-theme'

const LEGACY_MODES: UiThemeMode[] = ['light', 'dark', 'system']

function isMode(v: unknown): v is UiThemeMode {
  return typeof v === 'string' && (LEGACY_MODES as string[]).includes(v)
}

function readMirror(): UiThemeMode {
  try {
    const v = localStorage.getItem(UI_THEME_STORAGE_KEY)
    return isMode(v) ? v : 'system'
  } catch {
    return 'system'
  }
}

function writeMirror(m: UiThemeMode): void {
  try {
    localStorage.setItem(UI_THEME_STORAGE_KEY, m)
  } catch {
    // 隐私模式下 localStorage 不可写：仅失去"首屏镜像"，主题仍可切换（本次会话内有效）
  }
}

function mediaQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null
}

/** 账号偏好（light/dark/system）。 */
const mode = ref<UiThemeMode>(readMirror())
/** 系统深浅色实时值（仅 system 模式参与计算）。 */
const systemDark = ref<boolean>(mediaQuery()?.matches ?? false)
/** 当前路由是否处于外壳内；对外展示面（登录/官网/公开页/嵌入）恒为浅色。 */
let inShell = true

/** 实际生效的明暗（system 已折算）。导出供 stores/user 派生 `isDarkTheme`。 */
export const resolved = computed<ResolvedUiTheme>(() => {
  if (!inShell) return 'light'
  if (mode.value === 'system') return systemDark.value ? 'dark' : 'light'
  return mode.value
})

/** 是否处于深色（供模板/组件做一次性判断；持续响应请用 {@link resolved}）。 */
const isDark = computed<boolean>(() => resolved.value === 'dark')

let mql: MediaQueryList | null = null
let onSystemChange: ((e: MediaQueryListEvent) => void) | null = null

function apply(): void {
  const el = document.documentElement
  const dark = resolved.value === 'dark'
  // Element Plus 深色模式以 html.dark 为选择器（见 element-plus/theme-chalk/dark/css-vars.css），
  // 与本模块共用同一个 class，无需两套开关
  el.classList.toggle('dark', dark)
  // 让浏览器原生控件（滚动条、表单、下拉、日期面板）一并跟随，否则深色页面里会出现亮白原生件
  el.style.colorScheme = dark ? 'dark' : 'light'
}

/**
 * 初始化：绑定系统深浅监听并首次应用。应在应用挂载前调用一次（main.ts）。
 *
 * <p>只绑一次：重复调用（HMR / 多次挂载）会叠加 {@code addEventListener}，导致一次系统切换触发
 * 多遍重算；这里判空后直接复用。
 */
export function initAppTheme(): void {
  mql = mediaQuery()
  if (mql && !onSystemChange) {
    onSystemChange = (e: MediaQueryListEvent) => {
      systemDark.value = e.matches
      apply()
    }
    mql.addEventListener('change', onSystemChange)
  }
  systemDark.value = mql?.matches ?? false
  apply()
}

/**
 * 切换作用域：进入/离开产品外壳。对外展示面强制浅色。
 *
 * <p>由路由守卫按 {@code meta} 驱动（见 router 的 {@code standalone} 标记），而不是在每个页面里
 * 手动开关——漏一个页面就会出现"公开页变黑"这类只在特定入口才复现的问题。
 */
export function setUiThemeScope(insideShell: boolean): void {
  if (inShell === insideShell) return
  inShell = insideShell
  apply()
}

/** 设置账号偏好（同时刷新首屏镜像）。后端持久化由 {@code stores/user.setUiTheme} 负责。 */
export function setUiThemeMode(m: UiThemeMode): void {
  const next = isMode(m) ? m : 'system'
  mode.value = next
  writeMirror(next)
  apply()
}

/** 以权威值（后端 {@code app_user.ui_theme}）校正本地：登录后、切组织后调用。 */
export function syncUiThemeMode(m: unknown): void {
  const next = isMode(m) ? m : 'system'
  // 只在真不一致时才写：避免每次 loadMe 都触发一次 localStorage 写与 class 重算
  if (mode.value === next) return
  mode.value = next
  writeMirror(next)
  apply()
}

export function useAppTheme() {
  return { mode, resolved, isDark, setMode: setUiThemeMode, syncMode: syncUiThemeMode }
}

/** 主题下拉/分段控件的候选项（顺序即展示顺序）。 */
export const UI_THEME_OPTIONS: { value: UiThemeMode; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' }
]
