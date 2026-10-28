<script setup lang="ts">
/**
 * 高级查询 / SQL 编辑器（P1 + P2）。
 *
 * <b>能力边界</b>：用户手写只读 SQL → 后端做「单条只读校验 → 表级 ACL 拦截 → 执行 → 字段脱敏 → 图型推荐」。
 * 与对话问数共用同一条内核，因此<b>看不见的表与需要脱敏的字段在这里同样逃不掉</b>；
 * 但它<b>不消耗大模型、不占用问数配额</b>——编辑器的定位是"确定的逃生通道"。
 *
 * <b>右侧三条并列面板</b>：查询模板（保存/复用/带参数）、参数表单（模板 {@code :param} 绑定）、
 * 执行历史（最近 50 条，可一键回填编辑器）。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Setting, MagicStick, Download, TrendCharts, Grid as GridIcon } from '@element-plus/icons-vue'
import SqlEditor from '@/components/query/SqlEditor.vue'
import SchemaExplorer from '@/components/query/SchemaExplorer.vue'
import QueryResultGrid from '@/components/query/QueryResultGrid.vue'
import ChartPicker from '@/components/query/ChartPicker.vue'
import ChartStyleForm from '@/components/chart/ChartStyleForm.vue'
import QueryBuilder from '@/components/query/QueryBuilder.vue'
import BaseChart from '@/components/chart/BaseChart.vue'
import { buildChartOption } from '@/composables/useChartOption'
import { useConfigStore } from '@/stores/config'
import { usePermissionStore } from '@/stores/permission'
import { useUserStore } from '@/stores/user'
import { datasourceApi } from '@/api/datasource'
import {
  builderRun,
  chartTypes,
  deleteTemplate,
  listTemplates,
  queryHistory,
  runSql,
  runTemplate,
  saveTemplate,
  setTemplateVisibility,
  updateTemplate,
  type BuilderRunRequest,
  type BuilderSpec,
  type SavedQueryVO
} from '@/api/query'
import { exportXlsx, exportChartPng, gridToExport, fileStamp } from '@/utils/exportFile'
import { formatSql } from '@/utils/sqlFormat'
import { pruneChartStyle } from '@/utils/chartStyle'
import { usePerm } from '@/composables/usePerm'
import MetricSaveDialog from '@/components/chat/MetricSaveDialog.vue'
import ReportFormDialog from '@/views/report/ReportFormDialog.vue'
import type { ChartSpec, ChartStyle, QueryResult } from '@/types'
import type { QueryRunVO, QueryRecordVO } from '@/api/query'

const datasources = ref<{ id: number; name: string }[]>([])
const dsId = ref<number | null>(null)
const sql = ref('-- 选择数据源后即可运行只读 SQL\nSELECT 1 AS ok')
const maxRows = ref<number>(10000)
const chartType = ref<string | null>(null)
const running = ref(false)

const run = ref<QueryRunVO | null>(null)
const err = ref<string>('')
const mode = ref<'table' | 'chart'>('table')

// ============ 构造器 ============
/**
 * 编辑器页的两个 tab：手写 SQL / 可视化构造器。
 * 两者共享同一条执行内核与同一下方结果区，只在"标识符从哪来"上不同。
 */
const tab = ref<'sql' | 'builder'>('sql')
/** 构造器最近一次预览出来的 SQL；构造器 tab 下保存模板时用它，避免把空串存成"标准答案"。 */
const builderSql = ref<string>('')
const builderRef = ref<InstanceType<typeof QueryBuilder> | null>(null)

// ============ 模板 ============
const templates = ref<SavedQueryVO[]>([])
const tplId = ref<number | null>(null)
// 定时报表入口（T12-1）：模板行「定时」→ 弹层预选该模板；门控 ASK/schedule 关则隐藏入口
const scheduleVisible = ref(false)
const scheduleTplId = ref<number | null>(null)
// 组织共享模板（T12-6）：门控 ASK/shared-templates 仅收入口面，执行面数据驱动不受门控约束
const perm = usePermissionStore()
const userStore = useUserStore()
/** 可切换共享：属主本人或组织管理员（OWNER/ADMIN），与后端 canManageMembers 同判据 */
const canManageShare = computed<boolean>(() => perm.hasRole('OWNER', 'ADMIN'))
/** 是否本人属主模板（存量行 ownerUid 为空时保守当真，后端仍双重把关）。非属主隐藏删除/覆盖编辑 */
const isOwnedTpl = (t: SavedQueryVO): boolean =>
  t.ownerUid == null || t.ownerUid === userStore.profile?.id
