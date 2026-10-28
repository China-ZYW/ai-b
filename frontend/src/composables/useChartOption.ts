import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import type { ChartSpec, ChartStyle, QueryResult } from '@/types'
import { lighten, resolveTheme, rgba, type DashboardTheme } from '@/theme/dashboardTheme'
import { displayColumns } from '@/utils/format'
import {
  formatChartValue,
  formatChartValueWithUnit,
  nameWithUnit,
  resolveChartStyle,
  type ResolvedChartStyle
} from '@/utils/chartStyle'

/**
 * 把后端 ChartSpec + QueryResult 转换为 ECharts option。
 *
 * 配色由主题令牌驱动（问题6）：传入 DashboardTheme（或其 key）即让图表色板、坐标轴、
 * 提示框与所在大屏/卡片背景联动；不传时默认浅色主题，适配问数结果的白底面板。
 *
 * 坐标轴名/图例/系列名一律取语义层展示名（业务别名，无别名兜底列名）；字段匹配仍用物理列名。
 *
 * 轴名/单位/数值格式/图例位置由 {@link resolveChartStyle} 按“调用方覆盖 &gt; spec.style &gt; 租户级默认
 * &gt; 代码默认”逐字段合并；三层全空时与引入样式前的硬编码表现一致，老图表不会变样。
 *
 * 说明：ChartType=TABLE 或维度/度量缺失时返回 null，由调用方渲染明细表兜底。
 */

/** 归一化主题入参：对象直用；字符串按 key 解析；缺省用浅色（白底面板）。 */
function toTheme(theme?: DashboardTheme | string | null): DashboardTheme {
  if (theme && typeof theme === 'object') return theme
  if (typeof theme === 'string' && theme) return resolveTheme(theme)
  return resolveTheme('light')
}

/** 柱状渐变（竖直：主色高光 → 主色）。 */
function barGradient(base: string): echarts.graphic.LinearGradient {
  return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: lighten(base, 0.28) },
    { offset: 1, color: base }
  ])
}

/** 横向条形渐变（水平：主色 → 主色高光，适配 HBAR 从左到右生长）。 */
function barGradientH(base: string): echarts.graphic.LinearGradient {
  return new echarts.graphic.LinearGradient(0, 0, 1, 0, [
    { offset: 0, color: base },
    { offset: 1, color: lighten(base, 0.28) }
  ])
}

/** 折线面积渐变（半透明主色）。 */
function areaGradient(base: string): echarts.graphic.LinearGradient {
  return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: rgba(base, 0.35) },
    { offset: 1, color: rgba(base, 0.02) }
  ])
}

function toNumber(v: unknown): number {
  if (typeof v === 'number') return v
  if (v == null) return 0
  const n = Number(v)
  return Number.isNaN(n) ? 0 : n
}

/** 数值紧凑化已下沉到 {@link formatChartValue}（受租户级 value-format / value-decimals 驱动）。 */

/** 向上取“好看”刻度上限（1/2/5×10^k），供雷达 indicator 等避免怪异刻度与可读性告警。 */
function niceCeil(v: number): number {
  if (v <= 0) return 1
  const base = 10 ** Math.floor(Math.log10(v))
  const f = v / base
  const nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10
  return nf * base
}

function colIndex(columns: string[], name?: string | null): number {
  if (!name) return -1
  return columns.indexOf(name)
}

/**
 * 百分比占比列统一缩放（A2）：单位为 % 且所有度量值都落在 0~1（SQL 占比列典型输出 0.8973）时，
 * 度量列整体 ×100 后再进图型装配——轴刻度/tooltip/数据标签/雷达上限全图型天然同口径，
 * 无需逐图型处理（仪表盘分支自身的同语义缩放因值已 >1 不会二次叠加）。
 * 任一值越界（已是百分数形态）/负数，或全为 0/1 的歧义形态，一律不缩放（保守不干扰既有观感）。
 */
