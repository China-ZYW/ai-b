<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { ensureChinaMap, isChinaMapRegistered, optionNeedsChinaMap } from '@/composables/chartGeo'

/**
 * ECharts 通用容器：负责实例的初始化 / 更新 / 自适应 resize / 销毁。
 * 用 shallowRef 持有实例，避免 ECharts 实例被深度响应式代理（性能与告警问题）。
 *
 * <p>地图（MAP）：option 含 map series 时需先异步注册 geoJSON（CDN），未就绪先显示 loading；
 * 加载失败则 emit {@code fallback} 让调用方回落明细表，绝不抛错。
 */
const props = withDefaults(
  defineProps<{
    option: EChartsOption | null
    height?: string
    loading?: boolean
    /** L2 图表增强开关（主题令牌 flow）：折线霓虹发光 + 各系列错峰生长。 */
    flow?: boolean
  }>(),
  { height: '360px', loading: false, flow: false }
)

const emit = defineEmits<{
  (e: 'fallback', reason: string): void
  /** V4.44 联动下钻：把点击的图形点转译为 {name, seriesName, value, componentType}，由 WidgetCard 映射为维度+成员值。 */
  (e: 'point-click', payload: { name?: string | number; seriesName?: string; value?: unknown; componentType?: string }): void
}>()

const el = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
/** 地图 geoJSON 加载失败标志：展示空态并通知调用方回落表格。 */
const mapFailed = ref(false)
let resizeObserver: ResizeObserver | null = null
/** 渲染序号：避免异步地图加载期间 option 变更导致的陈旧渲染覆盖。 */
let renderSeq = 0
/** 上一次 setOption 的图表结构签名：同结构（图型/轴类型一致）时走 merge 做刷新过渡动画。 */
let prevSig = ''

/** 图表结构签名：series 类型 + x/y 轴类型。结构不变即认为同一图表，可 merge 平滑过渡。 */
function structSig(opt: EChartsOption | null): string {
  if (!opt) return ''
  const o = opt as Record<string, unknown>
  const s = Array.isArray(o.series) ? o.series : o.series ? [o.series] : []
  const types = (s as Array<Record<string, unknown>>)
    .map((x) => (x && typeof x === 'object' ? ((x as { type?: string }).type ?? '') : ''))
    .join(',')
  const axisType = (a: unknown): string => {
    if (Array.isArray(a)) return (a as Array<{ type?: string }>).map((x) => x?.type ?? '').join(',')
    return (a as { type?: string } | undefined)?.type ?? ''
  }
  return `${types}|${axisType(o.xAxis)}|${axisType(o.yAxis)}`
}

/**
 * L2 图表视觉增强（主题令牌 flow 驱动）：折线加粗并附带霓虹外发光、显示数据点，
 * 各系列 / 各数据项错峰入场。纯视觉叠加，不改动任何数据与图型语义。
 */
function enhanceFlow(opt: EChartsOption): EChartsOption {
  const src = opt as Record<string, unknown>
  const palette = Array.isArray(src.color) ? (src.color as string[]) : []
  const main = palette[0] || '#00c2ff'
  const raw = Array.isArray(src.series) ? src.series : src.series ? [src.series] : []
  const series = (raw as Array<Record<string, unknown>>).map((s, si) => {
    const out: Record<string, unknown> = { ...s }
    const type = (s?.type as string) || ''
    if (type === 'line') {
      const ls = (s.lineStyle as Record<string, unknown>) ?? {}
      out.lineStyle = { ...ls, width: (ls.width as number) ?? 2.4, shadowBlur: 14, shadowColor: main }
      out.showSymbol = true
      out.symbolSize = (s.symbolSize as number) ?? 5
    }
    // 错峰延迟：系列之间 120ms、同系列数据项之间 26ms
    out.animationDelay = (idx: number): number => si * 120 + idx * 26
    return out
  })
  return { ...opt, series } as unknown as EChartsOption
}