const newName = ref('')
const newDesc = ref('')
/**
 * 正在覆盖更新的模板 id；null 表示"另存为新模板"。
 * 选中一个模板即进入"更新"态（改 SQL 是最常见的二次动作），此时名称/说明预填原值，
 * 用户可以直接点保存覆盖，也可以另存一份副本。
 */
const editingId = ref<number | null>(null)
const params = ref<Record<string, string>>({})

// ============ 历史 ============
const history = ref<QueryRecordVO[]>([])
const showHistory = ref(false)

const runResult = computed<QueryResult | null>(() => run.value?.result ?? null)

/**
 * 图表规格：<b>用户干预优先，未干预时跟随推荐</b>。
 * {@code chartOverride} 为 null 表示"跟着后端推荐走"；用户一改图型/字段即落到这里，
 * 覆盖本次推荐的 {@code chart}。这样"什么都没动"的结果与对话问数链路完全一致。
 */
const chartOverride = ref<ChartSpec | null>(null)
/**
 * 图表标注覆盖（轴名/单位/数值格式/图例），与问数结果面板同一套会话级机制。
 * null 表示跟随后端 chart_spec 自带样式与租户级默认；用户一改即落到这里。
 */
const styleOverride = ref<ChartStyle | null>(null)
const hasStyleOverride = computed(() => pruneChartStyle(styleOverride.value) !== null)
const config = useConfigStore()
const chartSpec = computed<ChartSpec | null>(() => chartOverride.value ?? run.value?.chart ?? null)

// 按钮级权限门禁（与问数结果面板同源）：存为指标需 metric:item:write 权限点
const { can } = usePerm()
// 存为指标对话框显隐
const showSaveMetric = ref(false)
/**
 * 当前结果对应、可存为指标的 SQL：SQL 编辑器取编辑器内容，构造器取最近一次预览生成的 SQL。
 * 与「保存模板」同源（onSaveTemplate 同款取法）；构造器未预览过时为空，按钮因此隐藏，避免存空 SQL。
 */
const currentSql = computed(() => (tab.value === 'builder' ? builderSql.value : sql.value).trim())
const chartOption = computed(() =>
  chartSpec.value && runResult.value
    ? buildChartOption(chartSpec.value, runResult.value, undefined, styleOverride.value, config.chartDefaults)
    : null
)
/** 图表实例引用：下载 PNG 用（BaseChart 通过 getDataURL 暴露）。 */
const chartRef = ref<{ getDataURL?: (t?: 'png' | 'jpeg', bg?: string) => string | null } | null>(null)

/** 图表标注变更（来自 ChartStyleForm 的草稿去抖上抛）。 */
const onStyleChange = (s: ChartStyle | null) => {
  styleOverride.value = s
}
/** 下载图表为 PNG，与问数结果面板一致。 */
const onDownloadChart = () => {
  if (!exportChartPng(() => chartRef.value?.getDataURL?.() ?? null, `查询结果-${fileStamp()}`)) {
    ElMessage.warning('图表尚未渲染，无法下载')
  }
}
/** 导出明细数据（Excel；导出全部行，码值保持原始、便于与库对账），与问数一致。 */
const onExportData = async () => {
  const r = runResult.value
  if (!r) return
  const g = gridToExport(r.columns, r.rows, r.columnLabels ?? undefined)
  await exportXlsx(`查询结果-${fileStamp()}`, g.columns, g.rows)
}
// 新一轮结果：丢弃上一轮人工干预的图型/标注覆盖，避免张冠李戴（与问数 reset 逻辑一致）
watch(() => run.value, () => {
  chartOverride.value = null
  styleOverride.value = null
})
const elapsed = computed(() => (run.value ? (run.value.result.executionTimeMs ?? 0) : 0))

