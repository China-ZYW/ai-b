<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { semanticApi } from '@/api/semantic'
import { dashboardApi } from '@/api/dashboard'
import { lineageApi } from '@/api/lineage'
import { datasourceApi } from '@/api/datasource'
import type { ColumnRole, FieldImpactVO, FieldReferenceVO, SemanticMeta, TableMeta } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'
import { usePerm } from '@/composables/usePerm'
import { useConfigStore } from '@/stores/config'
import { exportXlsx, fileStamp } from '@/utils/exportFile'

/** 导出当前筛选后的语义层数据（Excel）。 */
async function exportSemantic(): Promise<void> {
  const list = (filtered.value ?? []) as unknown as Array<Record<string, unknown>>
  if (!list.length) {
    ElMessage.warning('当前条件下无可导出的语义行')
    return
  }
  const columns = [
    { key: 'tableName', label: '表' },
    { key: 'columnName', label: '列' },
    { key: 'aliasCn', label: '别名' },
    { key: 'columnRole', label: '角色' },
    { key: 'synonyms', label: '同义词' },
    { key: 'sqlExpr', label: '口径表达式' },
    { key: 'enabled', label: '启用' }
  ]
  await exportXlsx(`语义层-${fileStamp()}`, columns, list, '语义层')
}

/**
 * 语义管理面板（配置化升级 V2）：为数据源的表/列维护业务语义（别名、角色、同义词、口径、枚举、单位），
 * 这些语义会注入 NL2SQL 的 Prompt / SchemaLinker 选表选列 / 图表角色判定，是问数准确率的核心抓手。
 *
 * 骨架由「初始化骨架」按钮或后端首次问数时自动补齐，管理员只需在此「补语义」。
 */
const props = defineProps<{ datasourceId: number | null }>()

const config = useConfigStore()
const { can } = usePerm()

const loading = ref(false)
const rows = ref<SemanticMeta[]>([])
/** 数据源当前表结构（已按表 ACL 过滤隐藏表）：表下拉的数据源之一，新同步的表即使还没有语义行也能出现在筛选里。 */
const schemaTables = ref<TableMeta[]>([])
const tableFilter = ref('')
const roleFilter = ref<'ALL' | 'TABLE' | 'COLUMN'>('ALL')

const ROLE_OPTIONS: { value: ColumnRole; label: string }[] = [
  { value: 'AUTO', label: '自动（不干预）' },
  { value: 'DIMENSION', label: '维度' },
  { value: 'MEASURE', label: '度量' },
  { value: 'TEMPORAL', label: '时间' }
]

function roleLabel(r?: string | null): string {
  return ROLE_OPTIONS.find((o) => o.value === r)?.label || '自动'
}

/** 可见角色选项（组织角色，与后端权限矩阵的角色码一致）。 */
const ORG_ROLE_OPTIONS = [
  { code: 'OWNER', label: '组织所有者' },
  { code: 'ADMIN', label: '组织管理员' },
  { code: 'DATA_STEWARD', label: '数据管理员' },
  { code: 'ANALYST', label: '分析师' },
  { code: 'VIEWER', label: '只读成员' },
  { code: 'BILLING', label: '计费联系人' }
]


async function load(): Promise<void> {
  if (props.datasourceId == null) {
    rows.value = []
    schemaTables.value = []
    return
  }
  loading.value = true
  try {
    // 语义行 + 数据源表结构并行加载；schema 拉取失败（如数据源暂时连不上）不影响语义行展示。
    const [semanticRes, schemaRes] = await Promise.allSettled([
      semanticApi.list(props.datasourceId),
      datasourceApi.schema(props.datasourceId)
    ])
    rows.value = semanticRes.status === 'fulfilled' ? semanticRes.value : []
    schemaTables.value = schemaRes.status === 'fulfilled' ? schemaRes.value : []
  } finally {
    loading.value = false
  }
}

watch(() => props.datasourceId, () => void load(), { immediate: true })

