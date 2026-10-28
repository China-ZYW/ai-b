<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import BaseChart from '@/components/chart/BaseChart.vue'
import ChartStyleForm from '@/components/chart/ChartStyleForm.vue'
import ChartPicker from '@/components/query/ChartPicker.vue'
import AttributionPanel from '@/components/chat/AttributionPanel.vue'
import { buildChartOption } from '@/composables/useChartOption'
import { useConfigStore } from '@/stores/config'
import type {
  AnomalyMark,
  AttributionVO,
  ChartSpec,
  ChartStyle,
  Insight,
  QueryResult,
  ResultMeta,
  SkillInfo,
  Verify
} from '@/types'
import { formatElapsed, formatNumber, displayColumns, applyEnumDicts } from '@/utils/format'
import { pruneChartStyle } from '@/utils/chartStyle'
import { copyText } from '@/utils/clipboard'
import { exportChartPng, exportXlsx, fileStamp, gridToExport } from '@/utils/exportFile'
import { usePerm } from '@/composables/usePerm'

/**
 * 问数结果面板：图表 / 明细表切换 + 可折叠 SQL + 图表推荐理由。
 * 问数页与公开分享页共用；readonly 时隐藏收藏/分享等操作与表头重命名、图表设置入口。
 *
 * <p>V3 完整度：展示后端回填的 {@code meta}（真实截断/总行数/修复/置信度）、{@code insight}
 * （AI 解读）与 {@code verify}（质量校验）；明细表改为分页，可翻阅全量行。
 *
 * <p>列展示名：后端已按「语义层别名 &gt; 列名」下发 {@code columnLabels}，本组件再叠一层
 * {@code labelOverrides}（用户在表头重命名的会话级自定义，主要给汇总/派生列取业务叫法），
 * 向上 emit 后由问数页在“存为指标”时随指标配置持久化。
 *
 * <p>图表标注（轴名/单位/数值格式/图例）走同一套会话级覆盖机制：{@code styleOverride} 入、
 * {@code updateChartStyle} 出，由问数页持有并在存指标/分享时写进 {@code chartSpec.style}。
 */
const props = withDefaults(
  defineProps<{
    result: QueryResult
    chart?: ChartSpec | null
    meta?: ResultMeta | null
    insight?: Insight | null
    verify?: Verify | null
    /** 本次答问所用技能（V4）：标注"由谁回答、是否走了大模型"，使结果不再是黑盒。 */
    skill?: SkillInfo | null
    watermark?: boolean
    readonly?: boolean
    /** 该结果对应问题是否已收藏（问题1：收藏态展示 + 去重提示）。 */
    favorited?: boolean
    /** 会话级列展示名自定义（物理列名 → 自定义名，空值表示恢复默认）。 */
    labelOverrides?: Record<string, string> | null
    /** 会话级图表标注覆盖（轴名/单位/数值格式/图例），空值表示跟随 chart_spec 与租户默认。 */
    styleOverride?: ChartStyle | null
    /** 当前数据源 id：归因面板据此过滤"已沉淀口径"的可归因指标。 */
    datasourceId?: number | null
    /** 本次问数的问题文本：命中波动类语义时自动展开归因模块（需求 §3.1 触发条件）。 */
    question?: string | null
    /**
     * 本次问数对应的历史 id（对话场景由消息里的 response.historyId 传入）。
     * 归因跑完时后端据此把结果回写到该条 query_history，历史回放才能还原归因面板；
     * 公开分享页等无历史上下文的场景留空。
     */
    historyId?: number | null
    /**
     * 历史回放带回的归因结果（问数当时产物，后端只存不重算）。
     * 有值时归因面板直接展示它并展开；用户仍可改参数重跑（重跑会覆盖库里的旧结论）。
     */
    attribution?: AttributionVO | null
  }>(),
  {
    chart: null,
    meta: null,
    insight: null,
    verify: null,
    skill: null,
    watermark: false,
    readonly: false,
    favorited: false,
    labelOverrides: null,
    styleOverride: null
  }
)

const emit = defineEmits<{
  (e: 'favorite'): void
  (e: 'share'): void
  (e: 'saveMetric'): void
  /** 重命名结果列的展示名（label 为空串表示清除自定义、回落语义层别名/列名）。 */
  (e: 'renameColumn', column: string, label: string): void
  /** 上抛图表标注覆盖（null 表示清除、回落 chart_spec 与租户默认）。 */
  (e: 'updateChartStyle', style: ChartStyle | null): void
}>()