/** 图型下拉：清单来自后端注册表，前端不硬编码。 */
const chartTypeOptions = ref<{ code: string; displayName: string }[]>([])
const loadChartTypes = async () => {
  try {
    chartTypeOptions.value = (await chartTypes()) ?? []
  } catch {
    chartTypeOptions.value = []
  }
}

const paramsOf = computed<string[]>(
  () => templates.value.find((t) => t.id === tplId.value)?.paramNames ?? []
)

const extractParams = (text: string): string[] => {
  const out = new Set<string>()
  const re = /(?:^|[\s(,]):([A-Za-z_][\w]*)/g
  let m: RegExpExecArray | null
  // 逐行剔除注释后再扫，避免注释里的 :name 变成"必填参数"
  const clean = text.replace(/--[^\n]*/g, '')
  while ((m = re.exec(clean)) !== null) out.add(m[1])
  return [...out]
}

const trackParams = () => {
  const ps = extractParams(sql.value)
  if (tplId.value == null) {
    params.value = Object.fromEntries(ps.map((p) => [p, '']))
    return
  }
  // 模板模式：沿用模板声明的参数，保留用户已填的值
  const next: Record<string, string> = {}
  for (const p of paramsOf.value) next[p] = params.value[p] ?? ''
  params.value = next
}

const loadDatasources = async () => {
  try {
    const list = await datasourceApi.list()
    datasources.value = (list ?? []).map((d) => ({ id: d.id, name: d.name }))
    if (!dsId.value && datasources.value.length) dsId.value = datasources.value[0].id
  } catch {
    datasources.value = []
  }
}

const loadTemplates = async () => {
  try {
    templates.value = (await listTemplates(dsId.value)) ?? []
  } catch {
    templates.value = []
  }
}

const loadHistory = async () => {
  try {
    history.value = (await queryHistory({ datasourceId: dsId.value ?? null, limit: 50 })) ?? []
  } catch {
    history.value = []
  }
}

onMounted(() => {
  void loadDatasources()
  void loadTemplates()
  void loadHistory()
  void loadChartTypes()
  void config.load() // 门控开关（如 ASK/schedule）需合并视图就绪后才可靠
  trackParams()
})

watch(dsId, () => {
  tplId.value = null
  void loadTemplates()
  void loadHistory()
})

const onRun = async () => {
  if (!dsId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  const body = sql.value.trim()
  if (!body) {
    ElMessage.warning('SQL 不能为空')
    return
  }
  running.value = true
  err.value = ''
  run.value = null
  chartOverride.value = null
  try {
    run.value = await runSql({ datasourceId: dsId.value, sql: body, maxRows: maxRows.value, chartType: chartType.value })
    mode.value = chartOption.value ? 'chart' : 'table'
    await loadHistory()
  } catch (e: unknown) {
    err.value = (e as { response?: { data?: { message?: string } }; message?: string })?.response?.data?.message
      ?? (e as Error).message ?? '执行失败'
  } finally {
    running.value = false
  }
}

// ============ 表结构（点字段即插入编辑器） ============
/**
 * 左栏点字段往编辑器里插文本。
 * 点行插列名、点「+ 限定名」插 {@code 表名.列名}——多表查询里少写限定名会报"列不唯一"，
 * 而单表查询里写了限定名也没错，所以默认给限定名更省心。
 */
const editorRef = ref<InstanceType<typeof SqlEditor> | null>(null)

const onInsertColumn = (p: { table: string; column: string; qualified: string }) => {
  editorRef.value?.insertAtCursor(p.column)
}

const onInsertQualified = (p: { table: string; column: string; qualified: string }) => {
  editorRef.value?.insertAtCursor(p.qualified)
}

/** 构造器「运行」：结果落在同一个 run 上，于是图表/明细/导出/历史全部复用。 */
const onBuilderRun = async (payload: BuilderRunRequest) => {
  running.value = true
  err.value = ''
  run.value = null
  chartOverride.value = null
  try {
    run.value = await builderRun(payload)
    mode.value = chartOption.value ? 'chart' : 'table'
    await loadHistory()
  } catch (e: unknown) {
    err.value = (e as { response?: { data?: { message?: string } }; message?: string })?.response?.data?.message
      ?? (e as Error).message ?? '执行失败'
  } finally {
    running.value = false
  }
}

const onRunSelected = async () => {
  const ta = document.querySelector('.se-ta') as HTMLTextAreaElement | null
  const sel = ta?.value.slice(ta.selectionStart, ta.selectionEnd)?.trim()
  const target = sel || sql.value
  if (target !== sql.value) {
    const backup = sql.value
    sql.value = target
    try {
      await onRun()
    } finally {
      sql.value = backup
    }
    return
  }
  await onRun()
}

/**
 * SQL 美化：主子句换行 + 括号深度缩进 + SELECT 列表逗号断行，关键字统一大写。
 * 字面量/注释/反引号标识符占位保护后原样还原，不改语义；无变化时不弹提示。
 */
const onFormat = () => {
  if (!sql.value.trim()) {
    ElMessage.warning('SQL 为空，无需格式化')
    return
  }
  const formatted = formatSql(sql.value)
  if (formatted === sql.value) return
  sql.value = formatted
  ElMessage.success('已格式化')
}

/** 当前正在编辑的模板（用于更新时预填名称/说明）；他人共享模板不进编辑态，保存自动回落「另存新模板」。 */
const editingTpl = computed(() =>
  editingId.value == null
    ? null
    : templates.value.find((t) => t.id === editingId.value && isOwnedTpl(t)) ?? null
)

const onSaveTemplate = async () => {
  if (!dsId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  const name = newName.value.trim() || (editingTpl.value?.name ?? '')
  if (!name) {
    ElMessage.warning('请填写模板名称')
    return
  }
  // 构造器里"当前 SQL"就是它预览出来的那条（没预览过则拒绝保存，别存个空串当标准答案）
  if (tab.value === 'builder' && !builderSql.value.trim()) {
    ElMessage.warning('请先在构造器里点「预览 SQL」')
    return
  }
  const specJson =
    tab.value === 'builder' ? builderRef.value?.getSpec() ?? null : null
  const payload = {
    name,
    datasourceId: dsId.value,
    sqlTemplate: tab.value === 'builder' ? builderSql.value : sql.value,
    description: newDesc.value.trim() || editingTpl.value?.description || null,
    spec: specJson ? JSON.stringify(specJson) : undefined
  }
  try {
    // 编辑态单一判据=editingTpl（已剔除他人共享模板）；他人模板即便选了也走新建分支另存，避免必被后端拒的覆盖更新
    if (editingTpl.value != null) {
      await updateTemplate(editingId.value!, payload)
      ElMessage.success('模板已更新')
    } else {
      await saveTemplate(payload)
      ElMessage.success('已保存')
      newName.value = ''
      newDesc.value = ''
    }
    await loadTemplates()
  } catch (e: unknown) {
    ElMessage.error((e as Error).message ?? '保存失败')
  }
}

/** 另存为副本：保留更新态但清空 id，走新建分支。 */
const onSaveAsCopy = async () => {
  const src = editingTpl.value
  if (!src) return
  editingId.value = null
  newName.value = `${src.name.trim()} 副本`
  newDesc.value = src.description ?? ''
  await onSaveTemplate()
}

const onCancelEdit = () => {
  editingId.value = null
  newName.value = ''
  newDesc.value = ''
}

const onPickTemplate = async (id: number) => {
  const t = templates.value.find((x) => x.id === id)
  if (!t) return
  tplId.value = id
  if (dsId.value !== t.datasourceId) {
    dsId.value = t.datasourceId
  }
  params.value = Object.fromEntries((t.paramNames ?? []).map((p) => [p, '']))
  // 构造器规格优先还原回构造器，否则退回 SQL 编辑器
  if (t.spec) {
    try {
      const spec = JSON.parse(t.spec) as BuilderSpec
      if (spec && spec.mainTable) {
        tab.value = 'builder'
        await nextTick()
        builderRef.value?.loadSpec(spec)
        editingId.value = t.id
        newName.value = t.name
        newDesc.value = t.description ?? ''
        await loadTemplates()
        return
      }
    } catch {
      // 解析失败退回 SQL
    }
  }
  sql.value = t.sqlTemplate
  tab.value = 'sql'
  // 进入"更新"态：名称/说明预填，改完可直接覆盖
  editingId.value = t.id
  newName.value = t.name
  newDesc.value = t.description ?? ''
  await loadTemplates()
}

const onDeleteTemplate = async (row: SavedQueryVO) => {
  try {
    await deleteTemplate(row.id)
    if (tplId.value === row.id) tplId.value = null
    if (editingId.value === row.id) onCancelEdit()
    await loadTemplates()
    ElMessage.success('已删除')
  } catch (e: unknown) {
    ElMessage.error((e as Error).message ?? '删除失败')
  }
}

/** 模板行「定时」入口（T12-1）：打开弹层并预选该模板；创建成功不需要刷新本页，任务在后台生效。 */
const onScheduleTemplate = (t: SavedQueryVO) => {
  scheduleTplId.value = t.id
  scheduleVisible.value = true
}

/** 共享/收回切换（T12-6）：仅入口面按钮，后端双重鉴权（管理员+门控）兜底，失败由拦截器提示。 */
const onToggleShare = async (t: SavedQueryVO) => {
  const next = t.visibility === 'SHARED' ? 'PRIVATE' : 'SHARED'
  try {
    await setTemplateVisibility(t.id, next)
    await loadTemplates()
    ElMessage.success(next === 'SHARED' ? '已共享给组织成员' : '已收回为私有')
  } catch (e: unknown) {
    ElMessage.error((e as Error).message ?? '切换失败')
  }
}

const onRunTemplate = async () => {
  if (tplId.value == null) {
    ElMessage.info('未选择查询模板，将运行编辑器中的 SQL')
    await onRun()
    return
  }
  running.value = true
  err.value = ''
  run.value = null
  chartOverride.value = null
  try {
    run.value = await runTemplate(tplId.value, params.value)
    mode.value = chartOption.value ? 'chart' : 'table'
    await loadHistory()
  } catch (e: unknown) {
    err.value = (e as { response?: { data?: { message?: string } }; message?: string })?.response?.data?.message
      ?? (e as Error).message ?? '执行失败'
  } finally {
    running.value = false
  }
}

const onReuse = async (h: QueryRecordVO) => {
  if (h.datasourceId && h.datasourceId !== dsId.value) dsId.value = h.datasourceId
  showHistory.value = false
  if (h.spec) {
    try {
      const spec = JSON.parse(h.spec) as BuilderSpec
      if (spec && spec.mainTable) {
        tab.value = 'builder'
        await nextTick()
        builderRef.value?.loadSpec(spec)
        ElMessage.info('已还原到构造器，可直接运行')
        return
      }
    } catch {
      // 解析失败退回 SQL
    }
  }
  if (h.sqlText) sql.value = h.sqlText
  tab.value = 'sql'
  ElMessage.info('已回填编辑器，可直接修改后运行')
}

/**
 * 导出数据（Excel）：枚举字典只在显示层生效，导出的仍是原始码值，保证与库里的数据可比对、可追溯。
 * 与问数结果面板「导出数据」完全一致（屏幕给人看，文件给程序比）。
 */
const onExport = onExportData
</script>

<template>
  <div class="sqe">
    <el-card shadow="never" class="sqe-card">
      <template #header>
        <div class="sqe-head">
          <span class="sqe-title">高级查询 / SQL 编辑器</span>
          <el-tag size="small" type="info">只读 SQL</el-tag>
          <el-tag v-if="run?.masked" size="small" type="warning">已脱敏</el-tag>
          <el-tag v-if="run" size="small" type="info">{{ run.skillCode }}</el-tag>
        </div>
      </template>

      <div class="sqe-bar">
        <div class="sqe-bar-left">
          <el-select v-model="dsId" placeholder="数据源" class="sqe-ds" @change="trackParams">
            <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-input-number v-model="maxRows" :min="1" :max="100000" :step="1000" size="small" class="sqe-rows" />
          <el-select v-model="chartType" clearable placeholder="图型（自动）" size="small" class="sqe-chart">
            <el-option v-for="t in chartTypeOptions" :key="t.code" :label="t.displayName" :value="t.code" />
          </el-select>
          <el-radio-group v-model="tab" size="small" class="sqe-tab">
            <el-radio-button value="sql">SQL 编辑器</el-radio-button>
            <el-radio-button value="builder">可视化构造器</el-radio-button>
          </el-radio-group>
        </div>
        <div class="sqe-bar-right">
          <el-button v-if="tab === 'sql'" type="primary" size="small" :loading="running" @click="onRun">
            运行 (Ctrl+Enter)
          </el-button>
          <el-button v-if="tab === 'sql'" size="small" :disabled="running" @click="onRunSelected">
            运行选中片段
          </el-button>
          <el-button v-if="tab === 'sql'" size="small" :disabled="running" @click="onFormat">
            格式化
          </el-button>
          <el-button size="small" :disabled="running" @click="onRunTemplate">
            运行模板
          </el-button>
          <el-button size="small" text @click="showHistory = !showHistory">历史</el-button>
        </div>
      </div>

      <el-divider class="sqe-div" />

      <p class="sqe-tip">
        {{
          tab === 'sql'
            ? '单条只读 SELECT：写操作被后端拦截，结果经字段脱敏；补全会按当前数据源给出表名 / 列名 / 语义别名。'
            : '构造器生成的 SQL 与手写 SQL 走同一条安全内核；标识符全部从服务端清单里挑。'
        }}
      </p>

      <div class="sqe-cols">
        <!-- 手写 SQL 才显示这块：构造器内置同一个组件，两边样式天然一致。
             两个 tab 都用 v-show 常驻：切走不清状态，切回来数据还在（v-if 会销毁重建、
             每次切换都重新拉 schema，表现为"切个 tab 表结构就空一下"）。 -->
        <div v-show="tab === 'sql'" class="sqe-schema">
          <SchemaExplorer
            :datasource-id="dsId"
            height="300px"
            @pick="onInsertColumn"
            @pick-qualified="onInsertQualified"
          />
        </div>

        <div class="sqe-main">
          <SqlEditor ref="editorRef" v-show="tab === 'sql'" v-model="sql" :datasource-id="dsId" height="300px" />
          <QueryBuilder
            ref="builderRef"
            v-show="tab === 'builder'"
            :datasource-id="dsId"
            :max-rows="maxRows"
            :chart-type="chartType"
            @run="onBuilderRun"
            @preview="(s: string) => (builderSql = s)"
          />

          <div v-if="tplId != null" class="sqe-params">
            <span class="sqe-lb">模板参数</span>
            <el-input
              v-for="p in paramsOf"
              :key="p"
              v-model="params[p]"
              size="small"
              class="sqe-param"
              :placeholder="`:${p}`"
            />
            <el-button v-if="!paramsOf.length" size="small" text disabled>该模板无参数</el-button>
          </div>

        </div>

        <aside class="sqe-side">
          <div class="sqe-side-block">
            <span class="sqe-lb">查询模板</span>
            <el-select
              :model-value="tplId"
              placeholder="选择已保存的模板"
              size="small"
              clearable
              class="sqe-full"
              @update:model-value="(v: number | null) => v != null && onPickTemplate(v)"
            >
              <el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <div class="sqe-save">
              <el-input v-model="newName" size="small" placeholder="模板名称" class="sqe-full" />
              <el-input v-model="newDesc" size="small" placeholder="说明（可选）" class="sqe-full" />
              <el-button size="small" type="primary" plain class="sqe-full" @click="onSaveTemplate">
                {{ editingTpl ? '覆盖更新模板' : '保存当前 SQL' }}
              </el-button>
              <div v-if="editingTpl" class="sqe-save-row">
                <el-button size="small" text class="sqe-full" @click="onSaveAsCopy">另存为副本</el-button>
                <el-button size="small" text class="sqe-full" @click="onCancelEdit">取消编辑</el-button>
              </div>
            </div>
            <ul class="sqe-tpl-list">
              <li v-for="t in templates" :key="t.id">
                <span class="sqe-tpl-name" :title="t.sqlTemplate" @click="onPickTemplate(t.id)">{{ t.name }}</span>
                <el-tag v-if="(t.paramNames ?? []).length" size="small">{{ t.paramNames.length }} 参数</el-tag>
                <el-tag v-if="t.visibility === 'SHARED'" size="small" type="success" effect="plain">共享</el-tag>
                <el-button v-if="config.scheduleEnabled" size="small" text @click="onScheduleTemplate(t)">定时</el-button>
                <el-button
                  v-if="config.sharedTemplatesEnabled && (canManageShare || isOwnedTpl(t))"
                  size="small"
                  text
                  @click="onToggleShare(t)"
                >{{ t.visibility === 'SHARED' ? '收回' : '共享' }}</el-button>
                <el-button v-if="isOwnedTpl(t)" size="small" text type="danger" @click="onDeleteTemplate(t)">删除</el-button>
              </li>
              <li v-if="!templates.length" class="sqe-tpl-empty">还没有保存的模板</li>
            </ul>
          </div>

          <div v-if="showHistory" class="sqe-side-block">
            <span class="sqe-lb">执行历史</span>
            <ul class="sqe-hist">
              <li v-for="h in history" :key="h.id">
                <el-button size="small" text class="sqe-hist-btn" @click="onReuse(h)">
                  <span class="sqe-hist-sql">{{ h.sqlText || '(空)' }}</span>
                  <em>{{ h.rowCount ?? 0 }} 行 · {{ (h.elapsedMs ?? 0) }}ms · {{ h.status }}</em>
                </el-button>
              </li>
              <li v-if="!history.length" class="sqe-tpl-empty">暂无执行记录</li>
            </ul>
          </div>
        </aside>
      </div>

      <el-divider class="sqe-div" />

      <!-- 结果区独占整卡宽度（在三栏布局之外）：图表/表格不再被左右栏夹窄，
           元信息与工具条也有充足空间不再换行挤压。SQL tab 与构造器 tab 共享本区。 -->
      <div v-if="err" class="sqe-err">
        <el-alert :title="err" type="error" show-icon :closable="false" />
      </div>

      <div v-else-if="runResult" class="sqe-out">
        <div class="sqe-out-bar">
          <el-radio-group v-model="mode" size="small">
            <el-radio-button value="chart" :disabled="!chartOption">
              <el-icon><TrendCharts /></el-icon>
            </el-radio-button>
            <el-radio-button value="table">
              <el-icon><GridIcon /></el-icon>
            </el-radio-button>
          </el-radio-group>
          <span class="sqe-meta">
            {{ runResult.rowCount }} 行 · {{ elapsed }} ms · {{ runResult.columns.length }} 列
          </span>

          <!-- 图表标注设置：轴名/单位/数值格式/图例，与问数结果面板同一套会话级机制 -->
          <el-popover v-if="chartOption" placement="bottom-end" :width="304" trigger="click">
            <template #reference>
              <el-button size="small" text :type="hasStyleOverride ? 'primary' : 'default'">
                <el-icon><Setting /></el-icon> 图表设置
              </el-button>
            </template>
            <ChartStyleForm
              :model-value="styleOverride"
              :chart-type="chartSpec?.type ?? null"
              :spec-style="chartSpec?.style ?? null"
              @update:model-value="onStyleChange"
            />
          </el-popover>
          <!-- 图型与字段绑定：清单来自后端图型注册表，改完立即重建 option -->
          <el-popover v-if="chartOption" placement="bottom-end" :width="360" trigger="click">
            <template #reference>
              <el-button size="small" text :type="chartOverride ? 'primary' : 'default'">
                <el-icon><MagicStick /></el-icon> 选择图表
              </el-button>
            </template>
            <ChartPicker
              v-model="chartOverride"
              :result="runResult"
              :recommended="chartSpec"
              :editable="mode === 'chart'"
            />
          </el-popover>
          <el-button v-if="chartOption && mode === 'chart'" size="small" text @click="onDownloadChart">
            <el-icon><Download /></el-icon> 下载图表
          </el-button>
          <el-button v-if="runResult.rows?.length" size="small" text @click="onExport">
            <el-icon><Download /></el-icon> 导出数据
          </el-button>
          <!-- 存为指标：复用问数同款对话框（metricApi.create 存命名化 SQL）。question 留空，
               columnLabels 不传（对话框内部 displayColumns(result) 已读语义层别名兜底）。 -->
          <el-button
            v-if="can('metric:item:write') && currentSql"
            size="small"
            text
            @click="showSaveMetric = true"
          >
            <el-icon><TrendCharts /></el-icon> 存为指标
          </el-button>
        </div>

        <div v-if="mode === 'chart' && chartOption" class="sqe-chartbox">
          <BaseChart ref="chartRef" :option="chartOption" height="320px" />
        </div>
        <QueryResultGrid v-else :result="runResult" height="320px" />

        <!-- 存为指标：把当前结果对应的 SQL 命名化存入指标库，可复跑、可进驾驶舱。
             与问数结果面板共用 MetricSaveDialog / metricApi.create；构造器 tab 因共享本结果区同样生效。 -->
        <MetricSaveDialog
          v-model="showSaveMetric"
          :datasource-id="dsId"
          :question="''"
          :sql="currentSql"
          :chart="chartSpec"
          :result="runResult"
        />
      </div>
      <div v-else class="sqe-empty">尚未执行查询</div>
    </el-card>

    <!-- 设为定时报表（T12-1）：模板行「定时」打开，预选来源模板 -->
    <ReportFormDialog v-model="scheduleVisible" :preset-template-id="scheduleTplId" />
  </div>
</template>

<style scoped>
/* AppLayout 的 .content 是 overflow:hidden（大屏页用），常规页面必须自带滚动，
   否则编辑器 300px + 结果区 320px 超出视口的部分（图表等）被直接裁掉。 */
.sqe {
  height: 100%;
  overflow-y: auto;
}
.sqe-card {
  margin: 12px;
}
.sqe-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.sqe-title {
  font-weight: 600;
}
.sqe-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 0;
}
.sqe-bar-left,
.sqe-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.sqe-div {
  margin: 12px 0;
}
.sqe-ds {
  width: 220px;
}
.sqe-tab {
  margin-right: 0;
}
.sqe-rows {
  width: 130px;
}
.sqe-chart {
  width: 150px;
}
.sqe-tip {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  line-height: 1.7;
}
.sqe-cols {
  display: flex;
  gap: 0;
  align-items: flex-start;
}
.sqe-schema {
  width: 250px;
  flex-shrink: 0;
  padding-right: 12px;
  border-right: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.sqe-main {
  flex: 1;
  min-width: 0;
  padding: 0 12px;
}
.sqe-side {
  width: 300px;
  flex-shrink: 0;
  padding-left: 12px;
  border-left: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.sqe-side-block {
  margin-bottom: 12px;
}
.sqe-lb {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  margin-bottom: 6px;
}
.sqe-full {
  width: 100%;
  margin-bottom: 6px;
}
.sqe-params {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding: 8px;
  background: #fafcff;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
}
.sqe-param {
  width: 160px;
}
.sqe-err {
  margin-top: 10px;
}
.sqe-out {
  margin-top: 12px;
}
.sqe-out-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.sqe-meta {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
.sqe-chartbox {
  margin-top: 8px;
}
.sqe-empty {
  margin-top: 16px;
  text-align: center;
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}
.sqe-save {
  margin-top: 8px;
}
.sqe-save-row {
  display: flex;
  gap: 4px;
}
.sqe-tpl-list,
.sqe-hist {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  max-height: 260px;
  overflow: auto;
}
.sqe-tpl-list li,
.sqe-hist li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  font-size: 12px;
}
.sqe-tpl-name {
  flex: 1;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sqe-tpl-empty {
  color: var(--el-text-color-placeholder, #c0c4cc);
  justify-content: center;
}
.sqe-hist-btn {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  padding: 2px;
}
.sqe-hist-sql {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: Consolas, monospace;
}
.sqe-hist em {
  font-style: normal;
  color: var(--el-text-color-placeholder, #c0c4cc);
  font-size: 11px;
}
</style>