function scalePctValues(
  rows: unknown[][],
  yIdxList: number[],
  st: ResolvedChartStyle
): unknown[][] {
  if (st.valueUnit !== '%' || !rows.length || !yIdxList.length) return rows
  let hasFrac = false
  for (const r of rows) {
    for (const i of yIdxList) {
      const v = r[i]
      if (v == null || v === '') continue
      const n = toNumber(v)
      if (n < 0 || n > 1) return rows
      if (n > 0 && n < 1) hasFrac = true
    }
  }
  if (!hasFrac) return rows
  return rows.map((r) => {
    const c = [...r]
    for (const i of yIdxList) {
      const v = c[i]
      if (v != null && v !== '') c[i] = Math.round(toNumber(v) * 10000) / 100
    }
    return c
  })
}

// 网格外边距收敛（containLabel 已含标签宽度）；bottom 预留图例带，避免图例与 x 轴标签重叠
const baseGrid = { left: 8, right: 16, top: 20, bottom: 26, containLabel: true }

/**
 * 构建图表 option；不适合可视化时返回 null。
 *
 * @param styleOverride 比 {@code spec.style} 更高优先级的覆盖（大屏 widget 级、问数会话级），可空
 * @param styleDefaults 租户级默认（{@code sys_config} CHART，由 useConfigStore 供给），可空
 */
