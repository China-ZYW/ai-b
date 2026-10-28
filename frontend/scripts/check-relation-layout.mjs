/**
 * 关联图谱布局算法验证（纯函数 utils/relationGraphLayout，直接从源码转译后执行，避免测到副本）。
 * 运行：node scripts/check-relation-layout.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import esbuild from 'esbuild'

const file = path.resolve('src/utils/relationGraphLayout.ts')
// 转译成 ESM（该模块自带 export，直接 new Function 会报 Unexpected token 'export'），走 data URL 动态 import
const js = esbuild.transformSync(fs.readFileSync(file, 'utf8'), { loader: 'ts', format: 'esm' }).code
const mod = await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`)

let failed = 0
function check(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? ` — ${extra}` : ''}`)
  if (!cond) failed += 1
}

const { computeRelationLayout, clipSegmentToBox, GRAPH_NODE_H } = mod

// 用例1：链状分层——明细表在右、维表在左，层号递增
{
  const names = ['users', 'orders', 'order_items']
  const links = [
    { source: 'orders', target: 'users' },
    { source: 'order_items', target: 'orders' }
  ]
  const { pos, width, height } = computeRelationLayout(names, links)
  check(
    '用例1 分层方向正确（users 最左、items 最右）',
    pos.get('users').x < pos.get('orders').x && pos.get('orders').x < pos.get('order_items').x,
    `x=${pos.get('users').x}/${pos.get('orders').x}/${pos.get('order_items').x}`
  )
  // users 与 orders 分属相邻两层（x 不同列），列间距必须够宽，否则两个节点框会贴上
  check(
    '用例1 相邻层列距不小于 COL_GAP',
    pos.get('orders').x - pos.get('users').x === 232,
    `x=${pos.get('users').x}->${pos.get('orders').x}`
  )
  check('用例1 画布尺寸为正', width > 0 && height > 0, `w=${width} h=${height}`)
}

// 用例2：互不相连的分量必须垂直堆叠（否则两张事实表会叠在同一列）
{
  const names = ['a1', 'a2', 'b1', 'b2']
  const links = [
    { source: 'a1', target: 'a2' },
    { source: 'b1', target: 'b2' }
  ]
  const { pos } = computeRelationLayout(names, links)
  const aRange = [Math.min(pos.get('a1').y, pos.get('a2').y), Math.max(pos.get('a1').y, pos.get('a2').y)]
  const bRange = [Math.min(pos.get('b1').y, pos.get('b2').y), Math.max(pos.get('b1').y, pos.get('b2').y)]
  check('用例2 分量垂直分离', aRange[1] < bRange[0], `a=[${aRange}] b=[${bRange}]`)
  check('用例2 分量 x 对齐', pos.get('a1').x === pos.get('b1').x)
}

// 用例3：环（自引用 + 双向外键）必须收敛，且不产生 NaN
{
  const names = ['dept', 'employee', 'audit_log']
  const links = [
    { source: 'employee', target: 'dept' },
    { source: 'dept', target: 'employee' },
    { source: 'audit_log', target: 'employee' }
  ]
  const { pos } = computeRelationLayout(names, links)
  check(
    '用例3 有环收敛且坐标有限',
    names.every((n) => Number.isFinite(pos.get(n).x) && Number.isFinite(pos.get(n).y))
  )
}

// 用例4：孤立表（没登记任何关联）也要有坐标，不能丢节点
{
  const names = ['orders', 'logs']
  const links = [{ source: 'orders', target: 'users' }]
  const { pos } = computeRelationLayout(names, links)
  check('用例4 孤立表仍有坐标', Number.isFinite(pos.get('logs').x) && Number.isFinite(pos.get('logs').y))
}

// 用例5：60 表 59 边的规模下仍快且坐标有限
{
  const names = Array.from({ length: 60 }, (_, i) => `t${String(i).padStart(3, '0')}`)
  const links = names.slice(1).map((n, i) => ({ source: n, target: names[i] }))
  const t0 = Date.now()
  const { pos } = computeRelationLayout(names, links)
  const ms = Date.now() - t0
  check(
    `用例5 60 节点布局正常（${ms}ms）`,
    names.every((n) => Number.isFinite(pos.get(n).x) && Number.isFinite(pos.get(n).y)) && ms < 500
  )
}

// 用例6：三个分量铺开后同列节点不得重叠（专盯分量纵向偏移是否漏加）
{
  const names = ['c1', 'c2', 'c3', 'c4', 'c5', 'c6']
  const links = [
    { source: 'c1', target: 'c2' },
    { source: 'c3', target: 'c4' },
    { source: 'c5', target: 'c6' }
  ]
  const { pos } = computeRelationLayout(names, links)
  const byX = new Map()
  names.forEach((n) => {
    const p = pos.get(n)
    if (!byX.has(p.x)) byX.set(p.x, [])
    byX.get(p.x).push(p.y)
  })
  let overlap = 0
  byX.forEach((ys) => {
    ys.sort((a, b) => a - b)
    for (let i = 1; i < ys.length; i += 1) if (ys[i] - ys[i - 1] < GRAPH_NODE_H) overlap += 1
  })
  check('用例6 同列节点无重叠', overlap === 0, `overlap=${overlap}`)
}

// 用例7：箭头裁剪必须落在节点边框上（而不是压在节点中心）
{
  const from = { x: 0, y: 0 }
  const to = { x: 100, y: 0 }
  const hit = clipSegmentToBox(from, to, 100, 40)
  check('用例7 水平方向裁剪到边框', Math.abs(hit.x - 50) < 1e-6 && hit.y === 0, JSON.stringify(hit))
  // 斜 45°：矩形半高 20 先被穿过，交点落在 (20,20)（x 同步按比例缩，仍是边界上的点）
  const hit2 = clipSegmentToBox({ x: 0, y: 0 }, { x: 100, y: 100 }, 100, 40)
  check(
    '用例7 斜向裁剪落在边界上',
    Math.abs(hit2.x - 20) < 1e-6 && Math.abs(hit2.y - 20) < 1e-6,
    JSON.stringify(hit2)
  )
  // 纯垂直：只能从上下边界出
  const hit3 = clipSegmentToBox({ x: 0, y: 0 }, { x: 0, y: 100 }, 100, 40)
  check('用例7 垂直裁剪落在上下边界', hit3.x === 0 && Math.abs(hit3.y - 20) < 1e-6, JSON.stringify(hit3))
  const same = clipSegmentToBox({ x: 0, y: 0 }, { x: 0, y: 0 }, 100, 40)
  check('用例7 退化线段不产生 NaN', Number.isFinite(same.x) && Number.isFinite(same.y))
}

console.log(failed === 0 ? '\nALL_PASS' : `\nFAILED=${failed}`)
process.exit(failed === 0 ? 0 : 1)
