/**
 * AI 自适应布局引擎（确定性算法，非大模型调用）。
 *
 * 目标：替代原先「按图表类型三分支 + 从左到右朴素换行」的粗放排布，
 * 改为**依据每个指标的真实数据形态**自动判定视觉角色、计算尺寸并分层排版。
 *
 * 输入侧特征（全部来自指标自身的 chartSpec 与已加载的结果数据）：
 *  - 数据行数 rows、结果列数 cols；
 *  - 类目数 categories（X 轴去重取值个数）；
 *  - 系列数 series（seriesField 去重，或度量列个数）；
 *  - 最长类目标签长度 maxLabel（长标签需要更宽的横向空间）；
 *  - 是否单值 single（1 行 1 度量 → 指标卡）。
 *
 * 由此判定视觉角色（KPI / 趋势 / 地理 / 对比 / 排行 / 占比 / 分布 / 明细），
 * 再按视觉层级决定摆放先后：
 *   指标卡 → 趋势主图 → 地理 → 对比/排行 → 占比 → 分布 → 明细表（沉底），
 * 最后做**全局**首次适配（first-fit）装箱：后放者允许回填先放者右侧的缝隙，显著减少留白。
 *
 * 纯函数、无副作用、无网络依赖，故零延迟、零配额、结果稳定可预期。
 */
import type { ChartSpec, QueryResult } from '@/types'

/** 视觉角色：决定尺寸与所在层带。 */
export type LayoutRole =
  | 'KPI'
  | 'TREND'
  | 'GEO'
  | 'COMPARISON'
  | 'RANKING'
  | 'COMPOSITION'
  | 'DISTRIBUTION'
  | 'DETAIL'

/** 角色中文名（用于结果摘要）。 */
export const ROLE_LABEL: Record<LayoutRole, string> = {
  KPI: '指标卡',
  TREND: '趋势',
  GEO: '地理',
  COMPARISON: '对比',
  RANKING: '排行',
  COMPOSITION: '占比',
  DISTRIBUTION: '分布',
  DETAIL: '明细'
}

/** 视觉层级：越靠前越优先置于上方（指标卡吸顶、明细表沉底）。 */
const BAND_ORDER: LayoutRole[] = [
  'KPI',
  'TREND',
  'GEO',
  'COMPARISON',
  'RANKING',
  'COMPOSITION',
  'DISTRIBUTION',
  'DETAIL'
]

/** 参与排布的一项（编辑器里即一个 widget）。 */
export interface AdaptiveInput {
  i: string | number
  name?: string | null
  chartSpec?: ChartSpec | null
  /** 已加载的真实结果数据；为 null 时退化为「仅按图表类型估算」。 */
  result?: QueryResult | null
}

/** 排布结果的一项。 */
export interface AdaptiveSlot {
  i: string | number
  x: number
  y: number
  w: number
  h: number
  role: LayoutRole
  /** 人读的尺寸依据，便于排查「为什么这么排」。 */
  reason: string
  /** 该组件的数据形态摘要（供 AI 智能设计喂给大模型，不发明细数据行）。 */
  features: AdaptiveFeatures
}

export interface AdaptivePlan {
  slots: AdaptiveSlot[]
  /** 人读摘要，如「2 个指标卡、1 个趋势、1 个明细」。 */
  summary: string
  /** 布局总行数（网格行，非像素）。 */
  rows: number
}

/** 从指标与结果中提取的排布特征（导出供大模型描述用途）。 */
export interface AdaptiveFeatures {
  type: string
  rowCount: number
  colCount: number
  categories: number
  series: number
  measures: number
  maxLabel: number
  single: boolean
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v))
}

function colIndex(columns: string[] | undefined, name?: string | null): number {
  if (!columns || !name) return -1
  return columns.indexOf(name)
}

/** 某列去重取值个数（上限 cap，防止超大结果集卡顿）。 */
function distinctCount(rows: unknown[][] | undefined, idx: number, cap = 300): number {
  if (!rows || idx < 0) return 0
  const set = new Set<string>()
  for (const r of rows) {
    const v = r?.[idx]
    set.add(v == null ? '' : String(v))
    if (set.size >= cap) break
  }
  return set.size
}