/** 表名下拉（去重）：语义行已有表 ∪ 数据源 schema 现有表，保证新同步/新登记的表立即可筛选。 */
const tableOptions = computed(() => {
  const set = new Set<string>()
  schemaTables.value.forEach((t) => {
    if (t.name) set.add(t.name)
  })
  rows.value.forEach((r) => {
    if (r.tableName) set.add(r.tableName)
  })
  return Array.from(set).sort()
})

const filtered = computed(() =>
  rows.value.filter((r) => {
    if (roleFilter.value !== 'ALL' && r.objectType !== roleFilter.value) return false
    if (tableFilter.value && r.tableName !== tableFilter.value) return false
    return true
  })
)

/* ---------------- 初始化骨架 ---------------- */
const initializing = ref(false)
async function onInit(): Promise<void> {
  if (props.datasourceId == null) return
  initializing.value = true
  try {
    const n = await semanticApi.init(props.datasourceId)
    ElMessage.success(n > 0 ? `已补齐 ${n} 条语义骨架` : '语义骨架已是最新，无需补充')
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    initializing.value = false
  }
}

/* ---------------- 指标自动分析（Skill） ---------------- */
const analyzing = ref(false)
async function onAutoAnalysis(): Promise<void> {
  if (props.datasourceId == null) return
  analyzing.value = true
  try {
    const r = await semanticApi.autoAnalysis(props.datasourceId)
    if (r.submitted) {
      ElMessage.success('已提交后台，指标正在自动分析中，稍后刷新指标库查看')
    } else {
      ElMessage.success(
        `已自动分析：${r.tables} 表 / ${r.columns} 列，新建 ${r.metrics} 个指标、${r.groups} 个分组`
      )
    }
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    analyzing.value = false
  }
}

/* ---------------- AI 全库别名补全（Skill） ---------------- */
const completingAliases = ref(false)
async function onCompleteAliases(): Promise<void> {
  if (props.datasourceId == null) return
  completingAliases.value = true
  try {
    const r = await semanticApi.completeAliases(props.datasourceId)
    if (r.submitted) {
      ElMessage.success('已提交后台，AI 正在全库推理中文别名，稍后刷新查看')
    } else {
      ElMessage.success(`别名补全：扫描 ${r.tables} 表 / ${r.columns} 列，补全 ${r.filled} 条`)
    }
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    completingAliases.value = false
  }
}

/* ---------------- 驾驶舱 AI 自动化大屏（Skill） ---------------- */
const building = ref(false)
async function onAiBuild(): Promise<void> {
  if (props.datasourceId == null) return
  let intent: string | undefined
  try {
    const input = await ElMessageBox.prompt('可选：给 AI 大屏一些风格要求（如「科技感深色、给领导看的经营看板」），留空则按默认', '生成 AI 大屏', {
      confirmButtonText: '生成',
      cancelButtonText: '取消',
      inputPlaceholder: '风格指令（可空）',
      inputType: 'textarea'
    })
    intent = input.value?.trim() || undefined
  } catch {
    return // 用户取消
  }
  building.value = true
  try {
    const r = await dashboardApi.aiBuild(props.datasourceId, intent)
    ElMessage.success(
      `已生成 AI 大屏（${r.widgets} 个组件，方案：${r.source === 'llm' ? 'AI 布局增强' : '确定性基线'}）` +
        (r.summary ? `：${r.summary}` : '')
    )
  } catch {
    // 拦截器已提示
  } finally {
    building.value = false
  }
}

/* ---------------- 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const editing = reactive<SemanticMeta>({})

/** 可见角色字符串 ↔ 数组：el-select multiple 需要数组，落库为逗号分隔串。 */
const visibleRoleArr = computed<string[]>({
  get: () => {
    const v = editing.visibleRoles
    return v ? v.split(',').map((s) => s.trim()).filter(Boolean) : []
  },
  set: (val: string[]) => {
    editing.visibleRoles = val.join(',')
  }
})

