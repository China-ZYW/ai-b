import type { ChartLegendPosition, ChartStyle, ChartValueFormat } from '@/types'

/**
 * 图表展示样式的解析与数值格式化。
 *
 * <p>优先级（逐字段合并，不是整体替换）：
 * widget/会话级覆盖 &gt; chart_spec 自带 style（用户设置或后端自动推断）&gt; 租户级默认（sys_config CHART）&gt; 代码默认。
 * 逐字段合并是为了让"只改 Y 轴名"的覆盖仍能继承后端推断出的单位。
 *
 * <p>代码默认刻意与引入本模块前的硬编码行为逐字一致（COMPACT 紧凑数值 + bottom 图例 + 自动小数位），
 * 使既有图表在没有任何配置时不发生视觉变化。
 */

/** 解析后的样式：字段全部就位，消费方无需再做空值判断。 */
export interface ResolvedChartStyle {
  /** X 轴名（null=回落维度列展示名）。 */
  xAxisName: string | null
  /** Y 轴名（null=回落首个度量列展示名）。 */
  yAxisName: string | null
  /** 度量单位（''=无单位）。 */
  valueUnit: string
  valueFormat: ChartValueFormat
  /** 小数位（null=自动：整数原样、小数至多 2 位）。 */
  valueDecimals: number | null
  legendPosition: ChartLegendPosition
  /** X 轴标签旋转角度（null=按类别数自适应）。 */
  xLabelRotate: number | null
}

const FORMATS: ChartValueFormat[] = ['COMPACT', 'THOUSAND', 'PLAIN']
const LEGENDS: ChartLegendPosition[] = ['top', 'bottom', 'left', 'right', 'hidden']

function text(v: unknown): string | null {
  if (typeof v !== 'string') return null
  const s = v.trim()
  return s ? s : null
}

function int(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? Math.trunc(n) : null
}

/** 取第一个非空的字符串字段（覆盖 → 自带 → 默认）。 */
function pickText(...vals: unknown[]): string | null {
  for (const v of vals) {
    const s = text(v)
    if (s) return s
  }
  return null
}

function pickEnum<T extends string>(allowed: T[], fallback: T, ...vals: unknown[]): T {
  for (const v of vals) {
    if (typeof v === 'string' && (allowed as string[]).includes(v)) return v as T
  }
  return fallback
}

function pickInt(...vals: unknown[]): number | null {
  for (const v of vals) {
    const n = int(v)
    if (n != null) return n
  }
  return null
}

/**
 * 合并出最终生效的样式。
 *
 * @param specStyle chart_spec 自带的 style（后端自动推断或用户在指标/结果面板设置）
 * @param override  更高优先级的覆盖（大屏 widget 级、问数会话级）
 * @param defaults  租户级默认（来自 {@code GET /api/config/effective} 的 CHART 分类）
 */
export function resolveChartStyle(
  specStyle?: ChartStyle | null,
  override?: ChartStyle | null,
  defaults?: ChartStyle | null
): ResolvedChartStyle {
  const s = specStyle ?? {}
  const o = override ?? {}
  const d = defaults ?? {}
  const decimals = pickInt(o.valueDecimals, s.valueDecimals, d.valueDecimals)
  return {
    xAxisName: pickText(o.xAxisName, s.xAxisName, d.xAxisName),
    yAxisName: pickText(o.yAxisName, s.yAxisName, d.yAxisName),
    valueUnit: pickText(o.valueUnit, s.valueUnit, d.valueUnit) ?? '',
    valueFormat: pickEnum<ChartValueFormat>(FORMATS, 'COMPACT', o.valueFormat, s.valueFormat, d.valueFormat),
    // -1 是后端"自动"的哨兵值，等同于未设置
    valueDecimals: decimals != null && decimals >= 0 ? decimals : null,
    legendPosition: pickEnum<ChartLegendPosition>(LEGENDS, 'bottom', o.legendPosition, s.legendPosition, d.legendPosition),
    xLabelRotate: clampRotate(pickInt(o.xLabelRotate, s.xLabelRotate, d.xLabelRotate))
  }
}

function clampRotate(v: number | null): number | null {
  if (v == null) return null
  return Math.max(0, Math.min(90, v))
}

/** 轴名/系列名拼单位："销售额" + "元" → "销售额(元)"；无名时返回 undefined（不写轴名）。 */
export function nameWithUnit(name: string | null | undefined, unit: string): string | undefined {
  const n = typeof name === 'string' ? name.trim() : ''
  if (!n) return undefined
  return unit ? `${n}(${unit})` : n
}