type ViewMode = 'chart' | 'table'
const config = useConfigStore()
const { can } = usePerm()

const rows = computed(() => props.result.rows ?? [])
const columns = computed(() => props.result.columns ?? [])
/**
 * 展示行：把语义列「枚举字典」配好的码值换成中文（status=1 → 已支付）。
 *
 * 只作用于明细表与图表；导出（{@link onExportData}）与字段身份一律用原始 {@code rows}，
 * 免得导出的 Excel 里再也找不到原始码值、也没法跟库里对账。
 */
const displayRows = computed(() => applyEnumDicts(props.result))
const dictReplaced = computed(() => displayRows.value !== rows.value)
/** 列展示名（会话内重命名 > 语义层别名 > 列名）：仅作表头文案，prop/key 仍用列名保证取值正确。 */
const columnLabels = computed(() => displayColumns(props.result, props.labelOverrides))
/** 图表消费的结果：重命名同步进 columnLabels，使图例/系列名/轴名与明细表头一致；码值一并转中文。 */
const chartResult = computed<QueryResult>(() => {
  const base = dictReplaced.value ? { ...props.result, rows: displayRows.value } : props.result
  return props.labelOverrides && Object.keys(props.labelOverrides).length > 0
    ? { ...base, columnLabels: columnLabels.value }
    : base
})
/**
 * 图表规格本地覆盖（P2 图表选择器）：{@code null} 表示跟随后端推荐，
 * 用户一旦改图型/字段即落到这里。用本地态而非新增 props/emit，是为了不污染
 * 调用方（指标保存、分享快照、驾驶舱）的接口面。
 */
const chartOverride = ref<ChartSpec | null>(null)
// props.chart 换了（新一轮结果 / 历史回放）就丢弃上一次的人工干预，避免张冠李戴
watch(() => props.chart, () => { chartOverride.value = null })

const specForChart = computed<ChartSpec | null>(() => chartOverride.value ?? props.chart ?? null)
const chartOption = computed(() =>
  buildChartOption(specForChart.value, chartResult.value, undefined, props.styleOverride, config.chartDefaults)
)
const canChart = computed(() => chartOption.value !== null)

/** 初始视图：图表不可用时强制表格；否则遵循配置的 default-view。 */
function initialMode(): ViewMode {
  if (!canChart.value) return 'table'
  return config.defaultView === 'table' ? 'table' : 'chart'
}
const mode = ref<ViewMode>(initialMode())
/** 用户是否已手动切换视图（切换后配置默认值不再覆盖）。 */
const modeManual = ref(false)

/** 图表实例引用（下载 PNG 用）。 */
const chartRef = ref<{ getDataURL?: (t?: 'png' | 'jpeg', bg?: string) => string | null } | null>(null)

/** 下载图表为 PNG。 */
function onDownloadChart(): void {
  if (!exportChartPng(() => chartRef.value?.getDataURL?.() ?? null, `问数图表-${fileStamp()}`)) {
    ElMessage.warning('图表尚未渲染，无法下载')
  }
}

/** 导出明细数据（Excel；导出全部行，非当前分页）。 */
async function onExportData(): Promise<void> {
  const g = gridToExport(columns.value, rows.value, columnLabels.value)
  await exportXlsx(`问数结果-${fileStamp()}`, g.columns, g.rows)
}
const showSql = ref(false)

/** 溯源（T10-3）：后端未下发 lineage（开关关/无表/装配失败）或表清单为空时，整个「数据来源」块隐藏。 */
const showLineage = ref(false)
const lineage = computed(() => props.meta?.lineage ?? null)
const lineageTables = computed(() => lineage.value?.tables ?? [])

/**
 * 环比波动标记（T10-4b）：后端未下发 marks（开关关/非时间序列/无命中/装配失败）时，
 * 表格格与从前逐字节一致；命中时格高亮（升红降绿）+ 幅度徽标 + 表底一句事实结论。
 */