export function buildChartOption(
  spec: ChartSpec | null | undefined,
  result: QueryResult,
  theme?: DashboardTheme | string | null,
  styleOverride?: ChartStyle | null,
  styleDefaults?: ChartStyle | null
): EChartsOption | null {
  if (!spec || spec.type === 'TABLE') return null
  const { columns } = result
  let rows = result.rows
  if (!columns?.length || !rows?.length) return null

  const yIdxList = (spec.yFields ?? []).map((f) => colIndex(columns, f)).filter((i) => i >= 0)
  if (yIdxList.length === 0) return null

  // 展示名（语义别名，无别名兜底列名）：只用于坐标轴名/图例/系列名等呈现，不参与字段定位
  const labels = displayColumns(result)
  const label = (i: number): string => labels[i] ?? columns[i]

  // 无维度单值（KPI）转置：后端在"用户显式指定图型但结果仅一个度量值"时不下发 xField，
  // 此处以"度量列名作类别、首行取值作数值"重构为单类别结果递归成图，尊重用户指定图型。
  if (!spec.xField && rows.length === 1) {
    const row = rows[0]
    const transposed: QueryResult = {
      ...result,
      columns: ['__category__', '__value__'],
      // 展示名随转置重建（原结果的列展示名已错位）；各度量的展示名写进行值作类别名
      columnLabels: null,
      rows: yIdxList.map((i) => [label(i), toNumber(row[i])])
    }
    return buildChartOption(
      { ...spec, xField: '__category__', yFields: ['__value__'], seriesField: null },
      transposed,
      theme,
      styleOverride,
      styleDefaults
    )
  }

  const xIdx = colIndex(columns, spec.xField)
  if (xIdx < 0) return null

  const t = toTheme(theme)
  const palette = t.chartPalette
  const primary = palette[0]
  const title = spec.title || undefined
  // 展示样式：调用方覆盖（widget/会话级）> spec.style（用户设置或后端自动推断）> 租户级默认 > 代码默认
  const st: ResolvedChartStyle = resolveChartStyle(spec.style, styleOverride, styleDefaults)
  // 百分比占比列统一缩放（A2）：KPI 转置递归分支在上面已提前 return，缩放会在递归入口内同样生效
  rows = scalePctValues(rows, yIdxList, st)

  // 坐标轴 / 提示框 / 标题 / 图例样式：全部取自主题令牌，随大屏背景联动
  const axisStyle = {
    axisLine: { lineStyle: { color: t.axisLine } },
    axisLabel: { color: t.axisLabel, fontSize: 11 },
    splitLine: { lineStyle: { color: t.splitLine, type: 'dashed' as const } },
    // 连续轴刻度数收敛，避免小尺寸 widget 下 y 轴标签相互重叠
    splitNumber: 4
  }
  // 数值轴标签按样式格式化（默认万亿紧凑），避免窄 widget 下长数字（1,500,000）拥挤或被右缘裁切
  const valueAxisStyle = {
    ...axisStyle,
    axisLabel: { ...axisStyle.axisLabel, formatter: (val: number) => formatChartValue(val, st) }
  }
  // 轴触发提示框统一走 valueFormatter：数值口径与轴标签一致（历史上 tooltip 显示原值、轴显示“150万”，两者对不上）
  const tooltipStyle = {
    trigger: 'axis' as const,
    backgroundColor: t.tooltipBg,
    borderColor: t.tooltipBorder,
    borderWidth: 1,
    textStyle: { color: t.tooltipText, fontSize: 12 },
    valueFormatter: (v: unknown) => formatChartValueWithUnit(toNumber(v), st),
    extraCssText: 'box-shadow:0 6px 24px rgba(0,10,40,0.28);border-radius:10px;backdrop-filter:blur(4px);'
  }
  const titleStyle = { fontSize: 14, color: t.textPrimary, fontWeight: 700 as const }
  const legendStyle = { color: t.axisLabel }
  // 饼图分片描边：深色系用暗色分隔，浅色系用白色分隔
  const pieBorder = t.dark ? 'rgba(4,16,44,0.55)' : '#ffffff'

  // ---------- 展示样式消费点（轴名 / 单位 / 数值格式 / 图例位置） ----------
  const unit = st.valueUnit
  /** 带单位的数值文本（tooltip / 仪表盘读数 / 饼图标签）。 */
  const numUnit = (v: unknown): string => formatChartValueWithUnit(toNumber(v), st)

  /**
   * 数据标签常显：让数值直接标在图形上，不必悬浮 tooltip 才看得到。
   * 柱/条：标在端点；线/面积：标在数据点；散点：标在点的度量值；饼：标“值(占比%)”。
   * 与轴/tooltip 同口径（带单位，受租户级 value-format/decimals 驱动）；labelLayout.hideOverlap 在区间过密时
   * 自动隐藏重叠标签，避免小尺寸 widget / 长数字糊成一团。
   */
  function seriesLabel(
    kind: 'bar' | 'line' | 'scatter' | 'pie',
    st: ResolvedChartStyle,
    horizontal = false
  ): Record<string, unknown> {
    const lbl: Record<string, unknown> = {
      show: true,
      color: t.axisLabel,
      fontSize: 11,
      labelLayout: { hideOverlap: true },
      formatter: (p: unknown) => formatChartValueWithUnit(toNumber((p as { value?: unknown }).value), st)
    }
    if (kind === 'bar') lbl.position = horizontal ? 'right' : 'top'
    else if (kind === 'line') lbl.position = 'top'
    else if (kind === 'scatter') {
      lbl.formatter = (p: unknown) => {
        const arr = Array.isArray((p as { value?: unknown }).value)
          ? ((p as { value?: unknown }).value as unknown[])
          : []
        return formatChartValueWithUnit(toNumber(arr[1]), st)
      }
    } else if (kind === 'pie') {
      lbl.position = 'outside'
      lbl.labelLine = { show: true }
      lbl.formatter = (p: unknown) => {
        const q = p as { value?: unknown; percent?: number }
        return `${formatChartValueWithUnit(toNumber(q.value), st)} (${q.percent ?? 0}%)`
      }
    }
    return lbl
  }

  /**
   * 轴名：仅当用户显式设置时才写（或该图型本就需要区分双数值轴，如散点，传 fallback）。
   * 默认不给笛卡尔图加轴名——那会改变既有图表观感，并在窄 widget 下挤占绘图区。
   * 单位只拼在数值轴上（isValueAxis），给维度轴拼单位没有意义。
   */
  const axisName = (
    explicit: string | null,
    isValueAxis: boolean,
    fallback?: string | null
  ): string | undefined => nameWithUnit(explicit ?? fallback ?? null, isValueAxis ? unit : '')

  /**
   * 数值轴名的兜底：已知单位且只有单度量时，把度量展示名带出来（“销售额(元)”），让单位在轴上可见。
   * 多度量时不兜底——用第一个度量名命名共用的数值轴会误导，该信息由图例表达。
   * 无单位时也不写轴名，保持引入样式前的观感与窄 widget 下的绘图区。
   */
  const valueAxisFallback = unit && yIdxList.length === 1 ? label(yIdxList[0]) : null

  /** 图例：位置取自样式（hidden 返回 undefined 即不渲染）；scroll 用于类别可能很多的饼/漏斗/分组系列。 */
  function legendOf(scroll: boolean): Record<string, unknown> | undefined {
    const pos = st.legendPosition
    if (pos === 'hidden') return undefined
    const base: Record<string, unknown> = { textStyle: legendStyle }
    if (scroll) base.type = 'scroll'
    if (pos === 'top') return { ...base, top: 8, left: 'center' }
    if (pos === 'left') return { ...base, left: 8, top: 'middle', orient: 'vertical' }
    if (pos === 'right') return { ...base, right: 8, top: 'middle', orient: 'vertical' }
    return { ...base, bottom: 8, left: 'center' }
  }

  /**
   * 网格：图例移到上/左/右时相应让位，避免与绘图区重叠（bottom 沿用既有 26px 图例带）。
   *
   * <p>{@code hasLegend=false}（单系列不展图例、或图例隐藏）时不为图例让位；{@code hasAxisName=true} 时
   * 额外预留上/右余量——ECharts 的 {@code containLabel} 不包含轴名，不留白会被容器裁切。
   */
  function gridOf(hasLegend: boolean, hasAxisName: boolean, rightExtra = 0): Record<string, unknown> {
    const g: Record<string, unknown> = { ...baseGrid }
    if (rightExtra) g.right = baseGrid.right + rightExtra
    if (hasAxisName) {
      g.top = Math.max(Number(g.top), 34)
      g.right = Math.max(Number(g.right), 44)
    }
    if (!hasLegend) return g
    const pos = st.legendPosition
    if (pos === 'top') g.top = Math.max(Number(g.top), 42)
    else if (pos === 'left') g.left = 92
    else if (pos === 'right') g.right = Math.max(Number(g.right), 92) + rightExtra
    return g
  }

  /**
   * 类别轴样式：仅在用户显式设了旋转角时写 rotate，否则保持 ECharts 自适应（不改变既有观感）。
   * 只给<b>物理 X 轴</b>为类别轴时用（条形图的 X 是数值轴，旋转刻度标签没有意义）。
   */
  const catAxisStyle =
    st.xLabelRotate != null
      ? { ...axisStyle, axisLabel: { ...axisStyle.axisLabel, rotate: st.xLabelRotate } }
      : axisStyle

  // ---------- 饼图 ----------
  if (spec.type === 'PIE') {
    const yIdx = yIdxList[0]
    const data = rows.map((r) => ({ name: String(r[xIdx] ?? '—'), value: toNumber(r[yIdx]) }))
    return {
      color: palette,
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        // 饼图 value 为单个标量，但默认文案丢了占比，这里显式拼“名称: 值单位 (占比%)”
        formatter: (p: unknown) => {
          const q = p as { name?: string; value?: unknown; percent?: number }
          return `${q.name ?? '—'}: ${numUnit(q.value)} (${q.percent ?? 0}%)`
        }
      },
      legend: legendOf(true),
      series: [
        {
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '48%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: pieBorder, borderWidth: 2, borderRadius: 6 },
          // 数据标签常显：数值(占比%)直接标在扇区外，hideOverlap 防过密时重叠；引导线开启
          label: seriesLabel('pie', st),
          labelLine: { show: true },
          data
        }
      ]
    } as EChartsOption
  }

  const xData = rows.map((r) => String(r[xIdx] ?? ''))

  // ---------- 热力图 HEATMAP（维度1=xField、维度2=seriesField、值=yFields[0]）----------
  if (spec.type === 'HEATMAP') {
    const hSeriesIdx = colIndex(columns, spec.seriesField)
    const valueIdx = yIdxList[0]
    const xCats = Array.from(new Set(rows.map((r) => String(r[xIdx] ?? ''))))
    const yCats = hSeriesIdx >= 0
      ? Array.from(new Set(rows.map((r) => String(r[hSeriesIdx] ?? '—'))))
      : [label(valueIdx)]
    let maxV = 0
    const data: number[][] = rows.map((r) => {
      const xi = xCats.indexOf(String(r[xIdx] ?? ''))
      const yi = hSeriesIdx >= 0 ? yCats.indexOf(String(r[hSeriesIdx] ?? '—')) : 0
      const v = toNumber(r[valueIdx])
      if (v > maxV) maxV = v
      return [xi, yi, v]
    })
    return {
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        // 热力图 value 是 [xi, yi, v] 三元组，通用 valueFormatter 处理不了数组，这里显式拼“行/列: 值”
        formatter: (p: unknown) => {
          const v = (p as { value?: unknown }).value
          const arr = Array.isArray(v) ? (v as unknown[]) : []
          return `${xCats[Number(arr[0])] ?? '—'} / ${yCats[Number(arr[1])] ?? '—'}: ${numUnit(arr[2])}`
        }
      },
      // 右侧 96px 留给 visualMap；热力图不渲染图例，故不受图例位置影响
      grid: { ...baseGrid, right: 96 },
      xAxis: {
        type: 'category',
        data: xCats,
        splitArea: { show: true },
        name: axisName(st.xAxisName, false),
        ...catAxisStyle
      },
      yAxis: {
        type: 'category',
        data: yCats,
        splitArea: { show: true },
        name: axisName(st.yAxisName, false),
        ...axisStyle
      },
      visualMap: {
        min: 0,
        max: maxV || 1,
        calculable: true,
        orient: 'vertical',
        right: 8,
        top: 'middle',
        itemHeight: 96,
        inRange: { color: [lighten(primary, 0.6), primary] },
        textStyle: { color: t.axisLabel }
      },
      series: [
        {
          type: 'heatmap',
          data,
          label: {
            show: true,
            fontSize: 11,
            // 浅色格用深色字、深色格用白字，保证格内数值对比度
            color: (p: { value?: unknown }) => {
              const arr = Array.isArray(p.value) ? (p.value as unknown[]) : []
              const v = Number(arr[2] ?? 0)
              return v / (maxV || 1) > 0.55 ? '#ffffff' : t.dark ? '#0b1e46' : '#1f2d55'
            }
          },
          emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(0,0,0,0.3)' } }
        }
      ]
    } as EChartsOption
  }

  // ---------- 雷达图 RADAR（指标名=xField 逐行、值=yFields）----------
  if (spec.type === 'RADAR') {
    const flatVals = rows.flatMap((r) => yIdxList.map((yi) => toNumber(r[yi])))
    const maxVal = flatVals.length ? Math.max(1, ...flatVals) : 1
    const niceMax = niceCeil(maxVal)
    const indicator = rows.map((r) => ({ name: String(r[xIdx] ?? '—'), max: niceMax }))
    const radarData = yIdxList.map((yi, i) => ({
      name: label(yi),
      value: rows.map((r) => toNumber(r[yi])),
      itemStyle: { color: palette[i % palette.length] },
      areaStyle: { opacity: 0.18 }
    }))
    return {
      color: palette,
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        // 雷达 value 是“每个 indicator 一个值”的数组，需逐维展开并带单位
        formatter: (p: unknown) => {
          const q = p as { name?: string; value?: unknown }
          const vals = Array.isArray(q.value) ? (q.value as unknown[]) : []
          const lines = indicator.map((ind, i) => `${ind.name}: ${numUnit(vals[i])}`)
          return [q.name ?? '', ...lines].filter((s) => s).join('<br/>')
        }
      },
      legend: yIdxList.length > 1 ? legendOf(false) : undefined,
      radar: {
        indicator,
        center: ['50%', '52%'],
        radius: '66%',
        axisName: { color: t.axisLabel, fontSize: 11 },
        splitLine: { lineStyle: { color: t.splitLine } },
        axisLine: { lineStyle: { color: t.axisLine } },
        splitArea: {
          areaStyle: {
            color: t.dark ? ['rgba(255,255,255,0.02)', 'rgba(255,255,255,0.05)'] : ['rgba(0,47,167,0.02)', 'rgba(0,47,167,0.05)']
          }
        }
      },
      series: [{ type: 'radar', data: radarData }]
    } as EChartsOption
  }

  // ---------- 漏斗图 FUNNEL（阶段=xField、值=yFields[0]）----------
  if (spec.type === 'FUNNEL') {
    const yIdx = yIdxList[0]
    const data = rows.map((r) => ({ name: String(r[xIdx] ?? '—'), value: toNumber(r[yIdx]) }))
    return {
      color: palette,
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        formatter: (p: unknown) => {
          const q = p as { name?: string; value?: unknown }
          return `${q.name ?? '—'}: ${numUnit(q.value)}`
        }
      },
      legend: legendOf(true),
      series: [
        {
          type: 'funnel',
          left: '10%',
          right: '10%',
          top: 34,
          bottom: 34,
          minSize: '24%',
          maxSize: '100%',
          sort: 'descending',
          gap: 2,
          label: {
            show: true,
            position: 'inside',
            color: '#fff',
            fontSize: 11,
            formatter: (p: unknown) => {
              const q = p as { name?: string; value?: unknown }
              return `${q.name ?? ''} ${numUnit(q.value)}`
            }
          },
          itemStyle: { borderColor: pieBorder, borderWidth: 1 },
          data
        }
      ]
    } as EChartsOption
  }

  // ---------- 仪表盘 GAUGE（值=yFields[0]，取首行）----------
  if (spec.type === 'GAUGE') {
    const yIdx = yIdxList[0]
    const rawValue0 = toNumber(rows[0]?.[yIdx])
    // 百分比单位 + 0~1 小数占比（SQL 占比列常见输出 0.8973）：×100 后按百分数呈现——
    // 否则刻度 0~100 下指针贴 0、读数被取整成 "1%"（Excel 百分比格式同此约定）；
    // 已是 0~100 形态的达成率（如 89.73）不落入该区间，行为不变
    const rawValue =
      st.valueUnit === '%' && rawValue0 >= 0 && rawValue0 <= 1 ? Math.round(rawValue0 * 10000) / 100
        : rawValue0
    // 读数小数位：用户设了则按其取位，否则沿用“至多 2 位（整数原样）”，避免 79.30416666666666 这类长尾
    const value =
      st.valueDecimals != null
        ? Number(rawValue.toFixed(st.valueDecimals))
        : Number.isInteger(rawValue)
          ? rawValue
          : Math.round(rawValue * 100) / 100
    const max = value <= 100 ? 100 : Math.ceil(value / 10) * 10
    // 仪表盘读数不走 COMPACT 紧凑取整（否则 89.73 又被打回 "90%"）：整数原样、小数至多 2 位 + 单位
    const plainStyle = { ...st, valueFormat: 'PLAIN' as const }
    const gaugeRead = (v: unknown): string => formatChartValueWithUnit(toNumber(v), plainStyle)
    return {
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        formatter: (p: unknown) => {
          const q = p as { name?: string; value?: unknown }
          return `${q.name ?? '—'}: ${gaugeRead(q.value)}`
        }
      },
      series: [
        {
          type: 'gauge',
          min: 0,
          max,
          center: ['50%', '58%'],
          progress: { show: true, width: 16, itemStyle: { color: primary } },
          axisLine: {
            lineStyle: {
              width: 16,
              color: [[1, t.dark ? 'rgba(255,255,255,0.12)' : 'rgba(0,47,167,0.12)']]
            }
          },
          axisTick: { show: false },
          splitNumber: 4,
          splitLine: { length: 10, lineStyle: { color: t.axisLine } },
          axisLabel: {
            color: t.axisLabel,
            distance: 12,
            fontSize: 10,
            formatter: (v: number) => formatChartValue(v, st)
          },
          pointer: { itemStyle: { color: primary } },
          anchor: { show: true, size: 14, itemStyle: { color: primary } },
          detail: {
            valueAnimation: true,
            fontSize: 22,
            fontWeight: 700,
            color: t.textPrimary,
            offsetCenter: [0, '62%'],
            formatter: (v: unknown) => gaugeRead(v)
          },
          title: { color: t.textSecondary, offsetCenter: [0, '88%'], fontSize: 12 },
          // 仪表盘下方的指标名：用户设了 Y 轴名则用它（该图型无坐标轴，Y 轴名即“这个读数叫什么”）
          data: [{ value, name: st.yAxisName ?? label(yIdx) }]
        }
      ]
    } as EChartsOption
  }

  // ---------- 散点图 SCATTER（两数值=xField + yFields）----------
  if (spec.type === 'SCATTER') {
    // x 需为数值列；若引擎把分类列(如省份)当作 xField，则回退用度量列作 x/y，避免散点塔缩到 x=0
    const xNumeric = rows.length > 0 && rows.every((r) => {
      const v = r[xIdx]
      return v != null && String(v).trim() !== '' && !Number.isNaN(Number(v))
    })
    const xi = xNumeric ? xIdx : yIdxList[0]
    const ys = xNumeric ? yIdxList : yIdxList.slice(1)
    const yList = ys.length ? ys : [yIdxList[0]]
    const series = yList.map((yi, i) => ({
      name: label(yi),
      type: 'scatter' as const,
      symbolSize: 12,
      itemStyle: { color: palette[i % palette.length], opacity: 0.75 },
      label: seriesLabel('scatter', st),
      data: rows.map((r) => [toNumber(r[xi]), toNumber(r[yi])])
    }))
    const sxName = axisName(st.xAxisName, false, label(xi))
    const syName = axisName(st.yAxisName, true, valueAxisFallback)
    return {
      color: palette,
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        // 散点 value 是 [x, y] 二元组：单位只拼在 y（对应 yFields），x 是另一个度量、量纲不同
        formatter: (p: unknown) => {
          const q = p as { seriesName?: string; value?: unknown }
          const arr = Array.isArray(q.value) ? (q.value as unknown[]) : []
          return `${q.seriesName ?? ''}: ${formatChartValue(toNumber(arr[0]), st)} / ${numUnit(arr[1])}`
        }
      },
      legend: series.length > 1 ? legendOf(false) : undefined,
      grid: gridOf(series.length > 1, !!(sxName || syName)),
      xAxis: {
        type: 'value',
        scale: true,
        name: sxName,
        nameTextStyle: { color: t.axisLabel },
        ...valueAxisStyle
      },
      yAxis: {
        type: 'value',
        scale: true,
        name: syName,
        nameTextStyle: { color: t.axisLabel },
        ...valueAxisStyle
      },
      series
    } as EChartsOption
  }

  // ---------- 地图 MAP（地理列=xField、值=yFields[0]；geoJSON 由 BaseChart 异步注册）----------
  if (spec.type === 'MAP') {
    const yIdx = yIdxList[0]
    const data = rows.map((r) => ({ name: String(r[xIdx] ?? '').trim(), value: toNumber(r[yIdx]) }))
    const maxV = data.length ? Math.max(1, ...data.map((d) => d.value)) : 1
    return {
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: {
        ...tooltipStyle,
        trigger: 'item',
        formatter: (p: unknown) => {
          const q = p as { name?: string; value?: unknown }
          return `${q.name ?? '—'}: ${numUnit(q.value)}`
        }
      },
      visualMap: {
        min: 0,
        max: maxV,
        left: 12,
        top: 'middle',
        itemHeight: 96,
        calculable: true,
        inRange: { color: [lighten(primary, 0.6), primary] },
        textStyle: { color: t.axisLabel }
      },
      series: [
        {
          type: 'map',
          map: 'china',
          roam: true,
          layoutCenter: ['55%', '50%'],
          layoutSize: '96%',
          zoom: 1.05,
          label: { show: false },
          emphasis: {
            label: { show: true, color: t.textPrimary },
            itemStyle: { areaColor: lighten(primary, 0.3) }
          },
          itemStyle: {
            areaColor: t.dark ? 'rgba(255,255,255,0.06)' : '#eef2fb',
            borderColor: t.axisLine
          },
          data
        }
      ]
    } as EChartsOption
  }

  const isArea = spec.type === 'AREA'
  const isLine = spec.type === 'LINE' || isArea
  const isHBar = spec.type === 'HBAR'
  const seriesType = isLine ? 'line' : 'bar'

  // ---------- 笛卡尔坐标轴（折线/柱状/面积/条形共用）----------
  // 条形图（HBAR）两轴互换：X 是数值轴（单位与度量名兜底拼这里）、Y 是类别轴
  const nameStyle = { color: t.axisLabel }
  const xName = axisName(st.xAxisName, isHBar, isHBar ? valueAxisFallback : null)
  const yName = axisName(st.yAxisName, !isHBar, isHBar ? null : valueAxisFallback)
  const hasAxisName = !!(xName || yName)

  /** 物理 X 轴：非 HBAR 时为类别轴（取旋转角），HBAR 时为数值轴（取格式化）。 */
  function cartesianX(cats?: string[], boundaryGap?: boolean) {
    return isHBar
      ? { type: 'value' as const, name: xName, nameTextStyle: nameStyle, ...valueAxisStyle }
      : {
          type: 'category' as const,
          data: cats ?? [],
          ...(boundaryGap == null ? {} : { boundaryGap }),
          name: xName,
          nameTextStyle: nameStyle,
          ...catAxisStyle
        }
  }

  /** 物理 Y 轴：非 HBAR 时为数值轴，HBAR 时为类别轴（旋转角只对物理 X 轴生效，故这里用 axisStyle）。 */
  function cartesianY(cats?: string[], boundaryGap?: boolean, scale = false) {
    return isHBar
      ? {
          type: 'category' as const,
          data: cats ?? [],
          ...(boundaryGap == null ? {} : { boundaryGap }),
          name: yName,
          nameTextStyle: nameStyle,
          ...axisStyle
        }
      : { type: 'value' as const, scale, name: yName, nameTextStyle: nameStyle, ...valueAxisStyle }
  }

  // ---------- 分组（存在 seriesField）：按系列字段透视 ----------
  const seriesIdx = colIndex(columns, spec.seriesField)
  if (seriesIdx >= 0) {
    const valueIdx = yIdxList[0]
    const categories = Array.from(new Set(xData))
    const seriesNames = Array.from(new Set(rows.map((r) => String(r[seriesIdx] ?? '—'))))
    const series = seriesNames.map((sName, i) => {
      const data = categories.map((cat) => {
        const row = rows.find((r) => String(r[xIdx] ?? '') === cat && String(r[seriesIdx] ?? '—') === sName)
        return row ? toNumber(row[valueIdx]) : 0
      })
      return {
        name: sName,
        type: seriesType,
        smooth: isLine,
        barMaxWidth: 34,
        label: seriesLabel(seriesType, st, isHBar),
        itemStyle: isLine
          ? undefined
          : {
              color: palette[i % palette.length],
              borderRadius: isHBar ? [0, 4, 4, 0] : [4, 4, 0, 0]
            },
        areaStyle: isArea ? { opacity: 0.28 } : isLine ? { opacity: 0.14 } : undefined,
        data
      }
    })
    return {
      color: palette,
      title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
      tooltip: tooltipStyle,
      legend: legendOf(true),
      grid: gridOf(true, hasAxisName),
      xAxis: cartesianX(categories),
      yAxis: cartesianY(categories, undefined, isLine),
      series
    } as EChartsOption
  }

  // ---------- 常规（一个维度 + 一/多个度量）----------
  const series = yIdxList.map((yIdx, i) => {
    const base = palette[i % palette.length]
    return {
      name: label(yIdx),
      type: seriesType,
      smooth: isLine,
      barMaxWidth: 42,
      symbolSize: 7,
      label: seriesLabel(seriesType, st, isHBar),
      itemStyle: isLine
        ? { color: base }
        : {
            color: yIdxList.length === 1 ? (isHBar ? barGradientH(primary) : barGradient(primary)) : base,
            borderRadius: isHBar ? [0, 5, 5, 0] : [5, 5, 0, 0]
          },
      lineStyle: isLine ? { width: 3, color: base } : undefined,
      // 面积图恒开填充；折线仅单度量时做渐变面积
      areaStyle: isArea
        ? { color: areaGradient(yIdxList.length === 1 ? primary : base) }
        : isLine && yIdxList.length === 1
          ? { color: areaGradient(primary) }
          : undefined,
      data: rows.map((r) => toNumber(r[yIdx]))
    }
  })

  return {
    color: palette,
    title: title ? { text: title, left: 'center', textStyle: titleStyle } : undefined,
    tooltip: tooltipStyle,
    legend: yIdxList.length > 1 ? legendOf(false) : undefined,
    grid: gridOf(yIdxList.length > 1, hasAxisName),
    xAxis: cartesianX(xData, !isLine),
    yAxis: cartesianY(xData, true, isLine),
    dataZoom:
      xData.length > 30
        ? [
            isHBar
              ? { type: 'slider', yAxisIndex: 0, width: 16, right: 8, borderColor: 'transparent' }
              : { type: 'slider', height: 16, bottom: 34, borderColor: 'transparent' }
          ]
        : undefined,
    series
  } as EChartsOption
}