function applyDecimals(v: number, decimals: number | null): string {
  if (decimals != null) return v.toFixed(decimals)
  // 自动：整数原样，小数至多 2 位（去掉无意义的尾零）
  if (Number.isInteger(v)) return String(v)
  return String(Number(v.toFixed(2)))
}

/**
 * 数值格式化（不含单位）。
 *
 * <p>COMPACT 分支在未设小数位时与历史 {@code compactNum} 完全一致：≥1亿 → "X.X亿"、
 * ≥1万 → "X.X万"、其余四舍五入取整。
 */
export function formatChartValue(v: number, style: ResolvedChartStyle): string {
  if (!Number.isFinite(v)) return String(v)
  const d = style.valueDecimals
  const abs = Math.abs(v)
  if (style.valueFormat === 'THOUSAND') {
    return v.toLocaleString('zh-CN', d == null ? { maximumFractionDigits: 2 } : {
      minimumFractionDigits: d,
      maximumFractionDigits: d
    })
  }
  if (style.valueFormat === 'COMPACT') {
    if (abs >= 1e8) return scaled(v / 1e8, d) + '亿'
    if (abs >= 1e4) return scaled(v / 1e4, d) + '万'
    // 百分比值不存在万亿语义，取整只会把 89.73% 吞成 90%（A2）：按自动小数位呈现
    if (style.valueUnit === '%') return applyDecimals(v, d)
    return d == null ? String(Math.round(v)) : v.toFixed(d)
  }
  return applyDecimals(v, d)
}

/** 万亿紧凑的缩放值：未指定小数位时沿用"1 位小数、整值去尾零"的历史写法。 */
function scaled(v: number, decimals: number | null): string {
  if (decimals != null) return v.toFixed(decimals)
  return v.toFixed(1).replace(/\.0$/, '')
}

/** 带单位的数值（tooltip / 仪表盘读数 / 饼图标签用）。 */
export function formatChartValueWithUnit(v: number, style: ResolvedChartStyle): string {
  const s = formatChartValue(v, style)
  return style.valueUnit ? s + style.valueUnit : s
}

/**
 * 收敛用户编辑的样式草稿：只保留"确实设了值"的字段，避免把一堆空串写进 chart_spec
 * （{@code share_link.chart_spec} 等列宽有限，且空值本就等同回落默认）。
 */
export function pruneChartStyle(style?: ChartStyle | null): ChartStyle | null {
  if (!style) return null
  const out: ChartStyle = {}
  const xa = text(style.xAxisName)
  const ya = text(style.yAxisName)
  const unit = text(style.valueUnit)
  const fmt = text(style.valueFormat)
  const dec = int(style.valueDecimals)
  const legend = text(style.legendPosition)
  const rotate = int(style.xLabelRotate)
  if (xa) out.xAxisName = xa
  if (ya) out.yAxisName = ya
  if (unit) out.valueUnit = unit
  if (fmt) out.valueFormat = fmt
  if (dec != null && dec >= 0) out.valueDecimals = dec
  if (legend) out.legendPosition = legend
  // 0 是有意义的显式值（强制水平，关闭 ECharts 的标签重叠自动旋转），故不能用 >0 过滤
  if (rotate != null && rotate >= 0) out.xLabelRotate = rotate
  return Object.keys(out).length ? out : null
}

/**
 * 两个样式是否等价（按收敛后的字段逐个比）。
 *
 * <p>用于阻断“父级回填草稿 → 草稿 watch 再上抛 → 父级又回填”的回环：上抛前先比一下，
 * 内容未变则不 emit。pruneChartStyle 每次返回新对象，单靠引用相等拦不住。
 */
export function sameChartStyle(a?: ChartStyle | null, b?: ChartStyle | null): boolean {
  const x = pruneChartStyle(a)
  const y = pruneChartStyle(b)
  if (!x || !y) return !x && !y
  return (
    (x.xAxisName ?? '') === (y.xAxisName ?? '') &&
    (x.yAxisName ?? '') === (y.yAxisName ?? '') &&
    (x.valueUnit ?? '') === (y.valueUnit ?? '') &&
    (x.valueFormat ?? '') === (y.valueFormat ?? '') &&
    x.valueDecimals === y.valueDecimals &&
    (x.legendPosition ?? '') === (y.legendPosition ?? '') &&
    x.xLabelRotate === y.xLabelRotate
  )
}
