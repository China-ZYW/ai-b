<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { RelationSuggestion, TableRelationDef } from '@/types'
import { usePerm } from '@/composables/usePerm'
import {
  GRAPH_NODE_H,
  GRAPH_PAD,
  clipSegmentToBox,
  computeRelationLayout
} from '@/utils/relationGraphLayout'

/**
 * 表关联图谱画布（自绘 SVG，不引第三方图库）。
 *
 * 为什么自绘：关联图谱的读法与"看图"不同——重点是"哪张表挂在哪张表上、用哪对键、基数是什么"，
 * 点节点/连线要能带回具体的登记行直达编辑；通用图组件只给得出图形点，且布局是它们的内部黑盒。
 * 这里依赖更少、行为可控，几十到上百个节点的性能也毫无压力。
 *
 * 布局：有向分层 + 连通分量垂直堆叠。分层沿用登记语义——边从「多方」指向「一方」，
 * 层号 = 上游层号 + 1，于是"明细表在左、维表在右"的自然读法成立；含环（自引用/环形外键）的
 * 子集靠轮次上限收敛，不会死循环。分量之间垂直堆叠，避免两张互不相关的事实表挤在同一列上。
 */
const props = withDefaults(
  defineProps<{
    relations: TableRelationDef[]
    /** 系统推荐的关联，可选一起画（虚线）。 */
    suggestions?: RelationSuggestion[]
    showSuggestions?: boolean
    /** 隐藏已停用的登记：停用不参与问数，画上去会让"图上有的"与"问数用到的"对不上。 */
    onlyEnabled?: boolean
    height?: string
  }>(),
  { suggestions: () => [], showSuggestions: false, onlyEnabled: true, height: '520px' }
)

const emit = defineEmits<{
  (e: 'edit-relation', r: TableRelationDef): void
  (e: 'remove-relation', r: TableRelationDef): void
}>()

const { can } = usePerm()

/* ---------------- 图数据 ---------------- */
interface GraphEdge {
  key: string
  kind: 'manual' | 'suggested'
  source: string
  target: string
  sourceColumn: string
  targetColumn: string
  relationType: string | null
  description: string | null
  enabled: boolean
  relation: TableRelationDef | null
}

interface GraphNode {
  key: string
  label: string
  /** 完整表名：<title> 与"未接入的表"列表用。 */
  fullName: string
  x: number
  y: number
  w: number
  /** 是否被手动拖过（重排时保留其位置）。 */
  pinned: boolean
}

const at = (v: string | null | undefined): string => (v ?? '').trim()

const edges = computed<GraphEdge[]>(() => {
  const out: GraphEdge[] = []
  const manual = (props.relations ?? []).filter((r) => (props.onlyEnabled ? (r.enabled ?? 1) === 1 : true))
  manual.forEach((r, i) => {
    const source = at(r.leftTable)
    const target = at(r.rightTable)
    if (!source || !target) return
    out.push({
      key: `m${i}:${source}.${at(r.leftColumn)}->${target}.${at(r.rightColumn)}`,
      kind: 'manual',
      source,
      target,
      sourceColumn: at(r.leftColumn),
      targetColumn: at(r.rightColumn),
      relationType: r.relationType ?? null,
      description: r.description ?? null,
      enabled: (r.enabled ?? 1) === 1,
      relation: r
    })
  })
  if (props.showSuggestions) {
    ;(props.suggestions ?? []).forEach((s, i) => {
      const source = at(s.leftTable)
      const target = at(s.rightTable)
      if (!source || !target) return
      out.push({
        key: `s${i}:${source}.${at(s.leftColumn)}->${target}.${at(s.rightColumn)}`,
        kind: 'suggested',
        source,
        target,
        sourceColumn: at(s.leftColumn),
        targetColumn: at(s.rightColumn),
        relationType: s.relationType ?? null,
        description: null,
        enabled: true,
        relation: null
      })
    })
  }
  return out
})