const marks = computed(() => props.meta?.marks ?? null)
const timeColIdx = computed(() => (marks.value ? columns.value.indexOf(marks.value.timeCol) : -1))
/** “时间点|列名”→标记：按时间值+度量物理名定位格，不依赖行序（检测按时间序排序，分页后仍正确）。 */
const markIndex = computed(() => {
  const idx = new Map<string, AnomalyMark>()
  for (const m of marks.value?.items ?? []) idx.set(`${m.timePoint}|${m.colName}`, m)
  return idx
})
function markOf(row: Record<string, unknown>, col: string): AnomalyMark | null {
  const m = marks.value
  if (!m || timeColIdx.value < 0) return null
  const raw = displayRows.value[Number(row.__key)]?.[timeColIdx.value]
  if (raw === null || raw === undefined) return null
  return markIndex.value.get(`${raw}|${col}`) ?? null
}
/** 单元格 class：升红降绿（中文财报配色），无标记返回空串保持原样。 */
function cellClass(args: { row: Record<string, unknown>; column: { property?: string } }): string {
  const mk = markOf(args.row, args.column?.property ?? '')
  return mk ? (mk.increase ? 'cell-anom-up' : 'cell-anom-down') : ''
}
function anomTitle(mk: AnomalyMark): string {
  return `环比：${mk.prevValue} → ${mk.value}，${mk.pctChange >= 0 ? '+' : ''}${mk.pctChange}%（阈值 ${marks.value?.thresholdPct ?? ''}%，仅报事实不归因）`
}

onMounted(() => {
  void config.load()
})

// 配置异步加载完成后，若用户尚未手动切换，则应用配置的默认视图
watch(
  () => config.defaultView,
  () => {
    if (!modeManual.value) mode.value = initialMode()
  }
)

/** 明细分页（V3）：翻阅全量行，页容量可选，不再依赖前端 maxDisplayRows 硬截断。 */
const currentPage = ref(1)
const pageSize = ref(20)
const totalRows = computed(() => rows.value.length)
watch(pageSize, () => {
  currentPage.value = 1
})
watch(totalRows, () => {
  currentPage.value = 1
})

/* ---------------- 表头重命名（会话级；存为指标时随指标配置持久化） ---------------- */
const editingCol = ref<string | null>(null)
const editingLabel = ref('')
/** 当前编辑框实例（函数 ref 绑定；同一时刻只有一个表头处于编辑态）。 */
let renameInputEl: { focus?: () => void; select?: () => void } | null = null

function bindRenameInput(el: Element | ComponentPublicInstance | null): void {
  renameInputEl = el as unknown as { focus?: () => void; select?: () => void } | null
}

async function startRename(col: string, i: number): Promise<void> {
  editingCol.value = col
  editingLabel.value = columnLabels.value[i] || col
  await nextTick()
  renameInputEl?.focus?.()
  renameInputEl?.select?.()
}

/** 确认重命名；置空等同放弃修改（保持原展示名）。 */
function commitRename(col: string): void {
  const v = editingLabel.value.trim()
  editingCol.value = null
  if (v) {
    emit('renameColumn', col, v)
  }
}

/** 取消编辑：清空草稿后，随之而来的 blur 提交因空值自然作废。 */
function cancelRename(): void {
  editingLabel.value = ''
  editingCol.value = null
}

/** 清除该列自定义，回落语义层别名（无别名则列名）。 */
function resetRename(col: string): void {
  editingCol.value = null
  emit('renameColumn', col, '')
}

/** 该列是否已被会话内重命名（决定“重置”图标是否展示）。 */
function isRenamed(col: string): boolean {
  return !!props.labelOverrides?.[col]?.trim()
}

/* ---------------- 图表标注设置（会话级；存指标/分享时写进 chartSpec.style） ---------------- */

/**
 * 表单本体见 {@link ChartStyleForm}（与驾驶舱编辑器的 widget 级覆盖共用，草稿/去抖/剪枝均在其中）；
 * 这里只负责把覆盖值在“组件 props ↔ 问数页会话状态”之间转手。
 */
const hasStyleOverride = computed(() => !!pruneChartStyle(props.styleOverride))

function onStyleChange(style: ChartStyle | null): void {
  emit('updateChartStyle', style)
}

