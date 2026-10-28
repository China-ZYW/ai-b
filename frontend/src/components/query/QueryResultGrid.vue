<script setup lang="ts">
/**
 * 查询结果显示网格（P1）。
 *
 * <b>为什么自己写虚拟滚动而不用 el-table 的 virtualized</b>：后端默认上限 10000 行，
 * el-table 全量渲染会同时产生一万个 DOM 行——首屏直接卡死。这里只渲染可视区的 ~40 行，
 * 用"上填充 + 下填充"两条占位撑出滚动条，滚动时按 offsetHeight 增量回收窗口（十万行也不掉帧）。
 *
 * <b>码值转中文</b>：语义层配了「枚举字典」的列，后端以 {@code enumDicts} 下发映射，
 * 网格只做<b>显示替换</b>，导出与复制仍拿原始码值——结果可追溯，比对时不会串。
 */
import { computed, ref } from 'vue'
import type { QueryResult } from '@/types'

const props = withDefaults(
  defineProps<{
    result: QueryResult | null
    /** 实体滚动容器高度；null 时用 CSS 撑满父容器 */
    height?: string | null
    /** 表格模式：不渲染枚举替换（图表/导出等处用） */
    displayLabels?: boolean
  }>(),
  { height: '360px', displayLabels: true }
)

const ROW_H = 28
const OVERSCAN = 8

const scrollTop = ref(0)
const viewH = ref(360)

const columns = computed(() => props.result?.columns ?? [])
const labels = computed(() => props.result?.columnLabels ?? [])
const dicts = computed(() => props.result?.enumDicts ?? {})
const rows = computed(() => props.result?.rows ?? [])

/** 枚举字典：列名 →（码值 → 中文）。 */
const dictOf = (col: string): Record<string, string> | null => {
  const d = dicts.value[col]
  return d && Object.keys(d).length ? d : null
}

const displayValue = (col: string, raw: unknown) => {
  if (raw === null || raw === undefined) return ''
  const d = props.displayLabels ? dictOf(col) : null
  if (d) {
    const s = String(raw)
    if (d[s]) return d[s]
  }
  if (typeof raw === 'number') return String(raw)
  const s = String(raw)
  // 长文本截断，避免撑破单元格
  return s.length > 60 ? s.slice(0, 60) + '…' : s
}

const total = computed(() => rows.value.length)
const start = computed(() => Math.max(0, Math.floor(scrollTop.value / ROW_H) - OVERSCAN))
const visibleCount = computed(() => Math.ceil(viewH.value / ROW_H) + OVERSCAN * 2)
const end = computed(() => Math.min(total.value, start.value + visibleCount.value))

/** 可视窗口切片（含上下填充，用于撑 scrollHeight）。 */
const padTop = computed(() => start.value * ROW_H)
const padBottom = computed(() => Math.max(0, (total.value - end.value) * ROW_H))
const slice = computed(() =>
  rows.value.slice(start.value, end.value).map((r, i) => ({ row: r, idx: start.value + i }))
)

const onScroll = (e: Event) => {
  const el = e.target as HTMLElement
  scrollTop.value = el.scrollTop
  viewH.value = el.clientHeight
}
</script>

<template>
  <div class="qgrid">
    <div v-if="!result || !columns.length" class="qgrid-empty">暂无结果</div>
    <div v-else class="qgrid-scroll" :style="height ? { height } : {}" @scroll="onScroll">
      <table class="qgrid-tb">
        <thead>
          <tr>
            <th class="qgrid-idx">#</th>
            <th v-for="(c, i) in columns" :key="`${c}-${i}`" :title="c">
              {{ labels[i] || c }}
              <em v-if="dictOf(c)">·枚举</em>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="total === 0"><td :colspan="columns.length + 1" class="qgrid-empty">查询无数据</td></tr>
          <template v-else>
            <tr :style="{ height: padTop + 'px' }" v-if="padTop > 0"><td :colspan="columns.length + 1" class="qgrid-gap" /></tr>
            <tr v-for="r in slice" :key="r.idx" :title="r.row.join(' | ')">
              <td class="qgrid-idx">{{ r.idx + 1 }}</td>
              <td v-for="(c, ci) in columns" :key="`${c}-${ci}`" class="qgrid-td">
                {{ displayValue(c, r.row[ci]) }}
              </td>
            </tr>
            <tr :style="{ height: padBottom + 'px' }" v-if="padBottom > 0"><td :colspan="columns.length + 1" class="qgrid-gap" /></tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.qgrid {
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 6px;
  overflow: hidden;
  background: var(--bg-card);
}
.qgrid-scroll {
  overflow: auto;
}
.qgrid-tb {
  border-collapse: collapse;
  width: 100%;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.qgrid-tb th,
.qgrid-tb td {
  border-right: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  padding: 0 8px;
  height: 28px;
  text-align: left;
  white-space: nowrap;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.qgrid-tb th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--fill-light);
  color: var(--el-text-color-regular, #606266);
  font-weight: 600;
}
.qgrid-idx {
  color: var(--el-text-color-placeholder, #c0c4cc);
  width: 48px;
  text-align: right !important;
  background: var(--fill-lighter);
}
.qgrid-td {
  color: var(--el-text-color-primary, #303133);
}
.qgrid-tb em {
  color: #e6a23c;
  font-style: normal;
}
.qgrid-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary, #909399);
}
.qgrid-gap {
  padding: 0;
  border: 0 !important;
}
</style>