/** 枚举字典的一行：码值 → 中文标签（如 1 → 已支付）。 */
interface EnumPair {
  code: string
  label: string
}

/** 抽屉里「枚举字典」的临时编辑态（保存时才序列化回 enumLabels，避免直接改语义行）。 */
const enumDictPairs = ref<EnumPair[]>([])

/** enum_labels JSON → 编辑行；非法 JSON 静默回落空数组（不打断打开抽屉）。 */
function parseEnumDict(json?: string | null): EnumPair[] {
  if (!json) return []
  try {
    const o = JSON.parse(json)
    if (!o || typeof o !== 'object' || Array.isArray(o)) return []
    return Object.entries(o as Record<string, unknown>).map(([code, label]) => ({
      code,
      label: label == null ? '' : String(label)
    }))
  } catch {
    return []
  }
}

/** 编辑行 → enum_labels JSON；一行都没填则返回空串（等于清空字典）。 */
function enumPairsToJson(): string {
  const kept = enumDictPairs.value.filter((p) => p.code.trim() !== '' && p.label.trim() !== '')
  if (!kept.length) return ''
  const obj: Record<string, string> = {}
  kept.forEach((p) => {
    obj[p.code.trim()] = p.label.trim()
  })
  return JSON.stringify(obj)
}

function addEnumPair(): void {
  enumDictPairs.value.push({ code: '', label: '' })
}

function removeEnumPair(idx: number): void {
  enumDictPairs.value.splice(idx, 1)
}

function openEdit(r: SemanticMeta): void {
  Object.assign(editing, {
    id: r.id,
    datasourceId: r.datasourceId,
    objectType: r.objectType,
    tableName: r.tableName,
    columnName: r.columnName,
    aliasCn: r.aliasCn ?? '',
    description: r.description ?? '',
    synonyms: r.synonyms ?? '',
    columnRole: (r.columnRole as ColumnRole) ?? 'AUTO',
    unit: r.unit ?? '',
    enumValues: r.enumValues ?? '',
    sqlExpr: r.sqlExpr ?? '',
    sensitive: r.sensitive ?? 0,
    visibleRoles: r.visibleRoles ?? '',
    enabled: r.enabled ?? 1
  })
  enumDictPairs.value = parseEnumDict(r.enumLabels)
  drawerVisible.value = true
}

/**
 * P1「血缘变更影响提示」：列字段修改别名/口径/列角色前，先评估血缘影响面。
 *
 * 影响面非 0 时弹确认框列出受影响指标与看板，用户确认后才保存；
 * 评估接口异常（如缺血缘读权限）不阻断保存，静默放过。
 */
function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string)
  )
}

async function confirmImpactIfNeeded(): Promise<boolean> {
  if (editing.objectType !== 'COLUMN' || props.datasourceId == null) return true
  if (!editing.tableName || !editing.columnName || editing.id == null) return true
  let impact: FieldImpactVO
  try {
    impact = await lineageApi.impact(props.datasourceId, editing.tableName, editing.columnName)
  } catch {
    return true // 评估失败不阻断保存
  }
  if (!impact || (impact.total ?? 0) <= 0) return true
  const metricList = (impact.metrics || []).map((m) => escapeHtml(m.name || `指标#${m.id}`))
  const dashList = (impact.dashboards || []).map((d) => escapeHtml(d.name || `看板#${d.id}`))
  const metricText = metricList.length ? metricList.join('、') : '（无）'
  const dashText = dashList.length ? dashList.join('、') : '（无）'
  const msg =
    `该字段被 <b>${impact.metrics?.length ?? 0}</b> 个指标、<b>${
      impact.dashboards?.length ?? 0
    }</b> 个看板引用，修改别名 / 口径 / 列角色可能影响其计算结果：<br>` +
    `<b>指标：</b>${metricText}<br><b>看板：</b>${dashText}<br><br>确认仍要保存？`
  try {
    await ElMessageBox.confirm(msg, '血缘变更影响提示', {
      type: 'warning',
      confirmButtonText: '仍要保存',
      cancelButtonText: '取消',
      dangerouslyUseHTMLString: true
    })
    return true
  } catch {
    return false // 用户取消
  }
}

