<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { ListWidgetSpec, QueryResult } from '@/types'
import { formatNumber } from '@/utils/format'

/**
 * 列表组件（kind=list）：把绑定指标的查询结果渲染为可滚动榜单。
 * - 主/次/数值列由 spec 指定（列名取自 result.columns），留空兜底取前几列；
 * - autoScroll=true 时开启无缝循环跑马灯（内容复制一份，translateY -50% 循环）；
 * - autoScroll=false 时可手动滚动查看完整榜单。
 * 配色继承父卡片下发的大屏主题 CSS 变量（--wc-title / --wc-meta / --wc-muted / --wc-accent），
 * 因此深色/浅色与驾驶舱主题自动一致，本组件不另发请求。
 */
const props = withDefaults(
  defineProps<{
    spec?: ListWidgetSpec | null
    result?: QueryResult | null
  }>(),
  { spec: null, result: null }
)

const columns = computed(() => props.result?.columns ?? [])
const rows = computed(() => props.result?.rows ?? [])

/** 列下标解析：优先用 spec 指定字段，缺失/未命中回落到兜底下标（-1 表示不渲染该列）。 */
function colIndex(field: string | null | undefined, fallback: number): number {
  if (field) {
    const i = columns.value.indexOf(field)
    if (i >= 0) return i
  }
  return fallback < columns.value.length ? fallback : -1
}
const primaryIdx = computed(() => colIndex(props.spec?.primaryField, 0))
const secondaryIdx = computed(() => colIndex(props.spec?.secondaryField, 1))
const valueIdx = computed(() => colIndex(props.spec?.valueField, 2))

function cell(row: unknown[], i: number): string {
  if (i < 0 || i >= row.length) return ''
  const v = row[i]
  if (v === null || v === undefined) return '—'
  if (typeof v === 'number') return formatNumber(v)
  return String(v)
}

/** 榜单条目：rank 用于左侧序号。 */
const items = computed(() =>
  rows.value.map((r, ri) => ({
    key: ri,
    primary: cell(r, primaryIdx.value),
    secondary: secondaryIdx.value >= 0 ? cell(r, secondaryIdx.value) : '',
    value: valueIdx.value >= 0 ? cell(r, valueIdx.value) : ''
  }))
)

const autoScroll = computed(() => !!props.spec?.autoScroll)
const dur = computed(() => Math.max(8, Number(props.spec?.scrollSpeed) || 30))

// 溢出检测：内容高于视口才真正滚动（否则跑马灯无意义，静态展示）
const viewport = ref<HTMLElement | null>(null)
const overflow = ref(false)
function measure(): void {
  const vp = viewport.value
  if (!vp) return
  overflow.value = vp.scrollHeight > vp.clientHeight + 2
}
onMounted(() => {
  nextTick(measure)
})
watch([items, () => props.spec?.autoScroll], () => nextTick(measure), { deep: false })

const scrolling = computed(() => autoScroll.value && overflow.value)
const hasData = computed(() => items.value.length > 0)
</script>

<template>
  <div class="list-widget">
    <div v-if="!hasData" class="lw-empty">
      <el-icon :size="26"><DocumentRemove /></el-icon>
      <p>暂无数据</p>
    </div>
    <div v-else ref="viewport" class="lw-viewport" :class="{ manual: !scrolling }">
      <div class="lw-track" :class="{ scrolling }" :style="{ '--scroll-dur': dur + 's' }">
        <div class="lw-list">
          <div v-for="(it, i) in items" :key="it.key" class="lw-item">
            <span class="lw-rank">{{ i + 1 }}</span>
            <div class="lw-text">
              <div class="lw-primary truncate">{{ it.primary }}</div>
              <div v-if="it.secondary" class="lw-secondary truncate">{{ it.secondary }}</div>
            </div>
            <span v-if="it.value" class="lw-value">{{ it.value }}</span>
          </div>
        </div>
        <!-- 跑马灯无缝循环：复制一份数据，translateY -50% 回到起点 -->
        <div v-if="scrolling" class="lw-list" aria-hidden="true">
          <div v-for="(it, i) in items" :key="'dup-' + it.key" class="lw-item">
            <span class="lw-rank">{{ i + 1 }}</span>
            <div class="lw-text">
              <div class="lw-primary truncate">{{ it.primary }}</div>
              <div v-if="it.secondary" class="lw-secondary truncate">{{ it.secondary }}</div>
            </div>
            <span v-if="it.value" class="lw-value">{{ it.value }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list-widget {
  height: 100%;
  width: 100%;
  overflow: hidden;
  font-family: var(--wc-font, inherit);
}
.lw-viewport {
  height: 100%;
  overflow: hidden;
  position: relative;
}
.lw-viewport.manual {
  overflow-y: auto;
}
.lw-track {
  will-change: transform;
}
.lw-track.scrolling {
  animation: lwScroll var(--scroll-dur, 30s) linear infinite;
}
.lw-track.scrolling:hover {
  animation-play-state: paused;
}
@keyframes lwScroll {
  from {
    transform: translateY(0);
  }
  to {
    transform: translateY(-50%);
  }
}
.lw-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--wc-border, rgba(255, 255, 255, 0.08));
}
.lw-item:hover {
  background: var(--wc-accent, #2f7bff) 14;
  background: color-mix(in srgb, var(--wc-accent, #2f7bff) 12%, transparent);
}
.lw-rank {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  color: var(--wc-title, #fff);
  background: color-mix(in srgb, var(--wc-accent, #2f7bff) 22%, transparent);
}
.lw-text {
  flex: 1;
  min-width: 0;
}
.lw-primary {
  font-size: calc(13.5px * var(--wc-scale, 1));
  font-weight: 600;
  color: var(--wc-title, #fff);
  line-height: 1.35;
}
.lw-secondary {
  font-size: calc(11.5px * var(--wc-scale, 1));
  color: var(--wc-muted, #8a94a6);
  margin-top: 2px;
  line-height: 1.3;
}
.lw-value {
  flex-shrink: 0;
  font-size: calc(14px * var(--wc-scale, 1));
  font-weight: 800;
  color: var(--wc-accent, #2f7bff);
  font-variant-numeric: tabular-nums;
}
.lw-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wc-muted, #8a94a6);
}
.lw-empty p {
  margin: 0;
  font-size: calc(12.5px * var(--wc-scale, 1));
}
@media (prefers-reduced-motion: reduce) {
  .lw-track.scrolling {
    animation: none;
  }
}
</style>
