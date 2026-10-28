<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { dashboardTemplateApi } from '@/api/dashboardTemplate'
import type { DashboardTemplateVO } from '@/types'

/**
 * 内置驾驶舱模板画廊：展示 4 套骨架模板（含槽位缩略预览），选中后由父组件
 * 走 /from-template 实例化。模板为全局共享资源，只读。
 */
const emit = defineEmits<{
  (e: 'select', template: DashboardTemplateVO): void
}>()

const loading = ref(false)
const templates = ref<DashboardTemplateVO[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    templates.value = await dashboardTemplateApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 槽位数（骨架布局单元数量），用于卡片角标。 */
function slotCount(t: DashboardTemplateVO): number {
  return t.layout?.length ?? 0
}
</script>

<template>
  <div v-loading="loading" class="tpl-gallery">
    <div
      v-for="t in templates"
      :key="t.id"
      class="tpl-card card-hover"
      @click="emit('select', t)"
    >
      <!-- 骨架缩略预览：按 12 列网格等比缩放槽位 -->
      <div class="tpl-preview">
        <span
          v-for="s in t.layout || []"
          :key="s.i"
          class="tpl-slot"
          :style="{
            left: `${(s.x / 12) * 100}%`,
            top: `${(s.y / 12) * 100}%`,
            width: `${(s.w / 12) * 100}%`,
            height: `${(s.h / 12) * 100}%`
          }"
        />
        <el-icon class="tpl-icon" :size="30"><component :is="t.icon || 'DataBoard'" /></el-icon>
      </div>

      <div class="tpl-body">
        <div class="tpl-name-row">
          <span class="tpl-name truncate">{{ t.name }}</span>
          <el-tag size="small" effect="plain" round>{{ t.category || '通用' }}</el-tag>
        </div>
        <p class="tpl-desc">{{ t.description || '—' }}</p>
        <div class="tpl-foot">
          <span class="muted">{{ slotCount(t) }} 个指标槽位</span>
          <el-button size="small" type="primary" text>
            使用模板 <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </div>
    </div>

    <el-empty v-if="!loading && templates.length === 0" description="暂无内置模板" />
  </div>
</template>

<style scoped>
.tpl-gallery {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  min-height: 120px;
}
.tpl-card {
  cursor: pointer;
  border-radius: var(--radius-lg);
  overflow: hidden;
  border: 1px solid var(--border-light);
  background: var(--bg-card);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
.tpl-card:hover {
  transform: translateY(-3px);
  border-color: rgba(0, 47, 167, 0.24);
  box-shadow: var(--shadow-md);
}
.tpl-preview {
  position: relative;
  height: 116px;
  background: linear-gradient(150deg, #00164f, #002fa7 60%, #0b3fc4);
  padding: 8px;
}
.tpl-slot {
  position: absolute;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(120, 180, 255, 0.4);
  border-radius: 4px;
  box-sizing: border-box;
  margin: 2px;
}
.tpl-icon {
  position: absolute;
  right: 12px;
  bottom: 10px;
  color: rgba(160, 210, 255, 0.5);
}
.tpl-body {
  padding: 12px 14px 14px;
}
.tpl-name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.tpl-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.tpl-desc {
  margin: 7px 0 10px;
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.55;
  height: 38px;
  overflow: hidden;
}
.tpl-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tpl-foot .muted {
  font-size: 12px;
}
</style>