/** 轴名占位提示用列展示名（语义别名 &gt; 列名），用户一眼知道默认会显示什么。 */
function labelOfField(field?: string | null): string {
  if (!field) return ''
  const i = columns.value.indexOf(field)
  return i >= 0 ? columnLabels.value[i] || field : ''
}
const dimName = computed(() => labelOfField(props.chart?.xField))
const measureName = computed(() => labelOfField(props.chart?.yFields?.[0]))
/** 条形图两轴互换：占位提示要跟着换，否则用户会填错轴。 */
const isHBar = computed(() => props.chart?.type === 'HBAR')
const xPlaceholder = computed(() => (isHBar.value ? measureName.value : dimName.value) || '留空不显示轴名')
const yPlaceholder = computed(() => (isHBar.value ? dimName.value : measureName.value) || '留空不显示轴名')

/** 后端截断元信息（V3）：SQL 命中行数达上限时为 true，totalRows 不可精确知。 */
const backendTruncated = computed(() => props.meta?.truncated === true)
/** 置信度百分比（来自 AI 质量校验，未启用时不展示）。 */
const confidencePct = computed(() =>
  props.meta?.confidence != null ? Math.round(props.meta.confidence * 100) : null
)

/** 数值单元格按配置格式化：thousand=千分位 / raw=原样。 */
function fmtCell(v: unknown): unknown {
  if (config.numberFormat === 'thousand' && typeof v === 'number' && Number.isFinite(v)) {
    return v.toLocaleString('zh-CN')
  }
  return v
}

/** el-table 需要对象数组：把二维行数组按列名映射为记录（当前页切片 + 数值格式化）。 */
const tableData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return displayRows.value.slice(start, start + pageSize.value).map((r, ri) => {
    const obj: Record<string, unknown> = { __key: start + ri }
    columns.value.forEach((c, ci) => {
      obj[c] = fmtCell(r[ci])
    })
    return obj
  })
})

/** AI 解读要点/异常（过滤空数组）。 */
const keyPoints = computed(() => props.insight?.keyPoints?.filter((s) => s && s.trim()) ?? [])
const anomalies = computed(() => props.insight?.anomalies?.filter((s) => s && s.trim()) ?? [])
const verifyIssues = computed(() => props.verify?.issues?.filter((s) => s && s.trim()) ?? [])
const hasInsight = computed(() => !!props.insight && (!!props.insight.summary || keyPoints.value.length > 0 || anomalies.value.length > 0))
const hasVerify = computed(
  () => !!props.verify && (props.verify.answered != null || props.verify.score != null || verifyIssues.value.length > 0 || !!props.verify.suggestion)
)
/** 校验结果色调：answered=false 或 score<40 视为告警。 */
const verifyTone = computed(() => {
  const v = props.verify
  if (!v) return 'info'
  if (v.answered === false || (v.score != null && v.score < 40)) return 'danger'
  if (v.score != null && v.score < 70) return 'warning'
  return 'success'
})

/** 地图加载失败（CDN 不可达）：回落明细表。 */
function onChartFallback(reason: string): void {
  modeManual.value = true
  mode.value = 'table'
  ElMessage.warning(reason)
}

/** 图型中文展示名。 */
function chartTypeLabel(t?: string | null): string {
  switch (t) {
    case 'LINE':
      return '折线图'
    case 'BAR':
      return '柱状图'
    case 'PIE':
      return '饼图'
    case 'AREA':
      return '面积图'
    case 'HBAR':
      return '条形图'
    case 'HEATMAP':
      return '热力图'
    case 'MAP':
      return '地图'
    case 'RADAR':
      return '雷达图'
    case 'FUNNEL':
      return '漏斗图'
    case 'GAUGE':
      return '仪表盘'
    case 'SCATTER':
      return '散点图'
    default:
      return '明细表'
  }
}

async function copySql(): Promise<void> {
  const ok = await copyText(props.result.sql || '')
  if (ok) ElMessage.success('SQL 已复制')
  else ElMessage.warning('复制失败，请手动选择')
}

function switchMode(m: ViewMode): void {
  if (m === 'chart' && !canChart.value) {
    ElMessage.info('该结果不适合图表展示')
    return
  }
  modeManual.value = true
  mode.value = m
}
</script>

