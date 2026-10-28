<script setup lang="ts">
import { computed } from 'vue'
import { rgba } from '@/theme/dashboardTheme'

/**
 * L1 大屏装饰背景层（科技网格 + 缓慢扫描光）。
 *
 * 由主题令牌 {@code grid} 驱动，仅在上层传入 grid=true 时渲染。
 * 纯装饰：absolute 铺满、pointer-events:none、不占布局，因此不会影响既有
 * CSS Grid 大屏布局，也不会干扰 utils/smartLayout 的尺寸计算。
 * 已适配 prefers-reduced-motion：用户降低动效偏好时自动停动画。
 */
const props = withDefaults(
  defineProps<{
    /** 是否渲染网格背景（主题令牌 grid）。 */
    grid?: boolean
    /** 强调色（用于网格线与扫描光着色）。 */
    accent?: string
  }>(),
  { grid: false, accent: '#00c2ff' }
)

const line = computed(() => rgba(props.accent, 0.16))
const scan = computed(() => rgba(props.accent, 0.1))
</script>

<template>
  <div v-if="grid" class="tech-decor" :style="{ '--td-line': line, '--td-scan': scan }">
    <div class="td-grid" />
    <div class="td-sweep" />
  </div>
</template>

<style scoped>
.tech-decor {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  z-index: 1;
}
/* 两层重复线性渐变织出网格底纹，缓慢位移形成科技感流动 */
.td-grid {
  position: absolute;
  inset: -60px;
  background-image:
    repeating-linear-gradient(0deg, var(--td-line) 0 1px, transparent 1px 46px),
    repeating-linear-gradient(90deg, var(--td-line) 0 1px, transparent 1px 46px);
  opacity: 0.5;
  animation: tdDrift 20s linear infinite;
}
@keyframes tdDrift {
  from {
    transform: translate3d(0, 0, 0);
  }
  to {
    transform: translate3d(46px, 46px, 0);
  }
}
/* 一道竖向柔光缓慢横扫整屏 */
.td-sweep {
  position: absolute;
  top: 0;
  left: -35%;
  width: 35%;
  height: 100%;
  background: linear-gradient(90deg, transparent, var(--td-scan), transparent);
  animation: tdSweep 11s ease-in-out infinite;
}
@keyframes tdSweep {
  0% {
    transform: translateX(0);
  }
  100% {
    transform: translateX(400%);
  }
}
@media (prefers-reduced-motion: reduce) {
  .td-grid,
  .td-sweep {
    animation: none;
  }
}
</style>