/** 表名去重（按首次出现拼写排序）。大小写不同视为不同表，与后端按表名匹配一致。 */
const tableNames = computed<string[]>(() => {
  const seen = new Set<string>()
  edges.value.forEach((e) => {
    seen.add(e.source)
    seen.add(e.target)
  })
  return Array.from(seen).sort((a, b) => a.localeCompare(b))
})

/* ---------------- 布局 ---------------- */
/**
 * 布局算法本体在 {@link @/utils/relationGraphLayout}（纯函数、可独立验证）；组件只把它算出的
 * 坐标渲染成 SVG，再把用户手动拖动过的位置覆盖上去。
 *
 * NODE_H 必须由工具模块提供，别在本文件另写一份常量——渲染用它、箭头裁剪也用它，
 * 两边不一致时箭头会扎进节点里。
 */
const NODE_H = GRAPH_NODE_H

/** 估算 SVG 文本宽（中日韩字符约为西文两倍）。 */
function measure(text: string): number {
  let w = 0
  for (const ch of text) w += ch.charCodeAt(0) > 127 ? 13.4 : 7.4
  return w
}

function truncate(text: string, maxPx: number): string {
  if (measure(text) <= maxPx) return text
  let out = ''
  let w = 0
  for (const ch of text) {
    const cw = ch.charCodeAt(0) > 127 ? 13.4 : 7.4
    if (w + cw > maxPx - 16) break
    out += ch
    w += cw
  }
  return `${out}…`
}


const nodes = ref<GraphNode[]>([])
const canvasSize = ref({ w: 800, h: 400 })
/** 用户手动拖过的位置；重排不清，只有「重排」按钮才清。 */
const pinnedPos = new Map<string, { x: number; y: number }>()

function relayout(): void {
  const links = edges.value.map((e) => ({ source: e.source, target: e.target }))
  const { pos, width, height } = computeRelationLayout(tableNames.value, links)
  canvasSize.value = { w: width, h: height }
  nodes.value = tableNames.value.map((name) => {
    const fallback = { x: GRAPH_PAD, y: GRAPH_PAD }
    const p = pinnedPos.get(name) ?? pos.get(name) ?? fallback
    return {
      key: name,
      label: truncate(name, 196),
      fullName: name,
      x: p.x,
      y: p.y,
      w: Math.max(112, Math.min(212, measure(name) + 30)),
      pinned: pinnedPos.has(name)
    }
  })
}

watch(
  () => [props.relations, props.suggestions, props.showSuggestions, props.onlyEnabled],
  () => relayout(),
  { deep: true, immediate: true }
)

/* ---------------- 选中态 ---------------- */
const selectedTable = ref<string | null>(null)
const selectedEdgeKey = ref<string | null>(null)

function nodeClasses(n: GraphNode): Record<string, boolean> {
  const touchesActiveEdge = edges.value.some(
    (e) => e.key === selectedEdgeKey.value && (e.source === n.key || e.target === n.key)
  )
  return {
    'is-selected': selectedTable.value === n.key,
    'is-dim': !!selectedEdgeKey.value && !touchesActiveEdge,
    'is-linked': !!selectedTable.value && selectedTable.value !== n.key && touchesActiveEdge,
    // 手动拖过的位置：给点视觉区分，否则分不清哪些是算法排的、哪些是自己挪的
    'is-pinned': n.pinned
  }
}

function edgeClass(e: GraphEdge): Record<string, boolean> {
  const active = e.key === selectedEdgeKey.value
  const touches = !!selectedTable.value && (e.source === selectedTable.value || e.target === selectedTable.value)
  return {
    'is-suggested': e.kind === 'suggested',
    'is-m2m': e.kind === 'manual' && e.relationType === 'MANY_TO_MANY',
    'is-disabled': e.kind === 'manual' && !e.enabled,
    'is-active': active,
    'is-dim': (!!selectedTable.value && !touches) || (!!selectedEdgeKey.value && !active)
  }
}

function markerOf(e: GraphEdge): string {
  const base =
    e.kind === 'suggested' ? 'rg-arrow-sug' : e.relationType === 'MANY_TO_MANY' ? 'rg-arrow-m2m' : 'rg-arrow'
  return e.key === selectedEdgeKey.value ? `${base}-on` : base
}

