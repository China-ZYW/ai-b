<script setup lang="ts">
import { computed } from 'vue'
import type { WidgetStyle } from '@/types'

/**
 * 单个 widget 的视觉样式覆盖表单（编辑器右侧格子齿轮弹层）。
 * 只改本格子的卡片背景/边框/标题色/强调色/圆角，随大屏 layout JSON 落库；空项=跟随大屏主题。
 * 通过 v-model 双向绑定 {@link WidgetStyle}（null=无覆盖）。
 */
const props = defineProps<{
  modelValue?: WidgetStyle | null
  /** 当前大屏是否深色系（决定色板默认提示，仅展示用）。 */
  dark?: boolean
}>()
const emit = defineEmits<{
  /** 上抛覆盖值（null 表示清除、回落大屏主题）。 */
  (e: 'update:modelValue', style: WidgetStyle | null): void
}>()

const R = (v?: string | null): string | null => (v && v.trim() ? v.trim() : null)

/** 合并一次 patch 并清理空值；全空则回传 null（清除覆盖）。 */
function commit(next: WidgetStyle): void {
  const clean: WidgetStyle = {}
  const cardBg = R(next.cardBg)
  if (cardBg) clean.cardBg = cardBg
  const borderColor = R(next.borderColor)
  if (borderColor) clean.borderColor = borderColor
  const titleColor = R(next.titleColor)
  if (titleColor) clean.titleColor = titleColor
  const accent = R(next.accent)
  if (accent) clean.accent = accent
  if (typeof next.radius === 'number') clean.radius = next.radius
  emit('update:modelValue', Object.keys(clean).length ? clean : null)
}

const cardBg = computed({
  get: () => props.modelValue?.cardBg ?? '',
  set: (v: string) => commit({ ...(props.modelValue ?? {}), cardBg: v })
})
const borderColor = computed<string | null>({
  get: () => props.modelValue?.borderColor ?? null,
  set: (v) => commit({ ...(props.modelValue ?? {}), borderColor: v })
})
const titleColor = computed<string | null>({
  get: () => props.modelValue?.titleColor ?? null,
  set: (v) => commit({ ...(props.modelValue ?? {}), titleColor: v })
})
const accent = computed<string | null>({
  get: () => props.modelValue?.accent ?? null,
  set: (v) => commit({ ...(props.modelValue ?? {}), accent: v })
})
const radius = computed({
  get: () => (props.modelValue?.radius == null ? '' : String(props.modelValue.radius)),
  set: (v: string) => commit({ ...(props.modelValue ?? {}), radius: v === '' ? undefined : Number(v) })
})
</script>

<template>
  <div class="ws-form">
    <p class="ws-title">本组件样式覆盖</p>
    <div class="ws-row">
      <span class="ws-label">卡片背景</span>
      <el-input v-model="cardBg" size="small" placeholder="色值/渐变 CSS，空=跟随主题" />
    </div>
    <div class="ws-row">
      <span class="ws-label">卡片边框</span>
      <el-color-picker v-model="borderColor" size="small" />
    </div>
    <div class="ws-row">
      <span class="ws-label">标题颜色</span>
      <el-color-picker v-model="titleColor" size="small" />
    </div>
    <div class="ws-row">
      <span class="ws-label">强调色</span>
      <el-color-picker v-model="accent" size="small" />
    </div>
    <div class="ws-row">
      <span class="ws-label">卡片圆角</span>
      <el-select v-model="radius" size="small" placeholder="跟随主题" clearable style="width: 100%">
        <el-option label="直角（0）" value="0" />
        <el-option label="小（6）" value="6" />
        <el-option label="中（10）" value="10" />
        <el-option label="大（16）" value="16" />
        <el-option label="超大（24）" value="24" />
      </el-select>
    </div>
    <p class="ws-hint muted">空项=跟随大屏主题；仅影响本格子。</p>
  </div>
</template>

<style scoped>
.ws-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ws-title {
  margin: 0;
  font-size: 12.5px;
  font-weight: 700;
  color: var(--text-primary);
}
.ws-row {
  display: grid;
  grid-template-columns: 64px 1fr;
  align-items: center;
  gap: 8px;
}
.ws-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.ws-hint {
  margin: 2px 0 0;
  font-size: 11.5px;
}
</style>