/** 某列最长文本长度（类目标签过长需要更宽的横向空间）。 */
function maxLabelLen(rows: unknown[][] | undefined, idx: number): number {
  if (!rows || idx < 0) return 0
  let mx = 0
  for (const r of rows) {
    const v = r?.[idx]
    const n = v == null ? 0 : String(v).length
    if (n > mx) mx = n
  }
  return mx
}

function extract(input: AdaptiveInput): AdaptiveFeatures {
  const spec = input.chartSpec ?? null
  const res = input.result ?? null
  const type = (spec?.type ?? 'TABLE').toString().toUpperCase()
  const columns = res?.columns ?? []
  const rows = res?.rows ?? []
  const rowCount = res?.rowCount ?? rows.length ?? 0
  const colCount = columns.length
  const xIdx = colIndex(columns, spec?.xField)
  const sIdx = colIndex(columns, spec?.seriesField)
  const categories = xIdx >= 0 ? distinctCount(rows, xIdx) : rowCount
  const series = sIdx >= 0 ? distinctCount(rows, sIdx) : Math.max(1, spec?.yFields?.length ?? 1)
  const measures = Math.max(1, spec?.yFields?.length ?? Math.max(1, colCount - 1))
  const maxLabel = maxLabelLen(rows, xIdx >= 0 ? xIdx : 0)
  const single = rowCount <= 1 && measures <= 1
  return { type, rowCount, colCount, categories, series, measures, maxLabel, single }
}

/** 特征 → 视觉角色。 */
function roleOf(f: AdaptiveFeatures): LayoutRole {
  // 单值（1 行 1 度量）优先做成指标卡，无论原本推荐了什么图
  if (f.single && f.type !== 'TABLE') return 'KPI'
  switch (f.type) {
    case 'TABLE':
      return 'DETAIL'
    case 'PIE':
    case 'FUNNEL':
    case 'RADAR':
      return 'COMPOSITION'
    case 'GAUGE':
      return 'KPI'
    case 'MAP':
      return 'GEO'
    case 'SCATTER':
    case 'HEATMAP':
      return 'DISTRIBUTION'
    case 'LINE':
    case 'AREA':
      return 'TREND'
    case 'HBAR':
      return 'RANKING'
    case 'BAR':
      // 竖向柱状：类目一多就挤，转为「加高的排行」表达
      return f.categories > 8 ? 'RANKING' : 'COMPARISON'
    default:
      return f.categories > 8 ? 'RANKING' : 'COMPARISON'
  }
}

/**
 * 角色 + 数据形态 → 网格尺寸（w/h）。
 *
 * @param screen 大屏模式：统一使用紧凑行高（KPI=2、图表=3、明细≤3），
 *               因为大屏网格按视口高度<b>等比分配行高</b>，行高一厚就会把同行图表压扁。
 */