const typeText = (t: string | null): string =>
  t === 'MANY_TO_ONE' ? '多对一' : t === 'ONE_TO_ONE' ? '一对一' : t === 'MANY_TO_MANY' ? '多对多' : '待确认'

/* ---------------- 几何 ---------------- */
interface Pt {
  x: number
  y: number
}

/** 线段与节点矩形边界的交点：箭头要落在边框上，否则会被节点盖住。 */

interface EdgeGfx {
  edge: GraphEdge
  x1: number
  y1: number
  x2: number
  y2: number
}

/** 预计算每条边的两端坐标：模板里渲染一次、点选时复用同一份，避免每次重算。 */
const edgeGfx = computed<EdgeGfx[]>(() => {
  const out: EdgeGfx[] = []
  edges.value.forEach((e) => {
    const s = nodes.value.find((n) => n.key === e.source)
    const t = nodes.value.find((n) => n.key === e.target)
    if (!s || !t) return
    const sc = clipSegmentToBox({ x: s.x, y: s.y }, { x: t.x, y: t.y }, s.w, NODE_H)
    const tc = clipSegmentToBox({ x: t.x, y: t.y }, { x: s.x, y: s.y }, t.w, NODE_H)
    out.push({ edge: e, x1: sc.x, y1: sc.y, x2: tc.x, y2: tc.y })
  })
  return out
})

const MARKERS: { id: string; fill: string }[] = [
  { id: 'rg-arrow', fill: 'var(--text-secondary)' },
  { id: 'rg-arrow-on', fill: 'var(--ikb)' },
  { id: 'rg-arrow-sug', fill: 'var(--warning)' },
  { id: 'rg-arrow-sug-on', fill: 'var(--ikb)' },
  { id: 'rg-arrow-m2m', fill: 'var(--danger)' },
  { id: 'rg-arrow-m2m-on', fill: 'var(--ikb)' }
]

/* ---------------- 交互 ---------------- */
const wrapEl = ref<HTMLDivElement | null>(null)
const zoom = ref(1)
const tx = ref(0)
const ty = ref(0)
const panning = ref(false)

const rootTransform = computed(() => `translate(${tx.value},${ty.value}) scale(${zoom.value})`)

function fit(): void {
  const el = wrapEl.value
  if (!el) return
  const cw = el.clientWidth
  const ch = el.clientHeight
  if (!cw || !ch) return
  const raw = Math.min((cw - 28) / canvasSize.value.w, (ch - 28) / canvasSize.value.h)
  const k = Number.isFinite(raw) && raw > 0 ? raw : 1
  zoom.value = Math.max(0.25, Math.min(1.6, Math.min(1, k)))
  tx.value = (cw - canvasSize.value.w * zoom.value) / 2
  ty.value = (ch - canvasSize.value.h * zoom.value) / 2
}

function zoomAt(px: number, py: number, factor: number): void {
  const nk = Math.min(2.4, Math.max(0.25, zoom.value * factor))
  if (nk === zoom.value) return
  tx.value = px - (px - tx.value) * (nk / zoom.value)
  ty.value = py - (py - ty.value) * (nk / zoom.value)
  zoom.value = nk
}

function onWheel(ev: WheelEvent): void {
  const el = wrapEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  zoomAt(ev.clientX - rect.left, ev.clientY - rect.top, ev.deltaY > 0 ? 0.9 : 1.1)
}

function zoomBy(factor: number): void {
  const el = wrapEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  zoomAt(rect.width / 2, rect.height / 2, factor)
}

/** 容器坐标 → 画布坐标。 */
function toGraph(clientX: number, clientY: number): Pt {
  const rect = (wrapEl.value as HTMLDivElement).getBoundingClientRect()
  return { x: (clientX - rect.left - tx.value) / zoom.value, y: (clientY - rect.top - ty.value) / zoom.value }
}

/* 节点拖拽：拖完即钉住，方便按自己的思路摆位置。 */
let dragKey: string | null = null
let dragOffset: Pt = { x: 0, y: 0 }