async function onSubmit(): Promise<void> {
  if (editing.id == null) return
  const ok = await confirmImpactIfNeeded()
  if (!ok) return
  await runSubmit(async () => {

    // 枚举字典由编辑行序列化后一并提交（空串=清空映射）
    editing.enumLabels = enumPairsToJson()
    await semanticApi.update({ ...editing })
    ElMessage.success('语义已保存')
    drawerVisible.value = false
    await load()
  })
}

/* ---------------- 枚举采样 ---------------- */
/**
 * 字段级血缘：查看该字段被哪些指标引用（需求 §3.3「被引用指标」列）。
 *
 * 点击时才加载（不在列表渲染时并发拉所有字段的引用数），避免语义层字段较多时把血缘接口打爆；
 * 无血缘读权限时不渲染该列。
 */
const refVisible = ref(false)
const refLoading = ref(false)
const refFieldId = ref<number | null>(null)
const refData = ref<FieldReferenceVO | null>(null)

async function openReferences(r: SemanticMeta): Promise<void> {
  if (r.id == null) return
  refFieldId.value = r.id
  refVisible.value = true
  refLoading.value = true
  refData.value = null
  try {
    refData.value = await lineageApi.fieldReferences(r.id)
  } catch (e: any) {
    ElMessage.error(e?.message || '血缘加载失败')
  } finally {
    refLoading.value = false
  }
}

const samplingId = ref<number | null>(null)
async function onSample(r: SemanticMeta): Promise<void> {
  if (props.datasourceId == null || !r.tableName || !r.columnName || r.id == null) return
  samplingId.value = r.id
  try {
    // 后端返回 { values, numeric, backfilled, reason }，数值码列未配字典时 backfilled=false
    const res = await semanticApi.sampleEnums(props.datasourceId, r.tableName, r.columnName)
    const values = res?.values ?? []
    if (values.length === 0) {
      ElMessage.info('未采样到枚举值（可能取值过多或列为空）')
      return
    }
    // 数值码列（如 status 存 1/2/3/4）采样到的是一串裸码值，回填进 Prompt 也解释不了含义；
    // 后端此时会跳过回填，这里明确引导管理员去配「枚举字典」，避免让人以为采样坏了。
    if (res && res.backfilled === false) {
      ElMessage.success(
        `采样到 ${values.length} 个取值，但这是数值码列且未配字典，已跳过回填。` +
          '请在编辑里补录「枚举字典」（1=已支付 这类映射），模型与问数结果才能理解码值含义'
      )
    } else {
      ElMessage.success(`已回填 ${values.length} 个枚举值`)
    }
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    samplingId.value = null
  }
}

