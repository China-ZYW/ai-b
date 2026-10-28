<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { issueDistillApi } from '@/api/issueDistill'
import { useConfigStore } from '@/stores/config'
import { usePerm } from '@/composables/usePerm'
import IssueConvertDrawer from './IssueConvertDrawer.vue'
import type { DistillReport, GovernanceIssue, GovernanceSignal } from '@/types'

/**
 * 待治理问题面板（T11-1 问题沉淀）：聚合问数历史，按治理信号归簇出「哪些问法值得沉淀」。
 *
 * 一期纯只读。二期 A 在行尾提供「转指标 / 转少样本」。二期 B 台账：
 * - 转写成功后自动调后端 convert 端点持久化，跨会话不再霸屏；
 * - 新增「忽略」按钮（只改台账状态、不改数据资产）；
 * - 「查看已处置」切换 + 「重新打开」按钮。
 *
 * T11-4 元数据巡检：顶栏「元数据巡检」按钮（随 METRIC/meta-patrol 开关显隐）；
 * 巡检行（META_* 信号）与蒸馏簇同表展示，但不提供转写（转写语义不适用），改给「去修复」跳治理页。
 *
 * T12-4 成本巡检：顶栏「成本巡检」按钮（随 METRIC/cost-patrol 开关显隐）；对窗口内成功历史聚簇出
 * 物化候选/慢查询/重复问法三类建议行进台账；成本行（COST_PATROL_*）走默认转写分支——转指标即物化出口。
 */
const props = defineProps<{ datasourceId: number | null }>()
const emit = defineEmits<{
  (e: 'go-semantic', issue: GovernanceIssue): void
  (e: 'go-metric', issue: GovernanceIssue): void
}>()
const configStore = useConfigStore()
const { can } = usePerm()

const loading = ref(false)
const report = ref<DistillReport | null>(null)
/** 是否显示已处置（CONVERTED/DISMISSED）的治理问题。 */
const includeHandled = ref(false)

/* ---------------- 二期写侧：转写抽屉 + 台账操作 ---------------- */
const drawerVisible = ref(false)
const drawerMode = ref<'metric' | 'fewshot'>('metric')
const activeIssue = ref<GovernanceIssue | null>(null)
/** 本会话已转写的归一键（乐观 UI；刷新后由服务端台账接管）。 */
const convertedKeys = ref<Set<string>>(new Set())

function openConvert(row: GovernanceIssue, mode: 'metric' | 'fewshot'): void {
  activeIssue.value = row
  drawerMode.value = mode
  drawerVisible.value = true
}
async function onConverted(payload: { mode: 'metric' | 'fewshot'; id?: number | null; refType?: string; refId?: number | null }): Promise<void> {
  // 乐观 UI：会话内置灰
  const key = activeIssue.value?.normalizedKey
  if (key) {
    const next = new Set(convertedKeys.value)
    next.add(key)
    convertedKeys.value = next
  }
  // 二期 B：调后端台账 convert 端点持久化
  const issue = activeIssue.value
  const dsId = issue?.jumpAnchor?.datasourceId as number | undefined ?? props.datasourceId ?? undefined
  if (issue?.normalizedKey && issue.signal && dsId != null && payload.refType && payload.refId != null) {
    try {
      await issueDistillApi.convert({
        datasourceId: dsId,
        normalizedKey: issue.normalizedKey,
        signal: issue.signal,
        refType: payload.refType,
        refId: payload.refId
      })
    } catch {
      // 台账写失败不阻断用户流程（抽屉已报成功）
    }
  }
}
function isConverted(row: GovernanceIssue): boolean {
  // 服务端状态优先 + 会话乐观置灰兜底
  if (row.status === 'CONVERTED' || row.status === 'DISMISSED') return true
  return !!row.normalizedKey && convertedKeys.value.has(row.normalizedKey)
}
/** AMBIGUOUS 非 SQL 问题：不转写，只通知父级切到语义层补术语/别名（不预填，见设计稿 §11(3)）。 */
function onGoSemantic(row: GovernanceIssue): void {
  emit('go-semantic', row)
}

/** T11-4：巡检行（META_* 信号）——资产缺陷，转写语义不适用，只给「去修复」跳治理页。 */
function isMeta(row: GovernanceIssue): boolean {
  return !!row.signal && row.signal.startsWith('META_')
}
/**
 * 去修复分派（设计稿 §6）：同名异口径的指标类（normalizedKey 含 |METRIC|）跳指标库；
 * 其余（幽灵语义行/注释缺失/别名冲突）跳语义管理。台账投影行无 kind 锚点，按归一键段判形。
 */
