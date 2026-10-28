/**
 * 指标库纯函数（T7.3 自 MetricView 逐字抽离）：分组树构建 / 后代展开 / 扁平化，
 * 以及新鲜度、图表类型文案与加入驾驶舱的网格尺寸。
 * 全部为无副作用的纯函数，输入显式传参（不捕获 ref），便于独立测试与多处复用。
 */
import type { ChartSpec, DashboardGroupVO, MetricVO } from '@/types'

/** 分组目录树节点（含两个合成根节点）。 */
export interface GroupTreeNode {
  id: number | string
  name: string
  parentId: number | null
  children: GroupTreeNode[]
  isSpecial?: boolean
}

/** 由平铺分组列表构建目录树，前后拼上「全部指标 / 未分组」两个合成节点。 */
export function buildGroupTree(groups: DashboardGroupVO[]): GroupTreeNode[] {
  const nodes = new Map<number, GroupTreeNode>()
  for (const g of groups) {
    nodes.set(g.id, { id: g.id, name: g.name, parentId: g.parentId ?? null, children: [] })
  }
  const roots: GroupTreeNode[] = []
  for (const n of nodes.values()) {
    const pid = n.parentId
    if (pid != null && nodes.has(pid)) nodes.get(pid)!.children.push(n)
    else roots.push(n)
  }
  const sortNodes = (list: GroupTreeNode[]) => {
    list.sort((a, b) => (a.id as number) - (b.id as number))
    list.forEach((n) => sortNodes(n.children))
  }
  sortNodes(roots)
  return [
    { id: 'all', name: '全部指标', parentId: null, children: [], isSpecial: true },
    ...roots,
    { id: 'ungrouped', name: '未分组', parentId: null, children: [], isSpecial: true }
  ]
}

/** 某分组的全部后代 id（含自身），用于「点父节点显示整棵子树下的指标」。 */
export function descendantGroupIds(groups: DashboardGroupVO[], id: number): number[] {
  const result: number[] = [id]
  const stack = [id]
  while (stack.length) {
    const cur = stack.pop() as number
    for (const g of groups) {
      if (g.parentId === cur) {
        result.push(g.id)
        stack.push(g.id)
      }
    }
  }
  return result
}

/** 移动指标下拉用的扁平分组（带缩进），跳过合成节点。 */
export function flattenGroupOptions(tree: GroupTreeNode[]): { id: number; name: string; indent: string }[] {
  const out: { id: number; name: string; indent: string }[] = []
  const walk = (list: GroupTreeNode[], depth: number) => {
    for (const n of list) {
      if (n.isSpecial) continue
      out.push({ id: n.id as number, name: n.name, indent: depth > 0 ? '　'.repeat(depth) : '' })
      walk(n.children, depth + 1)
    }
  }
  walk(tree.filter((n) => !n.isSpecial), 0)
  return out
}

/** 图表类型中文名（卡片标签）。 */
export function chartLabel(spec?: ChartSpec | null): string {
  switch (spec?.type) {
    case 'LINE':
      return '折线图'
    case 'BAR':
      return '柱状图'
    case 'PIE':
      return '饼图'
    case 'TABLE':
      return '明细表'
    default:
      return '明细表'
  }
}

/**
 * 新鲜度文案（P2）。判定完全由后端下发（stale / neverRefreshed / ageHours），
 * 前端只负责措辞与标黄——避免两端各算一套 SLA 口径导致"这边标黄那边不标"。
 */
export function freshnessText(m: MetricVO): string {
  if (m.neverRefreshed) return '尚未取数'
  const h = m.ageHours ?? 0
  const age = h < 1 ? '刚刚' : h < 24 ? `${h} 小时前` : `${Math.floor(h / 24)} 天前`
  return m.stale ? `数据已过期 · ${age}` : `数据 ${age}`
}

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