/* ---------------- 一键全部采样枚举 ---------------- */
const samplingAll = ref(false)
async function onSampleAll(): Promise<void> {
  if (props.datasourceId == null) return
  try {
    await ElMessageBox.confirm(
      '将对当前数据源下全部「维度 / 时间」列执行枚举值采样（自动跳过度量列），可能耗时数秒，继续？',
      '一键全部采样枚举',
      { type: 'info', confirmButtonText: '开始采样', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  samplingAll.value = true
  try {
    const r = await semanticApi.sampleAllEnums(props.datasourceId)
    const parts: string[] = []
    if (r.sampled) parts.push(`采样到枚举值 ${r.sampled} 列`)
    if (r.empty) parts.push(`${r.empty} 列为空跳过`)
    if (r.skipped) parts.push(`跳过度量列 ${r.skipped}`)
    if (r.numeric) parts.push(`${r.numeric} 列为数值码列（已跳过回填，可配枚举字典）`)
    if (r.failed) parts.push(`${r.failed} 列采样失败`)
    ElMessage.success(`全部采样完成（共 ${r.total} 列）：${parts.join('，') || '无更新'}`)
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    samplingAll.value = false
  }
}

/* ---------------- 删除 ---------------- */
async function onRemove(r: SemanticMeta): Promise<void> {
  if (r.id == null) return
  try {
    await ElMessageBox.confirm(
      `确定删除「${r.tableName}${r.columnName ? '.' + r.columnName : ''}」的语义行吗？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await semanticApi.remove(r.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

function shortEnum(v?: string | null): string {
  if (!v) return '—'
  return v.length > 40 ? v.slice(0, 40) + '…' : v
}

/** 枚举列展示：配了字典就优先显示「码值=中文」摘要，一眼看出 1 到底代表什么。 */
function enumCell(row: SemanticMeta): string {
  return shortEnum(row.enumLabels) || shortEnum(row.enumValues)
}
</script>

<template>
  <div class="semantic-panel">
    <div class="panel-toolbar">
      <div class="tb-filters">
        <el-select v-model="roleFilter" style="width: 130px" size="default">
          <el-option label="全部对象" value="ALL" />
          <el-option label="仅表" value="TABLE" />
          <el-option label="仅列" value="COLUMN" />
        </el-select>
        <el-select
          v-model="tableFilter"
          placeholder="按表筛选"
          clearable
          style="width: 200px"
          size="default"
        >
          <el-option v-for="t in tableOptions" :key="t" :label="t" :value="t" />
        </el-select>
      </div>
      <div class="tb-actions">
        <el-button size="default" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button
          type="primary"
          size="default"
          :disabled="datasourceId == null"
          :loading="initializing"
          @click="onInit"
        >
          <el-icon><MagicStick /></el-icon> 初始化骨架
        </el-button>
        <el-button
          size="default"
          :disabled="datasourceId == null"
          :loading="samplingAll"
          @click="onSampleAll"
        >
          <el-icon><Collection /></el-icon> 一键全部采样
        </el-button>
        <el-button
          v-if="can('semantic:meta:auto-analysis')"
          type="primary"
          size="default"
          :disabled="datasourceId == null"
          :loading="completingAliases"
          @click="onCompleteAliases"
        >
          <el-icon><EditPen /></el-icon> AI 别名补全
        </el-button>
        <el-button
          v-if="can('semantic:meta:auto-analysis') && config.metricAutoAnalysisEnabled"
          type="success"
          size="default"
          :disabled="datasourceId == null"
          :loading="analyzing"
          @click="onAutoAnalysis"
        >
          <el-icon><MagicStick /></el-icon> 指标自动分析
        </el-button>
        <el-button size="default" :disabled="!filtered.length" @click="exportSemantic">
          <el-icon><Download /></el-icon> 导出 Excel
        </el-button>
        <el-button
          v-if="can('dashboard:cockpit:ai') && config.cockpitAiDashboardEnabled"
          type="warning"
          size="default"
          :disabled="datasourceId == null"
          :loading="building"
          @click="onAiBuild"
        >
          <el-icon><MagicStick /></el-icon> 生成 AI 大屏
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="datasourceId == null"
      type="info"
      :closable="false"
      show-icon
      title="请先在上方选择数据源"
      class="mb12"
    />

    <el-table v-loading="loading" :data="filtered" style="width: 100%" row-key="id" border stripe size="small">
      <el-table-column label="对象" width="72" align="center">
        <template #default="{ row }">
          <el-tag :type="row.objectType === 'TABLE' ? 'warning' : 'info'" effect="plain" size="small" round>
            {{ row.objectType === 'TABLE' ? '表' : '列' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标识" width="60" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.source === 'AI'" type="success" effect="dark" size="small" round>AI</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="tableName" label="表" min-width="130" show-overflow-tooltip />
      <el-table-column label="列" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.columnName || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="aliasCn" label="别名" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.aliasCn || '—' }}</template>
      </el-table-column>
      <el-table-column label="角色" width="96" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.columnRole && row.columnRole !== 'AUTO'" type="success" effect="light" size="small" round>
            {{ roleLabel(row.columnRole) }}
          </el-tag>
          <span v-else class="muted">自动</span>
        </template>
      </el-table-column>
      <el-table-column prop="synonyms" label="同义词" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.synonyms || '—' }}</template>
      </el-table-column>
      <el-table-column prop="sqlExpr" label="口径表达式" min-width="140" show-overflow-tooltip>
        <template #default="{ row }"><span class="mono small">{{ row.sqlExpr || '—' }}</span></template>
      </el-table-column>
      <el-table-column label="枚举" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.enumLabels" class="mono small enum-with-dict">{{ enumCell(row) }}</span>
          <span v-else class="mono small">{{ shortEnum(row.enumValues) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="被引用指标" width="92" align="center">
        <template #default="{ row }">
          <el-button
            v-if="row.objectType === 'COLUMN'"
            size="small"
            text
            :loading="refLoading && refFieldId === row.id"
            @click="openReferences(row)"
          >
            <el-icon><Connection /></el-icon> 血缘
          </el-button>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="字段权限" width="88" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.sensitive === 1" type="danger" effect="light" size="small" round>敏感</el-tag>
          <span v-else class="muted">普通</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="66" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 0 ? 'info' : 'success'" effect="plain" size="small" round>
            {{ row.enabled === 0 ? '停用' : '启用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="196" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
          <el-button
            v-if="row.objectType === 'COLUMN'"
            size="small"
            text
            :loading="samplingId === row.id"
            @click="onSample(row)"
          >
            <el-icon><Download /></el-icon> 采样枚举
          </el-button>
          <el-button size="small" text type="danger" @click="onRemove(row)"><el-icon><Delete /></el-icon></el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无语义行，点击「初始化骨架」生成" :image-size="80" />
      </template>
    </el-table>

    <!-- 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" title="编辑语义" size="480px">
      <el-form :model="editing" label-width="92px">
        <el-form-item label="对象">
          <span class="mono">
            {{ editing.tableName }}<template v-if="editing.columnName">.{{ editing.columnName }}</template>
          </span>
        </el-form-item>
        <el-form-item label="中文别名">
          <el-input v-model="editing.aliasCn" placeholder="例如：销售额 / 下单时间" maxlength="128" clearable />
        </el-form-item>
        <el-form-item label="业务描述">
          <el-input
            v-model="editing.description"
            type="textarea"
            :rows="2"
            placeholder="补充该表/列的业务含义，帮助模型理解"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="同义词">
          <el-input
            v-model="editing.synonyms"
            type="textarea"
            :rows="2"
            placeholder="多个用逗号分隔，例如：金额,总额,营收"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item v-if="editing.objectType === 'COLUMN'" label="列角色">
          <el-select v-model="editing.columnRole" style="width: 100%">
            <el-option v-for="o in ROLE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <span class="field-hint muted">指定后图表引擎将直接采信，先于启发式判断</span>
        </el-form-item>
        <el-form-item v-if="editing.objectType === 'COLUMN'" label="度量单位">
          <el-input v-model="editing.unit" placeholder="例如：元 / 件 / % / 次 / 人" maxlength="16" clearable />
          <span class="field-hint muted">
            度量列填写后，图表轴名与数值标注自动带单位（如“销售额(元)”、“1.5万元”），一处维护全租户图表复用；
            置空则回落按列名关键词推断。
          </span>
        </el-form-item>
        <el-form-item label="口径表达式">
          <el-input
            v-model="editing.sqlExpr"
            placeholder="业务口径 SQL 片段，例如：unit_price * quantity"
            maxlength="500"
            clearable
          />
        </el-form-item>
        <el-form-item label="枚举值">
          <el-input
            v-model="editing.enumValues"
            type="textarea"
            :rows="3"
            placeholder='JSON 数组，例如：["已支付","已取消"]；可用「采样枚举」自动回填（数值码列默认不回填）'
          />
        </el-form-item>
        <el-form-item v-if="editing.objectType === 'COLUMN'" label="枚举字典">
          <div class="enum-dict-editor">
            <div v-for="(p, idx) in enumDictPairs" :key="idx" class="enum-pair">
              <el-input v-model="p.code" class="enum-code" placeholder="码值，如 1" maxlength="32" clearable />
              <span class="muted">=</span>
              <el-input
                v-model="p.label"
                class="enum-label"
                placeholder="中文标签，如 已支付"
                maxlength="64"
                clearable
              />
              <el-button size="small" text type="danger" @click="removeEnumPair(idx)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text @click="addEnumPair()">
              <el-icon><Plus /></el-icon> 添加一行
            </el-button>
          </div>
          <span class="field-hint muted">
            数字编码列（status 存 1/2/3/4）必填：配了字典，Prompt 才会输出「枚举:1,2,3,4；字典:1=已支付，2=已取消」，
            模型据此正确理解与过滤；留空则等同于未配置。
          </span>
        </el-form-item>
        <el-form-item v-if="editing.objectType === 'COLUMN'" label="敏感列">
          <el-switch v-model="editing.sensitive" :active-value="1" :inactive-value="0" />
          <span class="field-hint muted">
            开启后，该列在问数结果、历史回放、指标刷新与公开大屏中对无权限角色显示 ***（配置端→消费端闭环）。
          </span>
        </el-form-item>
        <el-form-item v-if="editing.objectType === 'COLUMN' && editing.sensitive === 1" label="可见角色">
          <el-select
            v-model="visibleRoleArr"
            multiple
            clearable
            collapse-tags
            placeholder="不选=仅组织所有者/管理员可见"
            style="width: 100%"
          >
            <el-option
              v-for="o in ORG_ROLE_OPTIONS"
              :key="o.code"
              :label="`${o.label}（${o.code}）`"
              :value="o.code"
            />
          </el-select>
          <span class="field-hint muted">未选择任何角色时，仅 OWNER/ADMIN 可见该列真实值。</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editing.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-drawer>

    <!-- 字段血缘浮层：该字段被哪些指标引用（需求 §3.3） -->
    <el-dialog v-model="refVisible" title="字段血缘 · 被引用指标" width="480px">
      <div v-loading="refLoading">
        <p v-if="refData" class="ref-head muted">
          <span class="mono">{{ refData.table }}.{{ refData.column }}</span>
          <template v-if="refData.label"> · {{ refData.label }}</template>
        </p>
        <el-empty
          v-if="refData && !refData.metrics?.length"
          description="尚无指标引用该字段（指标保存口径后自动生成血缘）"
          :image-size="64"
        />
        <el-table v-if="refData?.metrics?.length" :data="refData.metrics" size="small" border>
          <el-table-column prop="name" label="指标" min-width="160" show-overflow-tooltip />
          <el-table-column prop="id" label="ID" width="72" align="center" />
        </el-table>
        <p v-if="refData" class="ref-tail muted">共 {{ refData.count ?? 0 }} 个指标引用该字段。</p>
      </div>
      <template #footer>
        <el-button size="small" @click="refVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.semantic-panel {
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
.tb-filters,
.tb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: nowrap;   /* 按钮排永远一行：窄屏横向滚动，绝不折行 */
  overflow-x: auto;
  min-width: 0;
  scrollbar-width: thin;
  padding-bottom: 2px; /* 防止 focus 阴影被 overflow 裁掉 */
}
.mb12 {
  margin-bottom: 0;
}
.small {
  font-size: 12px;
}
.field-hint {
  font-size: 12px;
}
/* 枚举字典编辑器：码值=中文标签 逐行排列，宽抽屉里不必和 JSON 文本较劲 */
.enum-dict-editor {
  width: 100%;
}
.enum-pair {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.enum-code {
  width: 140px;
  flex: none;
}
.enum-label {
  flex: 1;
}
.enum-with-dict {
  color: var(--el-color-primary, #409eff);
}
</style>