async function render(): Promise<void> {
  if (!el.value) return
  if (!chart.value) {
    chart.value = echarts.init(el.value)
  }
  const seq = ++renderSeq
  if (!props.option) {
    mapFailed.value = false
    chart.value.clear()
    return
  }
  // 地图需先异步注册 geoJSON；未就绪时先 loading，成功后再渲染，失败则回落
  if (optionNeedsChinaMap(props.option) && !isChinaMapRegistered()) {
    mapFailed.value = false
    chart.value.showLoading('default', { color: '#002FA7', textColor: '#002FA7', maskColor: 'rgba(255,255,255,0.7)' })
    const ok = await ensureChinaMap()
    if (seq !== renderSeq) return
    if (!ok) {
      chart.value.hideLoading()
      chart.value.clear()
      mapFailed.value = true
      emit('fallback', '地图数据加载失败，已回落明细表')
      return
    }
  }
  if (seq !== renderSeq) return
  const sig = structSig(props.option)
  // 同结构（图型/轴类型一致）走 merge：数据刷新时平滑过渡（大屏自动刷新不再整图重绘闪烁）；
  // 结构变化才 notMerge 重建，避免图型切换时旧 series 残留。
  const useMerge = sig !== '' && sig === prevSig
  const animOpt: EChartsOption = {
    ...props.option,
    animation: true,
    animationDuration: 900,
    animationEasing: 'cubicOut',
    animationDurationUpdate: 700,
    animationEasingUpdate: 'cubicInOut'
  }
  // L2：flow 令牌开启时叠加霓虹发光与错峰生长（纯视觉增强，不动数据）
  const finalOpt: EChartsOption = props.flow ? enhanceFlow(animOpt) : animOpt
  chart.value.setOption(finalOpt, !useMerge)
  if (sig) prevSig = sig
  chart.value.hideLoading()
  if (props.loading) {
    chart.value.showLoading('default', { color: '#002FA7', textColor: '#002FA7', maskColor: 'rgba(255,255,255,0.7)' })
  }
}

function resize(): void {
  chart.value?.resize()
}

/** 把 ECharts 点击的图形点转译为结构化 payload 上抛（维度/成员值的语义映射交给 WidgetCard）。 */
function onPointClick(params: {
  name?: string | number
  seriesName?: string
  value?: unknown
  componentType?: string
}): void {
  emit('point-click', {
    name: params?.name,
    seriesName: params?.seriesName,
    value: params?.value,
    componentType: params?.componentType
  })
}

onMounted(async () => {
  await nextTick()
  await render()
  // 点击下钻：实例只初始化一次，点击事件监听器也只挂一次（render 内可能重绘但不重建实例）
  chart.value?.on('click', onPointClick as (params: unknown) => void)
  if (el.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => resize())
    resizeObserver.observe(el.value)
  }
  window.addEventListener('resize', resize)
})

watch(() => props.option, () => void render(), { deep: true })
watch(() => props.loading, () => void render())

onBeforeUnmount(() => {
  renderSeq++
  window.removeEventListener('resize', resize)
  resizeObserver?.disconnect()
  resizeObserver = null
  chart.value?.dispose()
  chart.value = null
})

/**
 * 导出图表为图片 DataURL（供「下载图表」用）。
 *
 * <p>默认 PNG + 2 倍像素比；{@code backgroundColor} 默认白底——Canvas 默认透明，
 * 直接放进文档/PPT 深色主题下会看不清。
 */
function getDataURL(type: 'png' | 'jpeg' = 'png', backgroundColor = '#ffffff'): string | null {
  if (!chart.value) return null
  try {
    return chart.value.getDataURL({ type, pixelRatio: 2, backgroundColor })
  } catch {
    return null
  }
}

defineExpose({ resize, getDataURL })
</script>

<template>
  <div class="base-chart">
    <div v-show="option && !mapFailed" ref="el" class="chart-canvas" :style="{ height }" />
    <div v-if="!option || mapFailed" class="chart-empty" :style="{ height }">
      <el-icon :size="34"><PieChart /></el-icon>
      <p>{{ mapFailed ? '地图数据加载失败，请查看下方明细表' : '该结果不适合图表展示，请查看下方明细表' }}</p>
    </div>
  </div>
</template>

<style scoped>
.base-chart {
  width: 100%;
}
.chart-canvas {
  width: 100%;
}
.chart-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-placeholder);
  background: var(--ikb-ghost);
  border-radius: var(--radius-md);
}
.chart-empty p {
  margin: 0;
  font-size: 13px;
}
</style>