function sizeOf(
  role: LayoutRole,
  f: AdaptiveFeatures,
  cols: number,
  screen: boolean
): { w: number; h: number } {
  const half = Math.max(3, Math.round(cols / 2))
  const third = Math.max(3, Math.round(cols / 3))
  let w = half
  let h = 4
  // 大屏分栏基准（12 列）：主视觉 8 宽 + 次视觉 4 宽。
  // 若主视觉独占整行，后续组件只能整体下移一行，行数预算会被白白吃掉。
  const wide = screen && cols >= 12 ? 8 : half
  const narrow = screen && cols >= 12 ? 4 : third
  switch (role) {
    case 'KPI':
      // 指标卡小而齐：大屏模式一行放 6 个（w=2，与新样式 KPI 带一致），普通模式一行 4 个
      w = screen ? 2 : cols >= 12 ? 3 : Math.max(2, Math.round(cols / 4))
      h = 2
      break
    case 'TREND':
      // 时间点多或系列多 → 整行，否则半幅；大屏模式统一 8 宽作主视觉，右侧留 4 宽给次视觉
      w = screen ? wide : f.categories >= 12 || f.series >= 3 ? cols : half
      h = screen ? 3 : 4
      break
    case 'GEO':
      // 大屏模式：地图给 4 宽而非 8 宽。①地图接近正方形才好看（8 宽 ×3 行会被压成扁条）；
      // ②这样它能和另外两个 4 宽组件凑满一整排，5 个图表正好排两排，无需压缩即可落在 8 行预算内。
      w = screen ? narrow : cols
      h = screen ? 3 : 6
      break
    case 'COMPARISON':
      w = screen ? narrow : half
      h = screen ? 3 : 4
      break
    case 'RANKING':
      // 类目越多越高（每条都要看得见）；大屏模式统一压到 3 行，靠滚动/裁剪展示
      w = screen ? (f.categories > 14 ? cols : narrow) : f.categories > 14 ? cols : half
      h = screen ? 3 : clamp(3 + Math.ceil(f.categories / 5), 4, 10)
      break
    case 'COMPOSITION':
      w = screen ? narrow : f.categories > 8 ? half : third
      h = screen ? 3 : 4
      break
    case 'DISTRIBUTION':
      w = screen ? narrow : half
      h = screen ? 3 : 5
      break
    case 'DETAIL':
      // 明细表：行数多或列数多则占满整行，行多则加高；大屏模式一律整行并压到 3 行
      w = screen ? cols : f.rowCount > 4 || f.colCount > 4 ? cols : half
      h = screen ? 3 : clamp(3 + Math.ceil(f.rowCount / 6), 3, 9)
      break
  }
  // 类目标签过长（如长地区名）需要更多横向空间。
  // ★大屏模式下跳过：否则宽度会被拉回半幅、破坏 8+4 分栏，进而吃掉行数预算。
  if (!screen && f.maxLabel > 8 && role !== 'DETAIL' && role !== 'KPI') {
    w = Math.min(cols, Math.max(w, half))
  }
  return { w: clamp(w, 2, cols), h: clamp(h, 2, screen ? 3 : 12) }
}

/** 尺寸依据说明。 */
function reasonOf(role: LayoutRole, f: AdaptiveFeatures): string {
  const n = f.categories
  switch (role) {
    case 'KPI':
      return '单值指标 → 指标卡'
    case 'TREND':
      return f.categories >= 12 || f.series >= 3
        ? `趋势 ${n} 点 / ${f.series} 系列 → 整行`
        : `趋势 ${n} 点 → 半幅`
    case 'GEO':
      return '地理分布 → 整行'
    case 'RANKING':
      return `类目较多（${n} 项）→ 加高排行`
    case 'COMPARISON':
      return `对比 ${n} 项 → 半幅`
    case 'COMPOSITION':
      return `占比 ${n} 项`
    case 'DISTRIBUTION':
      return '分布图 → 半幅加高'
    case 'DETAIL':
      return `明细 ${f.rowCount} 行 × ${f.colCount} 列 → 置底`
    default:
      return ROLE_LABEL[role]
  }
}

/** 占用网格：支撑首次适配（first-fit）装箱与缝隙回填。 */
class Occupancy {
  readonly cols: number
  private cells: boolean[][] = []

  constructor(cols: number) {
    this.cols = Math.max(1, cols)
  }

  get rows(): number {
    return this.cells.length
  }

  private ensure(rows: number): void {
    while (this.cells.length < rows) {
      this.cells.push(new Array<boolean>(this.cols).fill(false))
    }
  }

  /** 该矩形区域是否空闲（不扩容，避免搜索时把网格撑高）。 */
  private free(x: number, y: number, w: number, h: number): boolean {
    if (x < 0 || y < 0 || x + w > this.cols) return false
    for (let yy = y; yy < y + h; yy++) {
      if (yy >= this.cells.length) continue
      const row = this.cells[yy]
      for (let xx = x; xx < x + w; xx++) {
        if (row[xx]) return false
      }
    }
    return true
  }

  /** 自 startY 起寻找首个可容纳 w×h 的位置；找不到则另起新行。 */
  find(startY: number, w: number, h: number): { x: number; y: number } {
    const bottom = Math.max(startY, this.rows)
    for (let y = startY; y <= bottom; y++) {
      for (let x = 0; x + w <= this.cols; x++) {
        if (this.free(x, y, w, h)) return { x, y }
      }
    }
    return { x: 0, y: bottom }
  }

  place(x: number, y: number, w: number, h: number): void {
    this.ensure(y + h)
    for (let yy = y; yy < y + h; yy++) {
      for (let xx = x; xx < x + w; xx++) {
        this.cells[yy][xx] = true
      }
    }
  }
}