function onGoFix(row: GovernanceIssue): void {
  if (row.signal === 'META_SAME_NAME_DIFF_DEF' && (row.normalizedKey ?? '').includes('|METRIC|')) {
    emit('go-metric', row)
  } else {
    emit('go-semantic', row)
  }
}

/** T11-4：立即巡检——命中即时进台账，完成后刷新清单展示巡检行。 */
const patrolLoading = ref(false)
async function doPatrol(): Promise<void> {
  patrolLoading.value = true
  try {
    const r = await issueDistillApi.patrol(props.datasourceId ?? undefined)
    const hits = (r?.broken ?? 0) + (r?.diffDef ?? 0) + (r?.noComment ?? 0)
    if (r?.note) ElMessage.info(r.note)
    ElMessage.success(`巡检完成：扫 ${r?.scannedDatasources ?? 0} 个数据源，命中 ${hits} 条${(r?.failedDatasources ?? 0) > 0 ? `（${r?.failedDatasources} 个数据源不可达已跳过）` : ''}`)
    await load()
  } catch {
    /* 拦截器已提示（含门控关返业务码 40301） */
  } finally {
    patrolLoading.value = false
  }
}

/** T12-4：成本巡检——窗口内成功历史聚簇出三类建议，命中即时进台账，完成后刷新清单。 */
const costPatrolLoading = ref(false)
async function doCostPatrol(): Promise<void> {
  costPatrolLoading.value = true
  try {
    const r = await issueDistillApi.costPatrol(props.datasourceId ?? undefined)
    const hits = (r?.materialize ?? 0) + (r?.slow ?? 0) + (r?.dup ?? 0)
    if (r?.note) ElMessage.info(r.note)
    ElMessage.success(`成本巡检完成：扫近 ${r?.windowDays ?? 0} 天 ${r?.scannedRows ?? 0} 条历史，命中 ${hits} 条（物化 ${r?.materialize ?? 0} / 慢 ${r?.slow ?? 0} / 重复 ${r?.dup ?? 0}）`)
    await load()
  } catch {
    /* 拦截器已提示（含门控关返业务码 40301） */
  } finally {
    costPatrolLoading.value = false
  }
}

/** 二期 B：忽略某条治理建议。 */
async function doDismiss(row: GovernanceIssue): Promise<void> {
  const dsId = (row.jumpAnchor?.datasourceId as number | undefined) ?? props.datasourceId ?? undefined
  if (!row.normalizedKey || !row.signal || dsId == null) return
  try {
    await issueDistillApi.dismiss({ datasourceId: dsId, normalizedKey: row.normalizedKey, signal: row.signal })
    ElMessage.success('已忽略')
    void load() // 刷新清单
  } catch { /* 拦截器已提示 */ }
}

/** 二期 B：重新打开已处置的治理建议。 */
async function doReopen(row: GovernanceIssue): Promise<void> {
  if (!row.ledgerId) return
  try {
    await issueDistillApi.reopen({ id: row.ledgerId })
    ElMessage.success('已重新打开')
    void load()
  } catch { /* 拦截器已提示 */ }
}

/** 信号 → 中文标签（与后端 GovernanceSignal 语义对齐；T11-4 补巡检三值，T12-3 补结构变更）。 */
const SIGNAL_LABEL: Record<GovernanceSignal, string> = {
  THUMBS_DOWN: '用户点踩',
  COST_BLOCKED: '代价熔断',
  FAILED_HIGH: '高频失败',
  LOW_SCORE: '低质量分',
  AMBIGUOUS: '反复歧义',
  META_LINEAGE_BROKEN: '血缘断裂',
  META_SAME_NAME_DIFF_DEF: '同名不同口径',
  META_NO_COMMENT: '注释缺失',
  META_SCHEMA_CHANGED: '结构变更',
  COST_PATROL_MATERIALIZE: '物化候选',
  COST_PATROL_SLOW_SQL: '慢查询',
  COST_PATROL_DUP_QUERY: '重复问法'
}

/** 信号 → el-tag 配色（紧急度高的用危险/警告色；巡检行是资产缺陷用中性蓝/警告/提示色）。 */
const SIGNAL_TYPE: Record<GovernanceSignal, 'danger' | 'warning' | 'info' | 'primary'> = {
  THUMBS_DOWN: 'danger',
  COST_BLOCKED: 'warning',
  FAILED_HIGH: 'danger',
  LOW_SCORE: 'warning',
  AMBIGUOUS: 'info',
  META_LINEAGE_BROKEN: 'warning',
  META_SAME_NAME_DIFF_DEF: 'primary',
  META_NO_COMMENT: 'info',
  META_SCHEMA_CHANGED: 'warning',
  COST_PATROL_MATERIALIZE: 'primary',
  COST_PATROL_SLOW_SQL: 'warning',
  COST_PATROL_DUP_QUERY: 'info'
}