function onNodeDown(ev: PointerEvent, n: GraphNode): void {
  ev.stopPropagation()
  selectedTable.value = n.key
  selectedEdgeKey.value = null
  const g = toGraph(ev.clientX, ev.clientY)
  dragKey = n.key
  dragOffset = { x: g.x - n.x, y: g.y - n.y }
  ;(ev.currentTarget as SVGElement).setPointerCapture?.(ev.pointerId)
}

function onNodeMove(ev: PointerEvent): void {
  if (!dragKey) return
  const n = nodes.value.find((x) => x.key === dragKey)
  if (!n) return
  const g = toGraph(ev.clientX, ev.clientY)
  n.x = Math.round(g.x - dragOffset.x)
  n.y = Math.round(g.y - dragOffset.y)
}

function onNodeUp(): void {
  if (!dragKey) return
  const n = nodes.value.find((x) => x.key === dragKey)
  if (n) {
    pinnedPos.set(n.key, { x: n.x, y: n.y })
    n.pinned = true
  }
  dragKey = null
}

/** 空白处拖动平移。 */
function onBgDown(ev: PointerEvent): void {
  panning.value = true
  const start = { x: ev.clientX, y: ev.clientY }
  const base = { x: tx.value, y: ty.value }
  const move = (e: PointerEvent): void => {
    tx.value = base.x + (e.clientX - start.x)
    ty.value = base.y + (e.clientY - start.y)
  }
  const up = (): void => {
    panning.value = false
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

function selectNode(n: GraphNode): void {
  selectedTable.value = n.key
  selectedEdgeKey.value = null
}

function selectEdge(e: GraphEdge): void {
  selectedEdgeKey.value = e.key
  selectedTable.value = null
}

/** 侧栏点表名：把该节点挪到视野中央，省得在大图里找。 */
function selectTableFromSide(name: string): void {
  selectedTable.value = name
  selectedEdgeKey.value = null
  const n = nodes.value.find((x) => x.key === name)
  if (!n || !wrapEl.value) return
  const rect = wrapEl.value.getBoundingClientRect()
  tx.value = rect.width / 2 - n.x * zoom.value
  ty.value = rect.height / 2 - n.y * zoom.value
}

function resetLayout(): void {
  pinnedPos.clear()
  relayout()
}

function clearSelection(): void {
  selectedTable.value = null
  selectedEdgeKey.value = null
}

function editRelation(e: GraphEdge): void {
  if (e.relation) emit('edit-relation', e.relation)
}

function removeRelation(e: GraphEdge): void {
  if (e.relation) emit('remove-relation', e.relation)
}

/* ---------------- 侧栏 ---------------- */
const selectedNode = computed<GraphNode | null>(
  () => nodes.value.find((n) => n.key === selectedTable.value) ?? null
)
const selectedEdge = computed<GraphEdge | null>(
  () => edges.value.find((e) => e.key === selectedEdgeKey.value) ?? null
)
const nodeEdges = computed<GraphEdge[]>(() => {
  if (!selectedTable.value) return []
  const key = selectedTable.value
  return edges.value.filter((e) => e.source === key || e.target === key)
})
/** 未接入任何关联的表：多表问数里的孤岛，最容易在选表时被漏掉。 */
const orphanTables = computed<string[]>(() => {
  const linked = new Set<string>()
  edges.value.forEach((e) => {
    linked.add(e.source)
    linked.add(e.target)
  })
  return tableNames.value.filter((t) => !linked.has(t))
})
const stat = computed(() => ({
  tables: tableNames.value.length,
  links: edges.value.filter((e) => e.kind === 'manual').length,
  suggest: edges.value.filter((e) => e.kind === 'suggested').length
}))

/* ---------------- 尺寸自适应 ---------------- */
let ro: ResizeObserver | null = null

onMounted(async () => {
  await nextTick()
  await fit()
  // el-dialog 首次打开前容器宽度为 0，fit 会算出错误缩放；之后每次尺寸变化都重新适配
  if (typeof ResizeObserver !== 'undefined' && wrapEl.value) {
    let lastW = wrapEl.value.clientWidth
    ro = new ResizeObserver(() => {
      const w = wrapEl.value?.clientWidth ?? 0
      if (!w || w === lastW) return
      lastW = w
      fit()
    })
    ro.observe(wrapEl.value)
  }
})

onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
})

