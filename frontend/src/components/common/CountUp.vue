<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 数字滚动（count-up）展示组件：从当前显示值用 easeOutCubic 缓动滚动到目标值。
 * - 首次挂载从 0 滚到 {@code value}（指标/大屏 KPI 读数入场动画）；
 * - {@code value} 变化时从当前值重滚（数据刷新时的平滑过渡）。
 *
 * <p>{@code format} 负责把滚动中的数值格式化为最终文本（紧凑化/单位/千分位由调用方决定），
 * 组件本身只做缓动与渲染，不关心业务口径。
 */
const props = withDefaults(
  defineProps<{
    value: number
    duration?: number
    /** 数值→展示文本（含单位/紧凑化）；缺省按整数四舍五入。 */
    format?: (n: number) => string
  }>(),
  { duration: 900 }
)

const display = ref(0)
let raf = 0
let from = 0
let target = 0
let start = 0

function tick(now: number): void {
  const p = Math.min(1, (now - start) / props.duration)
  const e = 1 - Math.pow(1 - p, 3) // easeOutCubic
  display.value = from + (target - from) * e
  if (p < 1) raf = requestAnimationFrame(tick)
  else display.value = target
}

function run(to: number): void {
  cancelAnimationFrame(raf)
  from = display.value
  target = to
  start = performance.now()
  raf = requestAnimationFrame(tick)
}

onMounted(() => run(props.value))
watch(
  () => props.value,
  (v) => run(v)
)
onBeforeUnmount(() => cancelAnimationFrame(raf))

const text = computed(() =>
  props.format ? props.format(display.value) : String(Math.round(display.value))
)
</script>

<template>
  <span class="count-up">{{ text }}</span>
</template>

<style scoped>
.count-up {
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
}
</style>
