<script setup lang="ts">
/**
 * 编辑器单个组件格（T7.1 自 DashboardEditorView.vue 拆出）：网格模式 GridItem 与
 * 自由画布 free-item 共用的「工具条 + WidgetCard」内部块，两处渲染逐字一致。
 * ★拆分时顺带对齐一处差异：原网格分支的下拉菜单缺少「编辑图片/编辑列表」分支（自由画布分支有），
 *   合并为单一组件后统一采用完整版；onWidgetCommand 命令处理本身两种模式原本就一致。
 */
import type { ChartStyle, WidgetStyle } from '@/types'
import WidgetCard from '@/components/dashboard/WidgetCard.vue'
import WidgetStyleForm from '@/components/dashboard/WidgetStyleForm.vue'
import ChartStyleForm from '@/components/chart/ChartStyleForm.vue'
import { useEditorCtx } from './useEditor'
import type { FreeLayoutItem } from './editorTypes'

const props = defineProps<{
  item: FreeLayoutItem
  index: number
}>()

const ctx = useEditorCtx()
</script>

<template>
  <div class="grid-widget" :class="{ 'gw-dark': ctx.th.dark }">
    <div class="gw-tools">
      <el-button size="small" circle text title="刷新该组件" @click="ctx.refreshWidget(String(props.item.i))">
        <el-icon><Refresh /></el-icon>
      </el-button>
      <!-- widget 级图表标注：只改本格子的轴名/单位/数值格式/图例，不影响指标与 SQL -->
      <el-popover placement="bottom-end" :width="304" trigger="click">
        <template #reference>
          <el-button
            size="small"
            circle
            text
            :type="ctx.metaOf(props.item.i).chartStyle ? 'primary' : ''"
            title="图表标注"
          >
            <el-icon><Setting /></el-icon>
          </el-button>
        </template>
        <ChartStyleForm
          :model-value="ctx.metaOf(props.item.i).chartStyle"
          :chart-type="ctx.dataOf(props.item.i).chartSpec?.type ?? null"
          :spec-style="ctx.dataOf(props.item.i).chartSpec?.style ?? null"
          @update:model-value="(s: ChartStyle | null) => ctx.onWidgetStyle(String(props.item.i), s)"
        />
      </el-popover>
      <!-- widget 级视觉样式：只改本格子的卡片背景/边框/标题色/圆角 -->
      <el-popover placement="bottom-end" :width="292" trigger="click">
        <template #reference>
          <el-button
            size="small"
            circle
            text
            :type="ctx.metaOf(props.item.i).style ? 'primary' : ''"
            title="组件样式"
          >
            <el-icon><Brush /></el-icon>
          </el-button>
        </template>
        <div class="gw-field">
          <div class="gw-field-label">组件标题</div>
          <el-input
            :model-value="ctx.metaOf(props.item.i).title || ''"
            size="small"
            maxlength="64"
            clearable
            placeholder="空=指标名称"
            @update:model-value="(v: string) => ctx.onWidgetTitle(String(props.item.i), v)"
          />
        </div>
        <WidgetStyleForm
          :model-value="ctx.metaOf(props.item.i).style"
          :dark="ctx.th.dark"
          @update:model-value="(s: WidgetStyle | null) => ctx.onWidgetVisual(String(props.item.i), s)"
        />
      </el-popover>
      <el-dropdown trigger="click" @command="(cmd: string) => ctx.onWidgetCommand(String(props.item.i), cmd)">
        <el-button size="small" circle text title="更多操作"><el-icon><MoreFilled /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu class="gw-menu">
            <!-- 信息图组件：不绑定指标，仅编辑内容 -->
            <template v-if="ctx.metaOf(props.item.i).kind === 'infographic'">
              <el-dropdown-item command="editInfo">编辑内容</el-dropdown-item>
              <el-dropdown-item command="toChart">转为数据图表</el-dropdown-item>
            </template>
            <!-- 图片组件：仅编辑图片，可转回数据图表 -->
            <template v-else-if="ctx.metaOf(props.item.i).kind === 'image'">
              <el-dropdown-item command="editImage">编辑图片</el-dropdown-item>
              <el-dropdown-item command="toChart">转为数据图表</el-dropdown-item>
            </template>
            <!-- 列表组件：仅编辑列表配置，可转回数据图表 -->
            <template v-else-if="ctx.metaOf(props.item.i).kind === 'list'">
              <el-dropdown-item command="editList">编辑列表</el-dropdown-item>
              <el-dropdown-item command="toChart">转为数据图表</el-dropdown-item>
            </template>
            <!-- 数据图表组件：绑定 / 清空指标，也可转为信息图 -->
            <template v-else>
              <li class="gw-menu-head">绑定指标</li>
              <el-dropdown-item
                v-for="m in ctx.metrics"
                :key="m.id"
                :command="`bind:${m.id}`"
              >
                {{ m.name }}
              </el-dropdown-item>
              <el-dropdown-item divided command="unbind">清空绑定</el-dropdown-item>
              <el-dropdown-item command="toInfo">转为信息图</el-dropdown-item>
              <el-dropdown-item command="toList">转为列表</el-dropdown-item>
            </template>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <!-- 删除组件：从「⋮」菜单提出来的独立按钮（二次确认防误删） -->
      <el-button
        size="small"
        circle
        text
        type="danger"
        title="删除组件"
        @click="ctx.removeWidget(String(props.item.i))"
      >
        <el-icon><Delete /></el-icon>
      </el-button>
    </div>
    <WidgetCard
      :title="ctx.metaOf(props.item.i).title"
      :metric-id="ctx.metaOf(props.item.i).metricId"
      :chart-spec="ctx.dataOf(props.item.i).chartSpec"
      :chart-style="ctx.metaOf(props.item.i).chartStyle"
      :style-override="ctx.metaOf(props.item.i).style"
      :result="ctx.dataOf(props.item.i).result"
      :loading="ctx.dataOf(props.item.i).loading"
      :tokens="ctx.th"
      :theme="ctx.form.theme"
      :index="props.index"
      :infographic="ctx.metaOf(props.item.i).infographic"
      :image="ctx.metaOf(props.item.i).image"
      :list="ctx.metaOf(props.item.i).list"
      chart-height="100%"
    />
  </div>
</template>

<style scoped>
.grid-widget {
  height: 100%;
  position: relative;
}
.gw-tools {
  position: absolute;
  top: 4px;
  right: 6px;
  z-index: 5;
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.16s ease;
}
.grid-widget:hover .gw-tools {
  opacity: 1;
}
.gw-tools :deep(.el-button) {
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(4px);
}
/* 深色画布下的工具按钮适配：原规则 .canvas-dark .gw-tools 依赖外层类，组件化后由根节点 gw-dark 类自持 */
.gw-dark .gw-tools :deep(.el-button) {
  background: rgba(10, 30, 80, 0.7);
  color: #cfe0ff;
}
.gw-menu-head {
  padding: 6px 16px;
  font-size: 11px;
  color: var(--text-placeholder);
  font-weight: 700;
  list-style: none;
}
.gw-menu {
  max-height: 320px;
  overflow-y: auto;
}
.gw-field {
  margin-bottom: 10px;
}
.gw-field-label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 5px;
}
</style>
