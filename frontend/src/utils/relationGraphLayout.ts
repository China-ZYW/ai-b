/**
 * 关联图谱的布局算法（纯函数，不依赖 Vue，便于单独验证）。
 *
 * 策略：有向分层 + 连通分量垂直堆叠。
 * - 分层沿用登记语义——边从「多方」指向「一方」，层号 = 上游层号 + 1，
 *   于是"明细表在左、维表在右"的自然读法成立；
 * - 连通分量（互不相连的两簇事实表）垂直堆叠，否则它们会被挤进同一列、叠成一团；
 * - 含环（自引用 / 环形外键）的子集靠轮次上限收敛，绝不死循环。
 */

export const GRAPH_NODE_H = 40
export const GRAPH_ROW_GAP = 26
export const GRAPH_COL_GAP = 232
export const GRAPH_COMP_GAP = 64
export const GRAPH_PAD = 44

export interface GraphPoint {
  x: number
  y: number
}

export interface GraphLayoutOptions {
  nodeH: number
  rowGap: number
  colGap: number
  compGap: number
  pad: number
}

export interface GraphLayoutResult {
  /** 表名 → 画布坐标（左上角为原点，未缩放）。 */
  pos: Map<string, GraphPoint>
  width: number
  height: number
}

const DEFAULTS: GraphLayoutOptions = {
  nodeH: GRAPH_NODE_H,
  rowGap: GRAPH_ROW_GAP,
  colGap: GRAPH_COL_GAP,
  compGap: GRAPH_COMP_GAP,
  pad: GRAPH_PAD
}

export function computeRelationLayout(
  names: string[],
  links: { source: string; target: string }[],
  options?: Partial<GraphLayoutOptions>
): GraphLayoutResult {
  const opt = { ...DEFAULTS, ...(options ?? {}) }
  const { nodeH, rowGap, colGap, compGap, pad } = opt

  // 无向邻接：连通性判断与去重都走它
  const adj = new Map<string, string[]>()
  names.forEach((n) => adj.set(n, []))
  links.forEach((l) => {
    if (!adj.has(l.source) || !adj.has(l.target)) return
    adj.get(l.source)!.push(l.target)
    adj.get(l.target)!.push(l.source)
  })

  // 连通分量
  const seen = new Set<string>()
  const comps: string[][] = []
  names.forEach((n) => {
    if (seen.has(n)) return
    const comp: string[] = []
    const queue = [n]
    seen.add(n)
    while (queue.length) {
      const cur = queue.shift()!
      comp.push(cur)
      ;(adj.get(cur) ?? []).forEach((nb) => {
        if (!seen.has(nb)) {
          seen.add(nb)
          queue.push(nb)
        }
      })
    }
    comps.push(comp)
  })

  const pos = new Map<string, GraphPoint>()
  let yCursor = 0
  let maxLayers = 1

  comps.forEach((comp) => {
    const layer = new Map<string, number>()
    comp.forEach((t) => layer.set(t, 0))
    const directed = new Map<string, string[]>()
    comp.forEach((t) => directed.set(t, []))

    // 有向边（自环不参与分层，它只表示"表里存了自己"）
    links.forEach((l) => {
      if (layer.has(l.source) && layer.has(l.target) && l.source !== l.target) {
        directed.get(l.source)!.push(l.target)
      }
    })

    // 迭代松弛：入度小的先定层；有环时靠轮次上限收敛，绝不死循环
    for (let i = 0; i <= comp.length; i += 1) {
      let changed = false
      comp
        .slice()
        .sort(
          (a, b) =>
            (directed.get(a) ?? []).length - (directed.get(b) ?? []).length || a.localeCompare(b)
        )
        .forEach((t) => {
          ;(directed.get(t) ?? []).forEach((prev) => {
            const want = (layer.get(prev) ?? 0) + 1
            if (want > (layer.get(t) ?? 0)) {
              layer.set(t, want)
              changed = true
            }
          })
        })
      if (!changed) break
    }

    const byLayer = new Map<number, string[]>()
    comp.forEach((t) => {
      const l = layer.get(t) ?? 0
      maxLayers = Math.max(maxLayers, l + 1)
      if (!byLayer.has(l)) byLayer.set(l, [])
      byLayer.get(l)!.push(t)
    })

    const rows = comp.length
    const compH = rows * nodeH + Math.max(0, rows - 1) * rowGap
    byLayer.forEach((arr, l) => {
      arr.sort((a, b) => a.localeCompare(b))
      const startY = (compH - arr.length * nodeH - (arr.length - 1) * rowGap) / 2
      arr.forEach((t, i) => {
        pos.set(t, {
          x: pad + l * colGap,
          y: startY + i * (nodeH + rowGap) + nodeH / 2 + yCursor
        })
      })
    })
    yCursor += compH + compGap
  })

  return {
    pos,
    width: pad * 2 + maxLayers * colGap,
    height: Math.max(160, yCursor + pad - compGap)
  }
}

/**
 * 线段与节点矩形边界的交点：箭头要落在节点边框上，否则会被节点盖住。
 */
export function clipSegmentToBox(
  from: GraphPoint,
  to: GraphPoint,
  w: number,
  h: number
): GraphPoint {
  const dx = to.x - from.x
  const dy = to.y - from.y
  if (dx === 0 && dy === 0) return to
  const sx = dx === 0 ? Number.POSITIVE_INFINITY : w / 2 / Math.abs(dx)
  const sy = dy === 0 ? Number.POSITIVE_INFINITY : h / 2 / Math.abs(dy)
  const s = Math.min(sx, sy)
  return { x: from.x + dx * s, y: from.y + dy * s }
}
