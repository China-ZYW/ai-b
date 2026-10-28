<script setup lang="ts">
/**
 * 驾驶舱编辑器：grid-layout-plus 自由拖拽 / 缩放网格（12 列）。
 * 左侧指标面板可拖入或点击加入；每个 widget 用绑定指标的实时刷新数据渲染；
 * 顶部配置名称/主题/列数/刷新频率/大屏标记；支持 AI 自适应布局（依据指标真实数据形态判定角色与尺寸并分层排版）与保存布局。
 *
 * T7.1 拆分（2026-10-24）：本体只留编排与画布容器；状态与动作在 ./editor/useEditor*.ts
 * 六个协作 composable（core 状态 / layout 几何互转 / widgets 组件操作 / dialogs 三类弹窗 /
 * ai 两条布局路径 / persistence 加载保存），模板拆入 ./editor/Editor{TopBar,MetricPanel,GridWidget}.vue，
 * 视图态经 provide/inject（useEditorCtx）下发，消费方零 props 风暴。
 */
import { onMounted, onUnmounted } from 'vue'
import { GridLayout, GridItem } from 'grid-layout-plus'
import InfographicTemplatePicker from '@/components/dashboard/InfographicTemplatePicker.vue'
import InfographicEditorDialog from '@/components/dashboard/InfographicEditorDialog.vue'
import ImageWidgetDialog from '@/components/dashboard/ImageWidgetDialog.vue'
import ListWidgetDialog from '@/components/dashboard/ListWidgetDialog.vue'
import EditorTopBar from './editor/EditorTopBar.vue'
import EditorMetricPanel from './editor/EditorMetricPanel.vue'
import EditorGridWidget from './editor/EditorGridWidget.vue'
import { useDashboardEditor } from './editor/useEditor'
import { MARGIN } from './editor/editorTypes'

/** 编辑器共享上下文（reactive 聚合，模板统一 ctx.* 访问；子组件经 useEditorCtx 注入同一对象）。 */
const ctx = useDashboardEditor()

onMounted(async () => {
  await ctx.load()
  await ctx.hydrateThemes()
  // 大屏槽位模式需按画布高度动态算行高（铺满视口，与预览一致）；用 ResizeObserver 实时跟随时钟区高度变化。
  ctx.observeCanvas()
})
onUnmounted(() => {
  ctx.unobserveCanvas()
})
</script>

<template>
  <div class="editor" :class="`editor-${ctx.form.theme}`">
    <!-- 顶部配置条（主题/背景/样式/Logo/列数/刷新/大屏/自由画布 + AI/预览/保存动作） -->
    <EditorTopBar />

    <!-- 主体：左指标面板 + 右网格画布 -->
    <div v-loading="ctx.loading" class="editor-body">
      <EditorMetricPanel />

      <div
        :ref="(el) => (ctx.gridWrap = el as HTMLElement | null)"
        class="grid-canvas"
        :class="ctx.th.dark ? 'canvas-dark' : 'canvas-light'"
        :style="ctx.canvasStyle"
        @drop="ctx.onDrop"
        @dragover.prevent
      >
        <GridLayout
          v-if="!ctx.freeCanvas"
          v-model:layout="ctx.layout"
          :col-num="ctx.form.cols"
          :row-height="ctx.editorRowHeight"
          :margin="[MARGIN, MARGIN]"
          :is-draggable="true"
          :is-resizable="true"
          :use-css-transforms="true"
          :vertical-compact="true"
        >
          <GridItem
            v-for="(item, li) in ctx.layout"
            :key="item.i"
            :x="item.x"
            :y="item.y"
            :w="item.w"
            :h="item.h"
            :i="item.i"
            :min-w="2"
            :min-h="2"
            drag-ignore-from=".gw-tools"
          >
            <EditorGridWidget :item="item" :index="li" />
          </GridItem>
        </GridLayout>

        <!-- 自由画布（V4.44）：百分比绝对定位，可任意拖拽 / 缩放；复用与网格模式完全一致的组件渲染 -->
        <div v-else class="free-canvas">
          <div
            v-for="(item, li) in ctx.layout"
            :key="item.i"
            class="free-item"
            :style="ctx.freeStyle(item)"
            @mousedown="ctx.startFreeDrag(item, $event, 'move')"
          >
            <EditorGridWidget :item="item" :index="li" />
            <span class="free-resize" title="拖拽缩放" @mousedown.stop="ctx.startFreeDrag(item, $event, 'resize')" />
          </div>
        </div>

        <div v-if="ctx.layout.length === 0 && !ctx.loading" class="canvas-empty">
          <el-icon :size="40"><DataBoard /></el-icon>
          <p>从左侧拖入指标，或添加信息图，开始编排你的驾驶舱</p>
        </div>
      </div>
    </div>
  </div>

  <InfographicTemplatePicker v-model="ctx.pickerVisible" :dark="ctx.th.dark" @select="ctx.onPickTemplate" />

  <InfographicEditorDialog
    v-model="ctx.infoVisible"
    :spec="ctx.infoSpec"
    :title="ctx.infoTitle"
    :dark="ctx.th.dark"
    :accent="ctx.th.accent"
    :metrics="ctx.metrics"
    :auto-sample="ctx.infoCreating"
    @confirm="ctx.onInfoConfirm"
  />

  <ImageWidgetDialog
    v-model="ctx.imageVisible"
    :spec="ctx.imageTargetKey ? (ctx.metaMap[ctx.imageTargetKey]?.image ?? null) : null"
    @confirm="ctx.onImageConfirm"
  />

  <ListWidgetDialog
    v-model="ctx.listVisible"
    :metrics="ctx.metrics"
    :initial-metric-id="ctx.listTargetKey ? (ctx.metaMap[ctx.listTargetKey]?.metricId ?? null) : null"
    :spec="ctx.listTargetKey ? (ctx.metaMap[ctx.listTargetKey]?.list ?? null) : null"
    @confirm="ctx.onListConfirm"
  />
</template>

<style scoped>
.editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.editor-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}
/* ---------- 右侧网格画布 ---------- */
.grid-canvas {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 14px;
  position: relative;
}
/* 画布背景改由主题令牌内联下发（canvasStyle）；此处仅保留深/浅色的后代适配。 */
/* 自由画布（V4.44）：百分比绝对定位容器与可拖拽/缩放的组件 */
/* P3 修复：必须给容器确定高度（height:100%），否则绝对定位子项的百分比高度解析基准为 0 → 整屏空白；与预览 .screen-grid.is-free 对齐 */
.free-canvas {
  position: relative;
  width: 100%;
  height: 100%;
}
.free-item {
  position: absolute;
  box-sizing: border-box;
  /* P2 修复：自由画布子项无间距、相邻 widget 会贴边；用内边距（border-box）在组件间制造统一 gap，与预览一致 */
  padding: 8px;
  min-width: 60px;
  min-height: 50px;
  cursor: move;
}
.free-item .grid-widget {
  height: 100%;
}
.free-resize {
  position: absolute;
  right: 2px;
  bottom: 2px;
  width: 14px;
  height: 14px;
  cursor: nwse-resize;
  border-right: 2px solid var(--sc-accent, #4f8cff);
  border-bottom: 2px solid var(--sc-accent, #4f8cff);
  opacity: 0.55;
}
.free-item:hover .free-resize {
  opacity: 1;
}
.canvas-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  pointer-events: none;
}
.canvas-dark .canvas-empty {
  color: rgba(160, 200, 255, 0.5);
}
.canvas-light .canvas-empty {
  color: var(--text-placeholder);
}
.canvas-empty p {
  margin: 0;
  font-size: 13.5px;
}
</style>
