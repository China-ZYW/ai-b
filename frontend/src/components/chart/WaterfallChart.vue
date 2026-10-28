<script setup lang="ts">
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 瀑布图（通用）：从「基期水位」出发，经若干正负增量段，精确落到「本期水位」。
 *
 * <p><b>实现原理</b>：ECharts 没有原生瀑布图，用「透明占位柱 + 可见值柱」双系列同 stack 模拟——
 * 占位柱负责把可见柱顶到正确的起始高度，自身不着色。这样无需手算坐标，且缩放/响应式全部由 ECharts 托管。
 *
 * <p><b>配色遵循中国市场约定</b>：<b>正向拉动用红色、负向拖累用绿色</b>（与国内行情软件一致，
 * 与欧美相反）。水位柱（基期/本期）用中性蓝，避免与"涨跌"语义混淆。
 *
 * <p><b>数值语义</b>：{@code steps} 传<b>带符号的增量</b>（正=拉动增长、负=拖累下降），
 * 组件内部取绝对值作柱高，但标签/tooltip 仍显示原始带符号值，避免"绿色柱子却写着正数"的误读。
 */
const props = withDefaults(
  defineProps<{
    /** 起点水位名称（如"基期"/"上月"）。 */
    baseName?: string
    /** 起点水位数值。 */
    baseValue?: number | null
    /** 终点水位名称（如"本期"/"本月"）。 */
    totalName?: string
    /** 终点水位数值（理论上等于 baseValue + 所有增量之和）。 */
    totalValue?: number | null
    /** 中间增量段（带符号）。 */
    steps?: { name: string; value: number }[] | null
    /** 图高（px）。 */
    height?: number
  }>(),
  { baseName: '基期', totalName: '本期', height: 320 }
)

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

/** 正向（拉动增长）：红；负向（拖累下降）：绿；水位：中性蓝。中国市场涨红跌绿。 */
const COLOR_UP = '#e34d59'
const COLOR_DOWN = '#00b42a'
const COLOR_NEUTRAL = '#4080ff'

/** 读取 Element Plus 主题变量，使图表在明/暗主题下都可读。 */
function themeColor(varName: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(varName).trim()
  return v || fallback
}

/** 大数缩写（亿/万），避免长串数字把坐标轴撑爆。 */
function formatNum(n?: number | null): string {
  if (n === null || n === undefined || Number.isNaN(n)) return '—'
  const abs = Math.abs(n)
  if (abs >= 1e8) return (n / 1e8).toFixed(2) + '亿'
  if (abs >= 1e4) return (n / 1e4).toFixed(2) + '万'
  return n.toFixed(Math.abs(n) < 1 && n !== 0 ? 2 : 0)
}

function buildOption(): EChartsOption {
  const textColor = themeColor('--el-text-color-regular', '#606266')
  const splitColor = themeColor('--el-border-color-lighter', '#ebeef5')

  const base = props.baseValue ?? 0
  const steps = props.steps ?? []
  const categories: string[] = [props.baseName]
  // 占位柱（透明）：把可见柱"垫"到正确起始高度
  const placeholders: number[] = [0]
  const values: Record<string, unknown>[] = [
    { value: base, raw: base, itemStyle: { color: COLOR_NEUTRAL } }
  ]

  let running = base
  for (const step of steps) {
    const delta = step.value ?? 0
    categories.push(step.name)
    if (delta >= 0) {
      placeholders.push(running)
      values.push({ value: delta, raw: delta, itemStyle: { color: COLOR_UP } })
    } else {
      // 下降段：占位降到"终点高度"，可见柱从那里向上画，视觉上表现为向下走
      placeholders.push(running + delta)
      values.push({ value: Math.abs(delta), raw: delta, itemStyle: { color: COLOR_DOWN } })
    }
    running += delta
  }
  categories.push(props.totalName)
  placeholders.push(0)
  const total = props.totalValue ?? running
  values.push({ value: total, raw: total, itemStyle: { color: COLOR_NEUTRAL } })

  return {
    grid: { left: 12, right: 16, top: 28, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (p: any) => {
        const arr = Array.isArray(p) ? p : [p]
        const item = arr.find((x: any) => x.seriesName === 'value') ?? arr[0]
        const raw = item?.data?.raw
        return `${item?.name ?? ''}<br/><b>${formatNum(raw)}</b>`
      }
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { color: textColor, interval: 0, hideOverlap: true },
      axisLine: { lineStyle: { color: splitColor } }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: textColor, formatter: (v: number) => formatNum(v) },
      splitLine: { lineStyle: { color: splitColor } }
    },
    series: [
      {
        name: 'placeholder',
        type: 'bar',
        stack: 'waterfall',
        silent: true,
        itemStyle: { color: 'transparent' },
        emphasis: { itemStyle: { color: 'transparent' } },
        data: placeholders
      },
      {
        name: 'value',
        type: 'bar',
        stack: 'waterfall',
        barMaxWidth: 46,
        label: {
          show: true,
          position: 'top',
          color: textColor,
          formatter: (p: any) => formatNum(p?.data?.raw)
        },
        data: values as any
      }
    ]
  } as EChartsOption
}

function render(): void {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

function onResize(): void {
  chart?.resize()
}

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  render()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})

// 数据变化即重绘；notMerge=true 保证切换维度/基准时不会残留上一份系列
watch(() => [props.baseValue, props.totalValue, props.steps, props.baseName, props.totalName], render, {
  deep: true
})
</script>

<template>
  <div ref="el" class="waterfall-chart" :style="{ height: height + 'px' }" />
</template>

<style scoped>
.waterfall-chart {
  width: 100%;
}
</style>