function summarize(list: { role: LayoutRole }[]): string {
  const counts = new Map<LayoutRole, number>()
  for (const e of list) counts.set(e.role, (counts.get(e.role) ?? 0) + 1)
  return BAND_ORDER.filter((r) => counts.has(r))
    .map((r) => `${counts.get(r)} 个${ROLE_LABEL[r]}`)
    .join('、')
}

/** 排一版布局（内部实现）。shrink 为行高缩放系数，用于大屏模式超预算时整体压缩。 */
function pack(items: AdaptiveInput[], cols: number, screen: boolean, shrink: number): AdaptivePlan {
  const grid = new Occupancy(cols)
  const enriched = items.map((it) => {
    const f = extract(it)
    const role = roleOf(f)
    const raw = sizeOf(role, f, grid.cols, screen)
    // 压缩只在行高上生效，最小 2 行（再矮图表就没法看了）
    const h = Math.max(2, Math.round(raw.h * shrink))
    return { it, role, w: raw.w, h, reason: reasonOf(role, f), features: f }
  })

  // 排序键：先按视觉层级（保证指标卡吸顶、明细表沉底），同层级内「先大后小」更紧实。
  const rank = new Map<LayoutRole, number>()
  BAND_ORDER.forEach((r, idx) => rank.set(r, idx))
  const ordered = [...enriched].sort((a, b) => {
    const d = (rank.get(a.role) ?? 99) - (rank.get(b.role) ?? 99)
    return d !== 0 ? d : b.h - a.h || b.w - a.w
  })

  // 全局首次适配装箱（而非按层带割裂）：
  // 层级只决定摆放先后，后放的组件允许回填先放组件右侧的缝隙，从而显著减少留白。
  const slots: AdaptiveSlot[] = []
  for (const e of ordered) {
    const pos = grid.find(0, e.w, e.h)
    grid.place(pos.x, pos.y, e.w, e.h)
    slots.push({
      i: e.it.i,
      x: pos.x,
      y: pos.y,
      w: e.w,
      h: e.h,
      role: e.role,
      reason: e.reason,
      features: e.features
    })
  }

  return { slots, summary: summarize(enriched), rows: grid.rows }
}

/** 排布选项。 */
export interface AdaptiveOptions {
  /**
   * 全屏大屏模式。大屏网格按视口高度<b>等比分配行高</b>（{@code repeat(maxRow, minmax(0,1fr))}），
   * 行数越多每行越扁——超过预算会把图表压到不可读（历史上 23 行布局即因此翻车）。
   * 开启后：①统一紧凑行高（KPI=2、图表=3、明细≤3）；②超出 {@code rowsBudget} 时整体压缩重排。
   */
  screen?: boolean
  /** 大屏模式的目标总行数预算，默认 8（KPI 行 2 + 两排图表 3×2）。 */
  rowsBudget?: number
}

/** 大屏模式的默认行数预算（与「8 行布局」新样式规范一致）。 */
export const DEFAULT_SCREEN_ROWS = 8

/**
 * 生成自适应布局方案。
 *
 * <p>大屏模式下严格遵守行数预算：先按紧凑高度排一版，超预算则按比例压缩行高后重排（最多 3 轮）。
 * 这一步是「AI 一键生成失败 / 关闭后回退确定性算法」时同样不翻车的关键——
 * 若无此约束，基线算法对十几个组件同样会排出 20 多行的压扁布局。
 *
 * @param items 参与排布的组件（顺序无关，引擎会按角色重排）
 * @param cols  网格列数（默认 12）
 * @param opts  排布选项（大屏模式与行数预算）
 */
export function planAdaptiveLayout(
  items: AdaptiveInput[],
  cols = 12,
  opts: AdaptiveOptions = {}
): AdaptivePlan {
  const screen = opts.screen === true
  if (!screen) return pack(items, cols, false, 1)
  const budget = opts.rowsBudget && opts.rowsBudget > 0 ? opts.rowsBudget : DEFAULT_SCREEN_ROWS
  let result = pack(items, cols, true, 1)
  let shrink = 1
  let rounds = 0
  while (result.rows > budget && rounds < 3) {
    shrink = Math.max(0.5, shrink * (budget / result.rows))
    result = pack(items, cols, true, shrink)
    rounds++
  }
  return result
}
