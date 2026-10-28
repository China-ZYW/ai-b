<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  INFO_CATEGORIES,
  INFO_CATEGORY_LABELS,
  INFO_TEMPLATE_CATALOG,
  type InfographicCategory
} from './infographicTemplates'

/**
 * 信息图「添加」入口：按分类展示带缩略图的模板卡片。
 *
 * <p>点击卡片即选定模板并关闭；真正的组件在编辑器「确定」时才创建——
 * 这样用户在编辑器点「取消」不会在画布留下空白占位（修复了此前「取消残留空组件」的体验问题）。
 *
 * <p>缩略图用内联 SVG（按分类绘制示意形态，非引擎实渲染），轻量且不需要为每个模板异步实例化引擎。
 */
defineProps<{
  modelValue: boolean
  /** 当前驾驶舱是否深色，用于缩略图配色。 */
  dark?: boolean
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'select', template: string): void
}>()

const activeTab = ref<InfographicCategory | 'all'>('all')

const filtered = computed(() =>
  activeTab.value === 'all'
    ? INFO_TEMPLATE_CATALOG
    : INFO_TEMPLATE_CATALOG.filter((t) => t.category === activeTab.value)
)

/** 按分类绘制示意缩略图（currentColor 跟随卡片文字色，深浅主题自适应）。 */
function thumbSvg(cat: InfographicCategory): string {
  switch (cat) {
    case 'list':
      return `<svg viewBox="0 0 96 60" xmlns="http://www.w3.org/2000/svg" fill="none">
        <rect x="10" y="9" width="76" height="9" rx="4.5" fill="currentColor" opacity="0.85"/>
        <rect x="10" y="25" width="76" height="9" rx="4.5" fill="currentColor" opacity="0.6"/>
        <rect x="10" y="41" width="76" height="9" rx="4.5" fill="currentColor" opacity="0.4"/>
        <circle cx="18" cy="13.5" r="2.4" fill="#fff" opacity="0.95"/>
        <circle cx="18" cy="29.5" r="2.4" fill="#fff" opacity="0.95"/>
        <circle cx="18" cy="45.5" r="2.4" fill="#fff" opacity="0.95"/>
      </svg>`
    case 'pyramid':
      return `<svg viewBox="0 0 96 60" xmlns="http://www.w3.org/2000/svg" fill="none">
        <path d="M48 8 L84 50 L12 50 Z" fill="currentColor" opacity="0.45"/>
        <path d="M48 8 L84 50 L12 50 Z" fill="none" stroke="currentColor" stroke-width="1.5"/>
        <path d="M30 29 L66 29 M21 39.5 L75 39.5" stroke="#fff" stroke-width="1.2" opacity="0.85"/>
      </svg>`
    case 'sector':
      return `<svg viewBox="0 0 96 60" xmlns="http://www.w3.org/2000/svg" fill="none">
        <path d="M48 30 L48 8 A22 22 0 0 1 70 30 Z" fill="currentColor" opacity="0.7"/>
        <circle cx="48" cy="30" r="22" fill="none" stroke="currentColor" stroke-width="1.4" opacity="0.6"/>
        <circle cx="48" cy="30" r="9" fill="#fff" opacity="0.85"/>
      </svg>`
    case 'waterfall':
      return `<svg viewBox="0 0 96 60" xmlns="http://www.w3.org/2000/svg" fill="none">
        <rect x="12" y="20" width="16" height="32" rx="3" fill="currentColor" opacity="0.85"/>
        <rect x="34" y="28" width="16" height="24" rx="3" fill="currentColor" opacity="0.7"/>
        <rect x="56" y="34" width="16" height="18" rx="3" fill="currentColor" opacity="0.55"/>
        <rect x="78" y="40" width="9" height="12" rx="2" fill="currentColor" opacity="0.4"/>
      </svg>`
    case 'compare':
      return `<svg viewBox="0 0 96 60" xmlns="http://www.w3.org/2000/svg" fill="none">
        <rect x="10" y="12" width="34" height="8" rx="4" fill="currentColor" opacity="0.8"/>
        <rect x="10" y="26" width="28" height="8" rx="4" fill="currentColor" opacity="0.55"/>
        <rect x="10" y="40" width="30" height="8" rx="4" fill="currentColor" opacity="0.4"/>
        <rect x="52" y="12" width="34" height="8" rx="4" fill="currentColor" opacity="0.8"/>
        <rect x="58" y="26" width="28" height="8" rx="4" fill="currentColor" opacity="0.55"/>
        <rect x="56" y="40" width="30" height="8" rx="4" fill="currentColor" opacity="0.4"/>
        <rect x="46.5" y="8" width="3" height="44" rx="1.5" fill="currentColor" opacity="0.5"/>
      </svg>`
    case 'relation':
      return `<svg viewBox="0 0 96 60" xmlns="http://www.w3.org/2000/svg" fill="none">
        <path d="M48 30 L20 14 M48 30 L76 14 M48 30 L20 46 M48 30 L76 46" stroke="currentColor" stroke-width="1.5" opacity="0.5"/>
        <circle cx="48" cy="30" r="9" fill="currentColor" opacity="0.85"/>
        <circle cx="20" cy="14" r="6" fill="currentColor" opacity="0.5"/>
        <circle cx="76" cy="14" r="6" fill="currentColor" opacity="0.5"/>
        <circle cx="20" cy="46" r="6" fill="currentColor" opacity="0.5"/>
        <circle cx="76" cy="46" r="6" fill="currentColor" opacity="0.5"/>
      </svg>`
    case 'sequence':
    default:
      return `<svg viewBox="0 0 96 60" xmlns="http://www.w3.org/2000/svg" fill="none">
        <circle cx="20" cy="30" r="9" fill="currentColor" opacity="0.85"/>
        <circle cx="48" cy="30" r="9" fill="currentColor" opacity="0.6"/>
        <circle cx="76" cy="30" r="9" fill="currentColor" opacity="0.4"/>
        <path d="M38 26 l7 4 -7 4 Z" fill="currentColor" opacity="0.75"/>
        <path d="M66 26 l7 4 -7 4 Z" fill="currentColor" opacity="0.75"/>
      </svg>`
  }
}

