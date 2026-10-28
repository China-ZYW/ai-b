<script setup lang="ts">
/**
 * 指标库：问数结果保存的可复用指标集中管理。
 * 支持预览（实时重跑 SQL）、改名/描述/列展示名、删除、加入驾驶舱（追加到已有驾驶舱布局）。
 * 指标以已存 SQL 取数，不消耗 LLM、不计入问数配额。
 *
 * <p><b>多层级分组</b>：左侧「分组」目录树 + 右侧卡片网格，与驾驶舱列表同构。分组复用后端
 * {@code resource_group} 表（{@code resource_type=METRIC} 独立于 DASHBOARD 那棵），指标归属走
 * 专用端点 {@code PUT /api/metric/{id}/group}（不并入全量 update，避免编辑器 payload 不携带 groupId 时清空归属）。
 * 分组读 / 写 / 删分别沿用指标库既有权限码 {@code metric:item:read/write/delete}，不发明新码。
 *
 * <p><b>T7.3 拆分（2026-10）</b>：本视图只保留编排与页面骨架——
 * 状态逻辑在 ./useMetric*（core/groups/preview/edit/addDash/ai，reactive ctx provide 下发），
 * 模板大块在 ./MetricGroupTree / MetricCardGrid / MetricEditDrawer / MetricAddDashDialog / MetricAiDialog，
 * 纯函数在 ./metricUtils。行为与拆分前一致，逻辑逐字搬移。
 */
import { onMounted } from 'vue'
import WidgetCard from '@/components/dashboard/WidgetCard.vue'
import ShareDialog from '@/components/chat/ShareDialog.vue'
import MetricGroupTree from './MetricGroupTree.vue'
import MetricCardGrid from './MetricCardGrid.vue'
import MetricEditDrawer from './MetricEditDrawer.vue'
import MetricAddDashDialog from './MetricAddDashDialog.vue'
import MetricAiDialog from './MetricAiDialog.vue'
import { useMetric } from './useMetric'

const ctx = useMetric()

onMounted(() => ctx.init())
</script>

<template>
  <div class="metric-view">
    <div class="metric-layout">
      <!-- 左侧：多层级分组目录树（与驾驶舱列表同构；resource_group 的 METRIC 域，与 DASHBOARD 树互不越界） -->
      <MetricGroupTree />

      <!-- 右侧：指标列表 -->
      <section class="metric-main">
        <div class="page-head">
          <div>
            <h2 class="ph-title">指标库</h2>
            <p class="ph-desc">
              在「对话问数」结果上点「存为指标」即可沉淀。指标以已存 SQL 实时重跑取数，不消耗大模型额度，可加入驾驶舱。
            </p>
          </div>
          <el-button @click="ctx.load">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
          <el-button
            v-if="ctx.can('semantic:meta:auto-analysis') && ctx.config.metricAutoAnalysisEnabled"
            type="primary" plain
            :loading="ctx.aiSubmitting && ctx.aiMode === 'analysis'"
            @click="ctx.openAiAnalysis"
          >
            <el-icon><MagicStick /></el-icon> 指标自动分析
          </el-button>
          <el-button
            v-if="ctx.can('dashboard:cockpit:ai') && ctx.config.cockpitAiDashboardEnabled"
            type="primary"
            :loading="ctx.aiSubmitting && ctx.aiMode === 'build'"
            @click="ctx.openAiBuild"
          >
            <el-icon><DataBoard /></el-icon> 生成 AI 大屏
          </el-button>
        </div>

        <el-alert
          v-if="ctx.staleCount > 0"
          class="stale-alert"
          type="warning"
          show-icon
          :closable="false"
          :title="`${ctx.staleCount} 个指标的数据已超过新鲜度期限`"
          description="黄色标记的指标数据可能已陈旧，建议进入指标预览触发一次刷新后再用于决策或分享。"
        />

        <div class="toolbar card">
          <el-input
            v-model="ctx.keyword"
            placeholder="搜索指标名称 / 描述"
            clearable
            style="width: 300px"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <span class="muted count">共 {{ ctx.filtered.length }} 个指标</span>
        </div>

        <MetricCardGrid />
      </section>
    </div>

    <!-- 预览对话框 -->
    <el-dialog v-model="ctx.previewVisible" :title="`指标预览 · ${ctx.previewMetric?.name || ''}`" width="720px" align-center>
      <div class="preview-box">
        <WidgetCard
          :title="(ctx.previewMetric?.alias || ctx.previewMetric?.name) ?? ''"
          :metric-id="ctx.previewMetric?.id"
          :chart-spec="ctx.previewSpec"
          :result="ctx.previewResult"
          :loading="ctx.previewLoading"
          theme="light"
          chart-height="380px"
        />
      </div>
    </el-dialog>

    <!-- 指标 AI 自动化（Skill）：指标自动分析 / 生成 AI 大屏 -->
    <MetricAiDialog />

    <!-- 编辑抽屉 -->
    <MetricEditDrawer />

    <!-- 加入驾驶舱对话框 -->
    <MetricAddDashDialog />

    <!-- 分享对话框（结果快照，复用「对话问数」的分享组件） -->
    <ShareDialog
      v-model="ctx.shareVisible"
      :datasource-id="ctx.shareTarget?.datasourceId ?? null"
      :question="ctx.shareTarget?.question || ''"
      :sql="ctx.shareTarget?.sql"
      :result="ctx.shareTarget?.result || null"
      :chart="ctx.shareTarget?.chart"
    />
  </div>
</template>

<style scoped>
.metric-view {
  display: flex;
  height: 100%;
  overflow: hidden;
  padding: 22px 26px 32px;
}
/* ---------- 分组侧栏 + 列表主区（根容器 .metric-view 为 height:100% 行向 flex，侧栏与网格各自内部滚动，不再整页滚动） ---------- */
.metric-layout {
  flex: 1;
  display: flex;
  gap: 16px;
  min-height: 0;
}
.metric-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  /* .metric-main 为列向 flex：头部固定不压缩，网格独占剩余高度并内部滚动 */
  flex-shrink: 0;
}
.ph-title {
  font-size: 20px;
  font-weight: 800;
  margin: 0 0 4px;
}
.ph-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
  max-width: 760px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  flex-shrink: 0;
}
.count {
  font-size: 12.5px;
}
.stale-alert {
  margin-bottom: 12px;
  /* 与驾驶舱列表一致：.metric-main 为列向 flex，告警条固定不压缩；网格独占剩余高度内部滚动。 */
  flex-shrink: 0;
}
.preview-box {
  height: 440px;
}
</style>