function signalLabel(s?: GovernanceSignal | null): string {
  return s ? SIGNAL_LABEL[s] ?? s : '—'
}

/** T11-2：点踩行用户修正线索的 tooltip 全文（说明 + 期望 SQL 截断展示）。 */
function userClueTip(row: GovernanceIssue): string {
  const parts: string[] = []
  if (row.userNote) parts.push(`口径说明：${row.userNote}`)
  if (row.userSuggestedSql) parts.push(`期望 SQL：${row.userSuggestedSql.slice(0, 300)}${row.userSuggestedSql.length > 300 ? '…' : ''}`)
  parts.push('转写抽屉已优先预填该建议，请复核后使用')
  return parts.join('\n')
}
function signalType(s?: GovernanceSignal | null): 'danger' | 'warning' | 'info' | 'primary' {
  return s ? SIGNAL_TYPE[s] ?? 'info' : 'info'
}

const issues = computed<GovernanceIssue[]>(() => report.value?.issues ?? [])
const stats = computed(() => report.value?.stats ?? null)
/** 时间窗起点格式化（去掉秒以下噪声），无则占位。 */
const sinceText = computed(() => {
  const s = report.value?.since
  if (!s) return '—'
  return s.replace('T', ' ').slice(0, 16)
})

async function load(): Promise<void> {
  loading.value = true
  try {
    report.value = await issueDistillApi.list(props.datasourceId ?? undefined, includeHandled.value)
  } catch {
    report.value = null
  } finally {
    loading.value = false
  }
}

watch(() => props.datasourceId, () => void load(), { immediate: true })
watch(includeHandled, () => void load())

/** 手动刷新按钮（与切换数据源触发同源）。 */
function onRefresh(): void {
  if (!configStore.issueDistillEnabled) {
    ElMessage.info('问题沉淀开关已关闭，请在「结果与策略配置」中开启')
    return
  }
  void load()
}
</script>

