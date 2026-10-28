<script setup lang="ts">
import { computed } from 'vue'
import type { ImageWidgetSpec } from '@/types'

/**
 * 图片/图标组件（纯展示，不取数）：自由放置的 logo/图标/装饰图片。
 * url 为空时显示占位；fit 控制填充方式（contain 保留完整 / cover 裁满 / fill 拉伸）；
 * round=true 圆形裁切（用于头像类 logo）。数据由父组件（编辑器/大屏）传入，本组件不发请求。
 */
const props = withDefaults(
  defineProps<{
    spec?: ImageWidgetSpec | null
  }>(),
  { spec: null }
)

const fit = computed<'contain' | 'cover' | 'fill'>(() => {
  const f = props.spec?.fit
  if (f === 'cover') return 'cover'
  if (f === 'fill') return 'fill'
  return 'contain'
})
const radius = computed(() => (props.spec?.round ? '50%' : '0'))
</script>

<template>
  <div class="image-widget">
    <img
      v-if="spec?.url"
      :src="spec.url"
      :alt="spec.alt || ''"
      class="iw-img"
      :style="{ objectFit: fit, borderRadius: radius }"
      draggable="false"
    />
    <div v-else class="iw-placeholder">
      <el-icon :size="30"><Picture /></el-icon>
      <p>未设置图片</p>
    </div>
  </div>
</template>

<style scoped>
.image-widget {
  height: 100%;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.iw-img {
  max-width: 100%;
  max-height: 100%;
  width: 100%;
  height: 100%;
  display: block;
  object-position: center;
  user-select: none;
  -webkit-user-drag: none;
}
.iw-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--wc-muted, #8a94a6);
}
.iw-placeholder p {
  margin: 0;
  font-size: 12.5px;
}
</style>
