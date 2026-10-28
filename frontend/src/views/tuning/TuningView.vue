<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDatasourceStore } from '@/stores/datasource'
import SemanticPanel from '@/components/tuning/SemanticPanel.vue'
import TableRelationPanel from '@/components/tuning/TableRelationPanel.vue'
import FewShotPanel from '@/components/tuning/FewShotPanel.vue'
import LineageView from '@/views/tuning/LineageView.vue'
import AttributionView from '@/views/tuning/AttributionView.vue'
import ChartRulePanel from '@/components/tuning/ChartRulePanel.vue'
import ConfigPanel from '@/components/tuning/ConfigPanel.vue'
import ThemePanel from '@/components/tuning/ThemePanel.vue'
import IssueDistillPanel from '@/components/tuning/IssueDistillPanel.vue'
import { usePerm } from '@/composables/usePerm'
import { useConfigStore } from '@/stores/config'
import type { GovernanceIssue } from '@/types'

/**
 * 问数调优中心（配置化升级 V2）：把「一切可变策略落库可配置、一切业务语义可维护」集中到一个页面。
 * 九个子面板：语义管理 / 表关联 / 样例库 / 图表规则 / 结果与策略配置 / 主题令牌 / 数据血缘 / 维度归因 / 待治理问题，
 * 除「结果与策略配置」「图表规则」「主题令牌」为当前租户维度外，其余均随数据源过滤。
 *
 * 语义、表关联与样例按数据源维护，顶部提供数据源选择（与全局 dsStore 联动）。
 */
const dsStore = useDatasourceStore()
const { can } = usePerm()
const configStore = useConfigStore()
const router = useRouter()
const activeTab = ref('semantic')

/** tab 渲染顺序，与下方 el-tab-pane 一致；用于选默认激活项。 */
const TAB_ORDER = [
  'semantic',
  'relations',
  'fewshot',
  'chart',
  'config',
  'theme',
  'lineage',
  'attribution',
  'distill'
] as const

/**
 * tab 级可见性（《角色权限体系与组织协同设计》7.5）：无对应 read 权限点则整个 tab 不渲染。
 * 当前角色矩阵下能进入调优页（tuning:panel:read）的 OWNER/ADMIN/DATA_STEWARD 对七个 tab 均有权，
 * 故这是面向未来矩阵变化的前瞻性门禁（如日后放宽 ANALYST 进调优但只给部分子页读权）。
 */
const tabVisible = computed<Record<string, boolean>>(() => ({
  semantic: can('semantic:meta:read'),
  // 表关联复用语义层权限点（同属语义层治理职责），不单独新增权限
  relations: can('semantic:meta:read'),
  fewshot: can('fewshot:example:read'),
  chart: can('chart:rule:read'),
  config: can('config:sys:read'),
  theme: can('theme:config:write'),
  lineage: can('lineage:metric:read'),
  // 归因除权限外还受配置中心 ATTRIBUTION/enabled 控制（后端会直接拒绝请求），
  // 模块关闭时隐藏 tab，避免留下"点了必报错"的死入口。
  attribution: can('attribution:run:execute') && configStore.attributionEnabled,
  // 待治理问题（T11-1 只读）：复用调优面板读权限点，并随 METRIC/issue-distill 开关显隐。
  distill: can('tuning:panel:read') && configStore.issueDistillEnabled
}))

onMounted(() => {
  void dsStore.load().catch(() => undefined)
  // 默认激活项若被权限隐藏，回落到首个可见 tab，避免 el-tabs 指向不存在的 pane 而内容空白
  if (!tabVisible.value[activeTab.value]) {
    const first = TAB_ORDER.find((t) => tabVisible.value[t])
    if (first) activeTab.value = first
  }
})

const currentDsId = computed(() => dsStore.currentId)
/** 语义/表关联/样例/血缘/归因面板需要数据源；其余面板与数据源无关。 */
const needDatasource = computed(
  () =>
    activeTab.value === 'semantic' ||
    activeTab.value === 'relations' ||
    activeTab.value === 'fewshot' ||
    activeTab.value === 'lineage' ||
    activeTab.value === 'attribution' ||
    activeTab.value === 'distill'
)

/** 待治理问题「去补术语」：同页切到语义管理 tab（不预填，见 T11-1 二期设计稿 §11(3)）。 */
function goSemantic(): void {
  activeTab.value = 'semantic'
}

/** T11-4 巡检行「去修复」（指标同名异口径）：跳指标库页处理。 */
function goMetric(_issue: GovernanceIssue): void {
  void router.push({ name: 'metrics' })
}
</script>

<template>
  <div class="tuning-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">问数调优中心</h2>
        <p class="ph-desc">
          维护业务语义、样例库、图表规则、结果策略与主题令牌 —— 这些配置直接驱动问数准确率与展示效果，保存后即时生效。
        </p>
      </div>
      <div v-if="needDatasource" class="ds-picker">
        <span class="ds-label">数据源</span>
        <el-select
          :model-value="dsStore.currentId"
          placeholder="选择数据源"
          style="width: 220px"
          @update:model-value="(v: number) => dsStore.select(v)"
        >
          <el-option v-for="d in dsStore.list" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
      </div>
    </div>

    <div class="card tabs-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane v-if="tabVisible.semantic" name="semantic">
          <template #label><span class="tab-label"><el-icon><Collection /></el-icon> 语义管理</span></template>
          <SemanticPanel :datasource-id="currentDsId" />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.relations" name="relations">
          <template #label><span class="tab-label"><el-icon><Link /></el-icon> 表关联</span></template>
          <TableRelationPanel :datasource-id="currentDsId" />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.fewshot" name="fewshot">
          <template #label><span class="tab-label"><el-icon><Notebook /></el-icon> 样例库</span></template>
          <FewShotPanel :datasource-id="currentDsId" />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.chart" name="chart">
          <template #label><span class="tab-label"><el-icon><TrendCharts /></el-icon> 图表规则</span></template>
          <ChartRulePanel />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.config" name="config">
          <template #label><span class="tab-label"><el-icon><Setting /></el-icon> 结果与策略配置</span></template>
          <ConfigPanel />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.theme" name="theme">
          <template #label><span class="tab-label"><el-icon><Brush /></el-icon> 主题令牌</span></template>
          <ThemePanel />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.lineage" name="lineage">
          <template #label><span class="tab-label"><el-icon><Connection /></el-icon> 数据血缘</span></template>
          <LineageView :datasource-id="currentDsId" />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.attribution" name="attribution">
          <template #label><span class="tab-label"><el-icon><DataAnalysis /></el-icon> 维度归因</span></template>
          <AttributionView :datasource-id="currentDsId" />
        </el-tab-pane>
        <el-tab-pane v-if="tabVisible.distill" name="distill">
          <template #label><span class="tab-label"><el-icon><Warning /></el-icon> 待治理问题</span></template>
          <IssueDistillPanel :datasource-id="currentDsId" @go-semantic="goSemantic" @go-metric="goMetric" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.tuning-view {
  height: 100%;
  overflow: auto;
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
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
.ds-picker {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ds-label {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 600;
}
.tabs-card {
  padding: 8px 18px 18px;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
</style>