<template>
  <div class="distill-panel">
    <div class="panel-toolbar">
      <p class="tb-tip">
        聚合近期问数历史（默认 30 天窗口，可在配置中心 METRIC 分类调整），按治理信号归簇出「值得沉淀 / 需复核」的问法，每条附建议动作。
        纯只读，不影响线上问数。统计起点：<span class="mono">{{ sinceText }}</span>
      </p>
      <div class="tb-actions">
        <el-button
          v-if="configStore.metaPatrolEnabled"
          size="default"
          type="warning"
          plain
          :loading="patrolLoading"
          @click="doPatrol"
        >
          <el-icon><Search /></el-icon> 元数据巡检
        </el-button>
        <el-button
          v-if="configStore.costPatrolEnabled"
          size="default"
          type="primary"
          plain
          :loading="costPatrolLoading"
          @click="doCostPatrol"
        >
          <el-icon><Coin /></el-icon> 成本巡检
        </el-button>
        <el-button size="default" :loading="loading" @click="onRefresh">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="default" :type="includeHandled ? 'primary' : ''" @click="includeHandled = !includeHandled">
          {{ includeHandled ? '返回待办' : `查看已处置 (${stats?.handled ?? 0})` }}
        </el-button>
      </div>
    </div>

    <!-- 关闭态：开关关时后端返回空报告，给出明确说明而非「无数据」误导 -->
    <el-alert
      v-if="!configStore.issueDistillEnabled"
      class="disabled-hint"
      type="info"
      :closable="false"
      show-icon
      title="问题沉淀当前处于关闭状态"
      description="可在「结果与策略配置」tab 中开启 METRIC / issue-distill 开关后再查看待治理清单。"
    />

    <!-- 概览统计条 -->
    <div v-else-if="stats" class="stats-row">
      <el-tag effect="plain" size="small">扫描 {{ stats.scanned ?? 0 }} 条</el-tag>
      <el-tag effect="plain" size="small" type="danger">高频失败 {{ stats.failedClusters ?? 0 }}</el-tag>
      <el-tag effect="plain" size="small" type="warning">低质量分 {{ stats.lowScoreClusters ?? 0 }}</el-tag>
      <el-tag effect="plain" size="small" type="danger">点踩 {{ stats.thumbsDown ?? 0 }}</el-tag>
      <el-tag effect="plain" size="small" type="warning">代价熔断 {{ stats.costBlocked ?? 0 }}</el-tag>
      <el-tag effect="plain" size="small" type="info">歧义 {{ stats.ambiguous ?? 0 }}</el-tag>
      <el-tag v-if="stats.handled" effect="plain" size="small" type="success">已处置 {{ stats.handled }}</el-tag>
    </div>

    <el-table
      v-if="configStore.issueDistillEnabled"
      v-loading="loading"
      :data="issues"
      style="width: 100%"
      row-key="normalizedKey"
      border
      stripe
      size="small"
    >
      <el-table-column label="信号" width="108" align="center">
        <template #default="{ row }">
          <el-tag :type="signalType(row.signal)" effect="plain" size="small" round>
            {{ signalLabel(row.signal) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="question" label="代表性问法" min-width="220" show-overflow-tooltip />
      <el-table-column label="次数" width="72" align="center">
        <template #default="{ row }">{{ row.occurrences ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="技能" width="108" align="center">
        <template #default="{ row }">
          <span class="mono small">{{ row.skill || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="建议动作" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.suggestedAction || '—' }}</span>
          <!-- T11-2：点踩行附用户修正线索徽标（口径说明/期望 SQL），hover 看全文；转写抽屉会自动优先预填建议 -->
          <el-tooltip
            v-if="row.userNote || row.userSuggestedSql"
            :content="userClueTip(row)"
            placement="top"
          >
            <el-tag type="warning" effect="plain" size="small" class="user-clue-tag">👤 用户补充</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="失败样例" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="small err-sample">{{ row.errorMsgSample || '—' }}</span>
        </template>
      </el-table-column>
      <!-- 二期写侧：转写 + 台账操作列（随 METRIC/issue-distill-convert 开关显隐，按钮再按写权限双门禁） -->
      <el-table-column
        v-if="configStore.issueDistillConvertEnabled"
        label="操作"
        width="200"
        align="center"
        fixed="right"
      >
        <template #default="{ row }">
          <!-- 已处置状态标签（查看已处置模式时显示） -->
          <el-tag v-if="row.status === 'CONVERTED'" type="success" effect="plain" size="small">已转写</el-tag>
          <el-tag v-else-if="row.status === 'DISMISSED'" type="info" effect="plain" size="small">已忽略</el-tag>
          <!-- 已处置 + 可撤回 -->
          <el-button
            v-if="(row.status === 'CONVERTED' || row.status === 'DISMISSED') && row.ledgerId"
            size="small"
            text
            type="warning"
            @click="doReopen(row)"
          >重新打开</el-button>
          <!-- OPEN + 本会话已转写 -->
          <el-tag v-else-if="isConverted(row)" type="success" effect="plain" size="small">本会话已转写</el-tag>
          <!-- AMBIGUOUS -->
          <el-button
            v-else-if="row.signal === 'AMBIGUOUS'"
            size="small"
            text
            type="primary"
            @click="onGoSemantic(row)"
          >去补术语</el-button>
          <!-- T11-4 巡检行：不提供转写（转写语义不适用），给「去修复」跳对应治理页 -->
          <template v-else-if="isMeta(row)">
            <el-button
              size="small"
              text
              type="primary"
              @click="onGoFix(row)"
            >去修复</el-button>
            <el-button
              size="small"
              text
              type="info"
              @click="doDismiss(row)"
            >忽略</el-button>
          </template>
          <!-- 正常 OPEN 行：转写 + 忽略 -->
          <template v-else>
            <el-button
              v-if="can('metric:item:write')"
              size="small"
              text
              type="primary"
              @click="openConvert(row, 'metric')"
            >转指标</el-button>
            <el-button
              v-if="can('fewshot:example:write')"
              size="small"
              text
              @click="openConvert(row, 'fewshot')"
            >转少样本</el-button>
            <el-button
              size="small"
              text
              type="info"
              @click="doDismiss(row)"
            >忽略</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="loading ? '聚合中…' : '暂无待治理问题（该时间窗内未命中治理信号）'"
          :image-size="80"
        />
      </template>
    </el-table>

    <!-- 二期转写抽屉：自持可编辑 SQL + 信任分级警示，人工确认后走既有 create -->
    <IssueConvertDrawer
      v-model="drawerVisible"
      :mode="drawerMode"
      :issue="activeIssue"
      :datasource-id="props.datasourceId"
      @converted="onConverted"
    />
  </div>
</template>

<style scoped>
.distill-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.tb-tip {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  max-width: 720px;
}
.tb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stats-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.disabled-hint {
  margin-bottom: 4px;
}
.small {
  font-size: 12px;
}
.err-sample {
  color: var(--el-color-danger);
}
.user-clue-tag {
  margin-left: 6px;
  cursor: default;
}
</style>