<template>
  <div class="result-panel card">
    <!-- 工具条 -->
    <div class="rp-toolbar">
      <div class="rp-metrics">
        <span class="metric"><el-icon><Files /></el-icon>{{ formatNumber(result.rowCount) }} 行</span>
        <span class="metric"><el-icon><Timer /></el-icon>{{ formatElapsed(result.executionTimeMs) }}</span>
        <el-tag v-if="chart" size="small" effect="light" type="primary" round>
          推荐：{{ chartTypeLabel(chart.type) }}
        </el-tag>
        <!-- 技能标注（V4）：tooltip 给出命中理由，如"命中指标「月度GMV」" -->
        <el-tooltip v-if="skill" :content="skill.reason || skill.name" placement="top">
          <el-tag size="small" effect="light" :type="skill.llmFree ? 'success' : 'primary'" round>
            {{ skill.name }}{{ skill.llmFree ? ' · 免大模型' : '' }}
          </el-tag>
        </el-tooltip>
        <el-tag v-if="confidencePct != null" size="small" effect="plain" :type="confidencePct >= 70 ? 'success' : confidencePct >= 40 ? 'warning' : 'danger'" round>
          <el-icon><Odometer /></el-icon> 置信度 {{ confidencePct }}%
        </el-tag>
        <el-tag v-if="meta?.repaired" size="small" effect="plain" type="warning" round>
          <el-icon><MagicStick /></el-icon> 已自动修正
        </el-tag>
        <el-tag v-if="meta?.emptyRetried" size="small" effect="plain" type="info" round>
          空结果重试
        </el-tag>
        <el-tag v-if="meta?.crossChecked" size="small" effect="plain" type="success" round>
          <el-icon><Connection /></el-icon> 辅助模型已交叉校验
        </el-tag>
        <el-tag v-if="backendTruncated" size="small" effect="plain" type="danger" round>
          结果已截断
        </el-tag>
      </div>
      <div class="rp-actions">
        <!-- 图表标注设置：轴名/单位/数值格式/图例，会话级生效，存指标与分享时一并带走 -->
        <el-popover v-if="canChart && !readonly" placement="bottom-end" :width="304" trigger="click">
          <template #reference>
            <el-button size="small" text :type="hasStyleOverride ? 'primary' : 'default'">
              <el-icon><Setting /></el-icon> 图表设置
            </el-button>
          </template>
          <ChartStyleForm
            :model-value="styleOverride"
            :chart-type="chart?.type ?? null"
            :spec-style="chart?.style ?? null"
            :x-placeholder="xPlaceholder"
            :y-placeholder="yPlaceholder"
            @update:model-value="onStyleChange"
          />
        </el-popover>
        <!-- 图型与字段绑定：清单来自后端图型注册表，改完立即重建 option -->
        <el-popover v-if="canChart && !readonly" placement="bottom-end" :width="360" trigger="click">
          <template #reference>
            <el-button size="small" text :type="chartOverride ? 'primary' : 'default'">
              <el-icon><MagicStick /></el-icon> 选择图表
            </el-button>
          </template>
          <ChartPicker
            v-model="chartOverride"
            :result="chartResult"
            :recommended="props.chart ?? null"
          />
        </el-popover>
        <el-radio-group :model-value="mode" size="small" @update:model-value="(v: string | number | boolean) => switchMode(v as ViewMode)">
          <el-radio-button value="chart" :disabled="!canChart">
            <el-icon><TrendCharts /></el-icon>
          </el-radio-button>
          <el-radio-button value="table">
            <el-icon><Grid /></el-icon>
          </el-radio-button>
        </el-radio-group>
        <!-- 下载图表（PNG）/ 导出数据（Excel）：只读分享页同样可用 -->
        <el-button v-if="canChart && mode === 'chart'" size="small" text @click="onDownloadChart">
          <el-icon><Download /></el-icon> 下载图表
        </el-button>
        <el-button v-if="rows.length" size="small" text @click="onExportData">
          <el-icon><Download /></el-icon> 导出数据
        </el-button>
        <template v-if="!readonly">
          <el-button v-if="can('favorite:item:write')" size="small" text :class="{ 'is-favorited': favorited }" @click="emit('favorite')">
            <el-icon><StarFilled v-if="favorited" /><Star v-else /></el-icon>
            {{ favorited ? '已收藏' : '收藏' }}
          </el-button>
          <el-button v-if="can('metric:item:write')" size="small" text @click="emit('saveMetric')">
            <el-icon><TrendCharts /></el-icon> 存为指标
          </el-button>
          <el-button v-if="can('share:link:write')" size="small" text @click="emit('share')">
            <el-icon><Share /></el-icon> 分享
          </el-button>
        </template>
      </div>
    </div>

    <!-- 图表 / 表格 -->
    <div class="rp-body">
      <div v-if="mode === 'chart' && canChart" class="rp-chart">
        <BaseChart ref="chartRef" :option="chartOption" height="360px" @fallback="onChartFallback" />
        <p v-if="chart?.reason" class="chart-reason">
          <el-icon><InfoFilled /></el-icon> {{ chart.reason }}
        </p>
      </div>
      <div v-else class="rp-table">
        <el-table :data="tableData" height="360" size="small" border stripe :cell-class-name="cellClass">
          <el-table-column
            v-for="(c, i) in columns"
            :key="`${c}-${i}`"
            :prop="c"
            :label="columnLabels[i] || c"
            :min-width="120"
            show-overflow-tooltip
          >
            <!-- 表头：展示名可就地重命名（会话级）；prop 仍用物理列名，行取值与图表字段匹配不受影响 -->
            <template #header>
              <div v-if="editingCol === c" class="th-edit" @click.stop>
                <el-input
                  :ref="bindRenameInput"
                  v-model="editingLabel"
                  size="small"
                  maxlength="64"
                  @keyup.enter="commitRename(c)"
                  @keyup.esc="cancelRename"
                  @blur="commitRename(c)"
                />
              </div>
              <div v-else class="th-box">
                <span class="th-text" :title="`列名：${c}`">{{ columnLabels[i] || c }}</span>
                <template v-if="!readonly">
                  <el-icon
                    v-if="isRenamed(c)"
                    class="th-act th-reset"
                    title="恢复默认名（语义层别名/列名）"
                    @click.stop="resetRename(c)"
                  >
                    <RefreshLeft />
                  </el-icon>
                  <el-icon class="th-act" title="重命名该列展示名" @click.stop="startRename(c, i)">
                    <EditPen />
                  </el-icon>
                </template>
              </div>
            </template>
            <!-- 单元格：原样展示值；命中环比标记时追加幅度徽标（格底色由 cell-class-name 高亮） -->
            <template #default="{ row }">
              <span>{{ row[c] }}</span>
              <em v-if="markOf(row as Record<string, unknown>, c)" class="anom-badge"
                  :class="markOf(row as Record<string, unknown>, c)!.increase ? 'anom-up' : 'anom-down'"
                  :title="anomTitle(markOf(row as Record<string, unknown>, c)!)">
                {{ markOf(row as Record<string, unknown>, c)!.increase ? '▲' : '▼' }}{{
                  Math.abs(markOf(row as Record<string, unknown>, c)!.pctChange).toFixed(1) }}%
              </em>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="无数据行" :image-size="80" />
          </template>
        </el-table>
        <!-- 环比波动事实结论（T10-4b）：只报数字与方向，不做业务归因；无 marks 整行不渲染 -->
        <p v-if="marks && marks.items.length" class="rp-anomaly-line">
          <el-icon><TrendCharts /></el-icon> {{ marks.summary }}
        </p>
        <div class="rp-table-foot">
          <p v-if="backendTruncated" class="rp-truncate">
            <el-icon><InfoFilled /></el-icon>
            命中行数达上限，仅返回前 {{ formatNumber(rows.length) }} 行（可在配置中调整上限）
          </p>
          <el-pagination
            v-if="totalRows > 0"
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            class="rp-pager"
            size="small"
            layout="total, sizes, prev, pager, next"
            :page-sizes="[20, 50, 100, 200]"
            :total="totalRows"
            background
          />
        </div>
      </div>
    </div>

    <!-- AI 结果解读 / 质量校验（V3；仅在有内容时展示） -->
    <div v-if="hasInsight || hasVerify" class="rp-ai">
      <div v-if="hasInsight" class="ai-card ai-insight">
        <div class="ai-head">
          <el-icon><Sunny /></el-icon><span>AI 数据解读</span>
        </div>
        <p v-if="insight?.summary" class="ai-summary">{{ insight.summary }}</p>
        <ul v-if="keyPoints.length" class="ai-list">
          <li v-for="(p, i) in keyPoints" :key="'kp' + i">{{ p }}</li>
        </ul>
        <div v-if="anomalies.length" class="ai-anomalies">
          <p v-for="(a, i) in anomalies" :key="'an' + i">
            <el-icon><WarningFilled /></el-icon>{{ a }}
          </p>
        </div>
      </div>
      <div v-if="hasVerify" class="ai-card ai-verify">
        <div class="ai-head">
          <el-icon><CircleCheck /></el-icon><span>质量校验</span>
          <el-tag size="small" effect="light" :type="verifyTone" round>
            {{ verify?.answered === false ? '未完全回答' : verify?.answered ? '已回答' : '待确认' }}
            <template v-if="verify?.score != null"> · {{ verify.score }}分</template>
          </el-tag>
        </div>
        <ul v-if="verifyIssues.length" class="ai-list">
          <li v-for="(s, i) in verifyIssues" :key="'vi' + i">{{ s }}</li>
        </ul>
        <p v-if="verify?.suggestion" class="ai-suggestion">
          <el-icon><InfoFilled /></el-icon>{{ verify.suggestion }}
        </p>
      </div>
    </div>

    <!--
      维度归因（需求 §3.1）：波动类问题自动展开，其余折叠为入口；公开分享页只读不展示。
      展示位置由配置中心控制（默认开）：ATTRIBUTION/result-panel 关闭后本面板整体不渲染
      （问数调优页的「维度归因」常驻 tab 仍可用）；ATTRIBUTION/enabled 关闭后归因模块整体停用，
      此处一并隐藏，避免留下点了必报错的死入口。
    -->
    <AttributionPanel
      v-if="!readonly && config.attributionEnabled && config.attributionResultPanel"
      :datasource-id="datasourceId"
      :question="question"
      :readonly="readonly"
      :history-id="historyId"
      :preset-result="attribution"
    />

    <!-- 数据来源溯源（T10-3）：来源表清单 + 口径出处，核对“数字从哪来”；后端未下发 lineage 时整块不渲染，面板与从前完全一致 -->
    <div v-if="lineage && lineageTables.length" class="rp-sql">
      <button class="sql-toggle" @click="showLineage = !showLineage">
        <el-icon><component :is="showLineage ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
        <span>数据来源</span>
        <span class="lineage-count">{{ lineageTables.length }} 张表</span>
      </button>
      <transition name="fade-slide">
        <div v-if="showLineage" class="lineage-block">
          <p v-if="skill" class="lineage-skill">
            口径出处：由【{{ skill.name }}{{ skill.llmFree ? ' · 免大模型' : '' }}】回答{{ skill.reason ? '：' + skill.reason : '' }}
          </p>
          <ul class="lineage-list">
            <li v-for="t in lineageTables" :key="t.table" class="lineage-item">
              <span class="lt-name">{{ t.aliasCn || t.table }}</span>
              <span v-if="t.aliasCn" class="lt-phys mono">{{ t.table }}</span>
              <div v-if="t.description" class="lt-desc">{{ t.description }}</div>
            </li>
          </ul>
        </div>
      </transition>
    </div>

    <!-- SQL 折叠 -->
    <div class="rp-sql">
      <button class="sql-toggle" @click="showSql = !showSql">
        <el-icon><component :is="showSql ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
        <span>查看生成的 SQL</span>
        <el-button v-if="showSql" size="small" text @click.stop="copySql">
          <el-icon><CopyDocument /></el-icon> 复制
        </el-button>
      </button>
      <transition name="fade-slide">
        <pre v-if="showSql" class="sql-block mono">{{ result.sql || '（本次未生成 SQL）' }}</pre>
      </transition>
    </div>

    <!-- 水印（免费版分享） -->
    <div v-if="watermark" class="watermark">AI 数据助理 · 分享快照</div>
  </div>