watch(canvasSize, async () => {
  await nextTick()
  fit()
})

defineExpose({ fit })
</script>

<template>
  <div class="rg-wrap" :style="{ height }">
    <div class="rg-main">
      <div
        ref="wrapEl"
        class="rg-canvas"
        :class="{ 'is-panning': panning }"
        @pointerdown="onBgDown"
        @wheel.prevent="onWheel"
      >
        <svg class="rg-svg" :width="canvasSize.w" :height="canvasSize.h">
          <defs>
            <marker
              v-for="m in MARKERS"
              :id="m.id"
              :key="m.id"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="7"
              markerHeight="7"
              orient="auto-start-reverse"
            >
              <path d="M0,0 L10,5 L0,10 Z" :style="{ fill: m.fill }" />
            </marker>
          </defs>

          <g :transform="rootTransform">
            <g
              v-for="g in edgeGfx"
              :key="g.edge.key"
              class="rg-edge-group"
              :class="edgeClass(g.edge)"
              @click.stop="selectEdge(g.edge)"
            >
              <line class="rg-edge-hit" :x1="g.x1" :y1="g.y1" :x2="g.x2" :y2="g.y2" />
              <line
                class="rg-edge"
                :x1="g.x1"
                :y1="g.y1"
                :x2="g.x2"
                :y2="g.y2"
                :marker-end="`url(#${markerOf(g.edge)})`"
              />
              <text
                v-if="g.edge.kind === 'manual'"
                class="rg-edge-label"
                :x="(g.x1 + g.x2) / 2"
                :y="(g.y1 + g.y2) / 2 - 5"
                text-anchor="middle"
              >
                {{ typeText(g.edge.relationType) }}
              </text>
            </g>

            <g
              v-for="n in nodes"
              :key="n.key"
              class="rg-node"
              :class="nodeClasses(n)"
              @pointerdown="onNodeDown($event, n)"
              @pointermove="onNodeMove"
              @pointerup="onNodeUp"
              @click.stop="selectNode(n)"
            >
              <title>{{ n.fullName }}</title>
              <rect
                class="rg-node-box"
                :x="n.x - n.w / 2"
                :y="n.y - NODE_H / 2"
                :width="n.w"
                :height="NODE_H"
                rx="9"
              />
              <text class="rg-node-text" :x="n.x" :y="n.y + 4" text-anchor="middle">{{ n.label }}</text>
              <circle v-if="n.pinned" class="rg-node-pin" :cx="n.x + n.w / 2 - 7" :cy="n.y - NODE_H / 2 + 7" r="2.5" />
            </g>
          </g>
        </svg>

        <div v-if="!nodes.length" class="rg-empty">
          <el-icon :size="30"><Connection /></el-icon>
          <p>
            {{ onlyEnabled ? '没有可显示的连接：先「新增登记」，或打开「显示推荐」看系统推断的候选' : '暂无关联' }}
          </p>
        </div>

        <div class="rg-zoombar">
          <el-button size="small" text @click.stop="zoomBy(1.18)">＋</el-button>
          <el-button size="small" text @click.stop="zoomBy(0.85)">－</el-button>
          <el-button size="small" text @click.stop="fit">适应</el-button>
          <el-button size="small" text @click.stop="resetLayout">重排</el-button>
        </div>
      </div>

      <aside class="rg-side">
        <div class="rg-side-head">
          <span class="rg-side-title">关联详情</span>
          <el-button
            size="small"
            text
            :disabled="!selectedTable && !selectedEdgeKey"
            @click="clearSelection"
          >
            清除
          </el-button>
        </div>

        <div v-if="selectedEdge" class="rg-block">
          <div class="rg-kv-row"><span class="rg-k">连接键</span></div>
          <div class="mono rg-mono">
            {{ selectedEdge.source }}.{{ selectedEdge.sourceColumn }}
            <span class="rg-eq">=</span>
            {{ selectedEdge.target }}.{{ selectedEdge.targetColumn }}
          </div>
          <div class="rg-kv-row">
            <span class="rg-k">基数</span>
            <el-tag
              size="small"
              effect="light"
              round
              :type="selectedEdge.kind === 'suggested' ? 'warning' : 'primary'"
            >
              {{ typeText(selectedEdge.relationType) }}
            </el-tag>
          </div>
          <div v-if="selectedEdge.kind === 'suggested'" class="rg-note">
            系统推荐（尚未登记），采纳后以人工口径覆盖
          </div>
          <div v-if="selectedEdge.description" class="rg-note">口径说明：{{ selectedEdge.description }}</div>
          <div v-if="selectedEdge.relation" class="rg-ops">
            <el-button
              v-if="can('semantic:meta:write')"
              size="small"
              text
              type="primary"
              @click="editRelation(selectedEdge)"
            >
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button
              v-if="can('semantic:meta:delete')"
              size="small"
              text
              type="danger"
              @click="removeRelation(selectedEdge)"
            >
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </div>
        </div>

        <div v-else-if="selectedNode" class="rg-block">
          <div class="rg-kv-row">
            <span class="rg-k">表</span>
            <span class="mono rg-mono">{{ selectedNode.fullName }}</span>
          </div>
          <div class="rg-kv-row">
            <span class="rg-k">关联</span>
            <span>{{ nodeEdges.length }} 条</span>
          </div>
          <div v-if="!nodeEdges.length" class="rg-note">
            这张表还没登记任何关联——多表问数时它只能单独查，或靠外键/命名推断兜底。
          </div>
          <ul v-else class="rg-list">
            <li v-for="e in nodeEdges" :key="e.key" :class="{ 'is-active': e.key === selectedEdgeKey }">
              <div class="mono rg-mono-sm">
                {{ e.source }}.{{ e.sourceColumn }} = {{ e.target }}.{{ e.targetColumn }}
              </div>
              <div class="rg-sub">
                <el-tag
                  size="small"
                  effect="plain"
                  round
                  :type="e.kind === 'suggested' ? 'warning' : 'primary'"
                >
                  {{ typeText(e.relationType) }}
                </el-tag>
                <el-button
                  v-if="e.kind === 'manual' && e.relation && can('semantic:meta:write')"
                  size="small"
                  text
                  type="primary"
                  @click="editRelation(e)"
                >
                  编辑
                </el-button>
              </div>
            </li>
          </ul>
        </div>

        <div v-else class="rg-block">
          <p class="rg-hint">
            点节点看它的关联、点连线看连接键；空白处拖动平移、滚轮缩放，节点可拖动钉住。
            连线方向为「多方 → 一方」，箭头落在维表一侧。
          </p>
        </div>

        <div class="rg-legend">
          <div class="rg-legend-item"><i class="dot m2o" />多对一</div>
          <div class="rg-legend-item"><i class="dot o2o" />一对一</div>
          <div class="rg-legend-item"><i class="dot m2m" />多对多</div>
          <div class="rg-legend-item"><i class="dot sug" />系统推荐（未登记）</div>
        </div>

        <div class="rg-stats">
          <span>表 {{ stat.tables }}</span>
          <span>关联 {{ stat.links }}</span>
          <span v-if="stat.suggest">推荐 {{ stat.suggest }}</span>
        </div>

        <div v-if="orphanTables.length" class="rg-block">
          <div class="rg-kv-row"><span class="rg-k">未接入的表</span><span>{{ orphanTables.length }}</span></div>
          <ul class="rg-list flat">
            <li v-for="t in orphanTables" :key="t">
              <button class="rg-link" type="button" @click="selectTableFromSide(t)">{{ truncate(t, 160) }}</button>
            </li>
          </ul>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.rg-wrap {
  width: 100%;
  min-width: 0;
}
.rg-main {
  display: flex;
  gap: 12px;
  height: 100%;
  min-height: 0;
}
.rg-canvas {
  position: relative;
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  cursor: grab;
  touch-action: none;
}
.rg-canvas.is-panning {
  cursor: grabbing;
}
.rg-svg {
  display: block;
}
.rg-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-placeholder);
  font-size: 13px;
  pointer-events: none;
}
.rg-empty p {
  margin: 0;
}
.rg-zoombar {
  position: absolute;
  right: 8px;
  bottom: 8px;
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: 8px;
  background: var(--ikb-ghost);
  border: 1px solid var(--border-light);
}

