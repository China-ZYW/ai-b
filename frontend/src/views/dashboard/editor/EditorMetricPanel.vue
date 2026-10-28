<script setup lang="ts">
/**
 * 驾驶舱编辑器左侧指标面板（T7.1 自 DashboardEditorView.vue 拆出，模板与样式逐字搬移）：
 * 指标搜索/拖入源 + 信息图、图片、列表三类组件添加按钮。状态经 useEditorCtx 注入。
 */
import { useEditorCtx } from './useEditor'

const ctx = useEditorCtx()
</script>

<template>
  <aside class="metric-panel">
    <p class="mp-title">指标</p>
    <el-input v-model="ctx.metricKeyword" size="small" placeholder="搜索指标" clearable class="mp-search">
      <template #prefix><el-icon><Search /></el-icon></template>
    </el-input>
    <p class="mp-hint muted">拖入右侧网格，或点击追加到底部</p>
    <el-button size="small" class="mp-add-info" @click="ctx.openInfoPicker">
      <el-icon><DocumentAdd /></el-icon> 添加信息图
    </el-button>
    <el-button size="small" class="mp-add-image" @click="ctx.openImagePicker">
      <el-icon><Picture /></el-icon> 添加图片/图标
    </el-button>
    <el-button size="small" class="mp-add-list" @click="ctx.openListPicker">
      <el-icon><List /></el-icon> 添加列表
    </el-button>
    <div class="mp-list">
      <div
        v-for="m in ctx.filteredMetrics"
        :key="m.id"
        class="mp-item"
        draggable="true"
        @dragstart="ctx.onDragStart(m, $event)"
        @click="ctx.addMetric(m)"
      >
        <el-icon class="mp-icon"><TrendCharts /></el-icon>
        <span class="mp-name truncate">{{ m.name }}</span>
        <el-icon class="mp-add"><Plus /></el-icon>
      </div>
      <el-empty v-if="ctx.filteredMetrics.length === 0" :image-size="60" description="暂无指标" />
    </div>
  </aside>
</template>

<style scoped>
.metric-panel {
  width: 244px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-light);
  background: linear-gradient(180deg, var(--bg-card), var(--ikb-ghost));
  display: flex;
  flex-direction: column;
  padding: 12px 10px;
}
.mp-title {
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--text-placeholder);
  margin: 2px 0 8px 4px;
}
.mp-search {
  margin-bottom: 6px;
}
.mp-hint {
  font-size: 11px;
  margin: 2px 4px 8px;
}
.mp-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mp-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  background: var(--bg-card);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  cursor: grab;
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}
.mp-item:hover {
  border-color: rgba(0, 47, 167, 0.28);
  box-shadow: var(--shadow-sm);
  transform: translateX(2px);
}
.mp-item:active {
  cursor: grabbing;
}
.mp-icon {
  color: var(--ikb-light);
  flex-shrink: 0;
}
.mp-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-regular);
  flex: 1;
}
.mp-add {
  color: var(--text-placeholder);
  flex-shrink: 0;
}
</style>