</template>

<style scoped>
.result-panel {
  position: relative;
  padding: 16px 18px;
  overflow: hidden;
}
.rp-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.rp-metrics {
  display: flex;
  align-items: center;
  gap: 14px;
}
.metric {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  color: var(--text-secondary);
  font-weight: 500;
}
.metric .el-icon {
  color: var(--ikb-light);
}
.rp-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.is-favorited {
  color: var(--el-color-warning);
}
.rp-body {
  border-radius: var(--radius-md);
}
.chart-reason {
  margin: 10px 0 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 5px;
}
.chart-reason .el-icon {
  color: var(--ikb-lighter);
}
.rp-truncate {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 5px;
}
.rp-table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.rp-table-foot .rp-truncate {
  margin: 0;
}
.rp-pager {
  margin-left: auto;
}
/* ---------- 环比波动标记（T10-4b） ---------- */
/* 命中格底色：升红降绿（中文财报配色），只用背景色不改字色权重，保持表格扫描性 */
:deep(.cell-anom-up) {
  background: var(--el-color-danger-light-8, #fde2e2) !important;
}
:deep(.cell-anom-down) {
  background: var(--el-color-success-light-8, #e1f3d8) !important;
}
.anom-badge {
  font-style: normal;
  font-size: 11px;
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 3px;
  white-space: nowrap;
}
.anom-up {
  color: var(--el-color-danger, #f56c6c);
  background: var(--el-color-danger-light-9, #fef0f0);
}
.anom-down {
  color: var(--el-color-success, #67c23a);
  background: var(--el-color-success-light-9, #f0f9eb);
}
.rp-anomaly-line {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--fill-lighter, #f5f7fa);
  border-radius: 4px;
  padding: 6px 8px;
  display: flex;
  align-items: baseline;
  gap: 5px;
}
.rp-anomaly-line .el-icon {
  color: var(--ikb-lighter, #909399);
}
/* ---------- 表头展示名就地重命名 ---------- */
.th-box {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.th-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.th-act {
  flex-shrink: 0;
  cursor: pointer;
  color: var(--ikb-lighter);
  opacity: 0;
  transition: opacity 0.15s;
}
.th-box:hover .th-act {
  opacity: 1;
}
.th-act:hover {
  color: var(--ikb);
}
/* 已重命名的列常驻提示，免得用户找不到恢复入口 */
.th-reset {
  opacity: 1;
  color: var(--el-color-warning);
}
.th-edit :deep(.el-input__wrapper) {
  padding: 0 6px;
}
.rp-ai {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}
.ai-card {
  border-radius: var(--radius-md);
  padding: 12px 14px;
  border: 1px solid var(--border);
}
.ai-insight {
  background: linear-gradient(135deg, rgba(0, 47, 167, 0.05), rgba(0, 47, 167, 0.01));
}
.ai-verify {
  background: var(--ikb-ghost);
}
.ai-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 700;
  color: var(--ikb);
}
.ai-head .el-tag {
  margin-left: auto;
}
.ai-summary {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-primary);
}
.ai-list {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-secondary);
}
.ai-anomalies {
  margin-top: 8px;
}
.ai-anomalies p {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--el-color-warning);
  display: flex;
  align-items: center;
  gap: 5px;
}
.ai-suggestion {
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  gap: 5px;
}
.rp-sql {
  margin-top: 12px;
  border-top: 1px dashed var(--border);
  padding-top: 10px;
}
.sql-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--ikb);
  font-size: 13px;
  font-weight: 600;
  padding: 2px 0;
}
.sql-block {
  margin: 10px 0 0;
  padding: 14px 16px;
  background: linear-gradient(135deg, #00164f, #002fa7);
  color: #d6e2ff;
  border-radius: var(--radius-md);
  font-size: 12.5px;
  line-height: 1.7;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
/* 溯源块（T10-3）：与 SQL 块同套折叠交互；浅底细边框，不抢主视觉 */
.lineage-count {
  color: var(--ikb-dim, #7a8aa3);
  font-weight: 400;
  font-size: 12px;
}
.lineage-block {
  margin: 10px 0 0;
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  font-size: 12.5px;
  line-height: 1.7;
}
.lineage-skill {
  margin: 0 0 6px;
  color: var(--ikb-dim, #7a8aa3);
}
.lineage-list {
  margin: 0;
  padding-left: 18px;
}
.lineage-item {
  margin: 2px 0;
}
.lt-name {
  font-weight: 600;
}
.lt-phys {
  margin-left: 8px;
  color: var(--ikb-dim, #7a8aa3);
  font-size: 12px;
}
.lt-desc {
  color: var(--ikb-dim, #7a8aa3);
  font-size: 12px;
}
.watermark {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 6;
  /* 平铺重复水印（SVG 背景），替代原单条居中、7% 透明度几乎不可见的样式 */
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='260' height='150'%3E%3Ctext x='8' y='82' font-family='sans-serif' font-size='15' font-weight='700' fill='%23002fa7' fill-opacity='0.13' transform='rotate(-24 130 75)'%3EAI 数据助理 · 分享快照%3C/text%3E%3C/svg%3E");
  background-repeat: repeat;
  background-size: 260px 150px;
}
</style>
