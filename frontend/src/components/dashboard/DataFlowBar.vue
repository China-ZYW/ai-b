<script setup lang="ts">
import { computed } from 'vue'
import { rgba } from '@/theme/dashboardTheme'

/**
 * L2 数据流动管道特效：以「数据源接入 → 抽取清洗 → 指标计算 → 大屏展现」的节点 + 流动光点，
 * 表达数据从接入到上屏的链路。由主题令牌 {@code flow} 驱动（仅该令牌为真时渲染）。
 *
 * <p>纯装饰：节点文案为固定链路示意，不发请求、不读业务数据（真实同步状态属后端任务范畴），
 * 高度固定 30px 作为标题栏下方的一条装饰，不参与也不影响大屏网格布局。
 */
const props = withDefaults(
  defineProps<{
    /** 是否渲染（主题令牌 flow）。 */
    flow?: boolean
    /** 强调色。 */
    accent?: string
    /** 链路节点文案。 */
    nodes?: string[]
  }>(),
  { flow: false, accent: '#00c2ff', nodes: () => ['数据源接入', '抽取清洗', '指标计算', '大屏展现'] }
)

const nodeBg = computed(() => rgba(props.accent, 0.12))
const line = computed(() => rgba(props.accent, 0.3))
</script>

<template>
  <div v-if="flow" class="flow-bar" :style="{ '--fb-accent': accent, '--fb-line': line, '--fb-node': nodeBg }">
    <template v-for="(n, i) in nodes" :key="n">
      <span class="fb-node" :style="{ animationDelay: `${i * 0.6}s` }">{{ n }}</span>
      <span v-if="i < nodes.length - 1" class="fb-track">
        <i class="fb-dot" :style="{ animationDelay: `${i * 0.35}s` }" />
      </span>
    </template>
  </div>
</template>

<style scoped>
.flow-bar {
  height: 30px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 22px;
  overflow: hidden;
  pointer-events: none;
}
.fb-node {
  font-size: 11px;
  letter-spacing: 1px;
  color: var(--fb-accent);
  background: var(--fb-node);
  border: 1px solid var(--fb-line);
  border-radius: 999px;
  padding: 2px 9px;
  white-space: nowrap;
  animation: fbPulse 3s ease-in-out infinite;
}
@keyframes fbPulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 transparent;
    opacity: 0.75;
  }
  50% {
    box-shadow: 0 0 12px 0 var(--fb-line);
    opacity: 1;
  }
}
.fb-track {
  position: relative;
  flex: 1;
  min-width: 24px;
  height: 1px;
  margin: 0 4px;
  background: var(--fb-line);
}
/* 流动光点：沿轨道循环移动，表达数据正在向前流动 */
.fb-dot {
  position: absolute;
  top: -2px;
  left: 0;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--fb-accent);
  box-shadow: 0 0 8px var(--fb-accent);
  animation: fbRun 2.8s linear infinite;
}
@keyframes fbRun {
  0% {
    left: 0;
    opacity: 0;
  }
  12% {
    opacity: 1;
  }
  88% {
    opacity: 1;
  }
  100% {
    left: 100%;
    opacity: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .fb-node,
  .fb-dot {
    animation: none;
  }
}
</style>