/* 连线 */
.rg-edge-hit {
  stroke: transparent;
  stroke-width: 14;
  fill: none;
  cursor: pointer;
}
.rg-edge {
  stroke: var(--text-secondary);
  stroke-width: 1.6;
  fill: none;
}
.rg-edge.is-suggested {
  stroke: var(--warning);
  stroke-dasharray: 6 4;
  opacity: 0.9;
}
.rg-edge.is-m2m {
  stroke: var(--danger);
}
.rg-edge.is-disabled {
  opacity: 0.45;
  stroke-dasharray: 3 3;
}
.rg-edge.is-active {
  stroke: var(--ikb);
  stroke-width: 3;
}
.rg-edge.is-dim {
  opacity: 0.2;
}
.rg-edge-label {
  font-size: 11px;
  fill: var(--text-secondary);
  stroke: var(--bg-card);
  stroke-width: 3px;
  paint-order: stroke;
  pointer-events: none;
}
.rg-edge-group.is-active .rg-edge-label {
  fill: var(--ikb);
  font-weight: 700;
}

/* 节点 */
.rg-node-box {
  fill: var(--ikb-ghost);
  stroke: var(--border);
  stroke-width: 1.2;
}
.rg-node-text {
  font-size: 12.5px;
  fill: var(--text-primary);
  pointer-events: none;
  user-select: none;
}
.rg-node {
  cursor: pointer;
}
.rg-node:hover .rg-node-box {
  fill: var(--ikb-pale);
  stroke: var(--ikb-lighter);
}
.rg-node.is-selected .rg-node-box {
  fill: var(--ikb-pale);
  stroke: var(--ikb);
  stroke-width: 2;
}
.rg-node.is-selected .rg-node-text {
  font-weight: 700;
}
.rg-node.is-dim {
  opacity: 0.26;
}
.rg-node.is-linked .rg-node-box {
  stroke: var(--ikb-lighter);
  stroke-dasharray: 4 3;
}
/* 手动拖过：钉住标记点（节点右上角小圆），避免与自动布局混淆 */
.rg-node.is-pinned .rg-node-box {
  stroke: var(--text-placeholder);
}
.rg-node.is-pinned .rg-node-pin {
  fill: var(--text-placeholder);
}