function pick(tpl: string): void {
  emit('select', tpl)
  emit('update:modelValue', false)
}
function close(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="添加信息图 · 选择模板"
    width="860px"
    append-to-body
    @update:model-value="close"
  >
    <div class="tpl-picker" :class="dark ? 'is-dark' : ''">
      <div class="tp-tabs">
        <button
          v-for="t in INFO_CATEGORIES"
          :key="t"
          type="button"
          class="tp-tab"
          :class="{ active: activeTab === t }"
          @click="activeTab = t"
        >
          {{ INFO_CATEGORY_LABELS[t] }}
        </button>
      </div>

      <div class="tp-grid">
        <button
          v-for="t in filtered"
          :key="t.template"
          type="button"
          class="tp-card"
          @click="pick(t.template)"
        >
          <div class="tp-thumb" v-html="thumbSvg(t.category)" />
          <div class="tp-name">{{ t.name }}</div>
          <div class="tp-desc">{{ t.desc }}</div>
        </button>
      </div>

      <p class="tp-tip muted">
        对比类模板（SWOT、左右对比）在编辑器里用「分组」填写：每个分组是一根节点，组内再填要点。
      </p>
    </div>
  </el-dialog>
</template>

<style scoped>
.tpl-picker {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.tp-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tp-tab {
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  border-radius: 999px;
  padding: 4px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.tp-tab:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.tp-tab.active {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
  color: #fff;
}
.tp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
  max-height: 420px;
  overflow-y: auto;
  padding: 2px;
}
.tp-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-blank);
  border-radius: 10px;
  padding: 10px;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s;
}
.tp-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 4px 14px rgba(64, 158, 255, 0.18);
  transform: translateY(-2px);
}
.tp-thumb {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
  overflow: hidden;
}
.tp-thumb :deep(svg) {
  width: 96px;
  height: 60px;
}
.tp-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.tp-desc {
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.tp-tip {
  margin: 0;
  font-size: 12px;
}
</style>