/* 侧栏 */
.rg-side {
  flex: 0 0 272px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  overflow: auto;
}
.rg-side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rg-side-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
}
.rg-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rg-kv-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-regular);
}
.rg-k {
  color: var(--text-secondary);
}
.rg-mono {
  font-family: var(--el-font-family, monospace);
  font-size: 12px;
  word-break: break-all;
  text-align: right;
}
.rg-mono-sm {
  font-family: var(--el-font-family, monospace);
  font-size: 11.5px;
  word-break: break-all;
}
.rg-eq {
  color: var(--text-secondary);
  margin: 0 2px;
}
.rg-note {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}
.rg-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-secondary);
}
.rg-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rg-list li {
  padding: 6px 8px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
}
.rg-list li.is-active {
  border-color: var(--ikb-light);
  background: var(--ikb-pale);
}
.rg-list.flat li {
  padding: 3px 0;
  border: none;
}
.rg-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}
.rg-ops {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.rg-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.rg-legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.dot {
  width: 12px;
  height: 3px;
  border-radius: 2px;
  background: var(--text-secondary);
  display: inline-block;
}
.dot.o2o {
  background: var(--text-placeholder);
}
.dot.m2m {
  background: var(--danger);
}
.dot.sug {
  background: repeating-linear-gradient(90deg, var(--warning) 0 5px, transparent 5px 9px);
}
.rg-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
.rg-link {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--ikb);
  cursor: pointer;
  word-break: break-all;
  font-family: var(--el-font-family, monospace);
}
.mono {
  font-family: var(--el-font-family, monospace);
}
</style>
