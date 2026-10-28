/**
 * 指标编辑抽屉（T7.3 自 MetricView 逐字抽离）：改名/描述/别名/canonical 口径/
 * 归因默认维度与基准/新鲜度 SLA/列展示名/图表标注，外加血缘懒加载与原始 SQL 复制。
 *
 * 列展示名：必须跑一次 SQL 才知道列清单（汇总/派生列不在 schema 里）；
 * 读取失败时仅展示已配置的列，且提交时原样透传旧配置，避免因拿不到列名而误删已有自定义。
 * 图表标注：直接维护 metric.chart_spec.style，存后指标预览、分享快照与所有引用该指标
 * 的大屏 widget 都按此渲染（widget 自身设了覆盖时以覆盖为准）。
 */
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { metricApi } from '@/api/metric'
import { lineageApi } from '@/api/lineage'
import { attributionApi } from '@/api/attribution'
import type { AttributionDimension, ChartSpec, ChartStyle, LineageVO, MetricForm, MetricVO, QueryResult } from '@/types'
import { displayColumns, pruneColumnLabels } from '@/utils/format'
import { pruneChartStyle } from '@/utils/chartStyle'
import { useActionGuard } from '@/utils/actionGuard'
import { copyText } from '@/utils/clipboard'
import type { MetricCore } from './useMetricCore'

export function useMetricEdit(core: MetricCore) {
  const editVisible = ref(false)
  const editing = ref<MetricVO | null>(null)
  const { busy: submitting, run: runSubmit } = useActionGuard()
  const editFormRef = ref<FormInstance>()
  const editForm = reactive<{
    name: string
    description: string
    alias: string
    /** canonical 口径表达式：趋势 / 因子 / 归因共用，留空=未沉淀（归因会被后端拒绝）。 */
    definition: string
    attributionDimension: string
    baselineType: string
    freshnessHours: number | null
  }>({
    name: '',
    description: '',
    alias: '',
    definition: '',
    attributionDimension: '',
    baselineType: '',
    freshnessHours: null
  })
  /** 归因默认维度候选（来自 /api/attribution/dimensions，已剔除敏感列）。 */
  const editDimensions = ref<AttributionDimension[]>([])
  const baselineOptions = [
    { value: 'MOM', label: '环比上期' },
    { value: 'YOY', label: '同比去年同期' },
    { value: 'PEAK', label: '对比历史高点' }
  ]
  const editRules: FormRules = {
    name: [{ required: true, message: '请输入指标名称', trigger: 'blur' }]
  }

  /** 列展示名编辑态：草稿（物理列名 → 展示名）+ 列清单 + 一次刷新取到的结果。 */
  const editLabels = ref<Record<string, string>>({})
  const editLabelRows = ref<{ column: string; fallback: string }[]>([])
  const editResult = ref<QueryResult | null>(null)
  const editColsLoading = ref(false)

  /** 编辑中的指标级图表标注（chart_spec.style）。 */
  const editStyle = ref<ChartStyle | null>(null)

  /** 把编辑后的标注并回 chart_spec；无图表规格（纯明细指标）时无处可挂，原样透传。 */
  function withStyle(spec: ChartSpec | null | undefined, style: ChartStyle | null): ChartSpec | null {
    if (!spec) return spec ?? null
    return { ...spec, style: pruneChartStyle(style) }
  }

  function onEditStyle(s: ChartStyle | null): void {
    editStyle.value = s
  }

  async function loadEditCols(m: MetricVO): Promise<void> {
    editColsLoading.value = true
    try {
      const data = await metricApi.refresh(m.id)
      editResult.value = data.result
      const defaults = displayColumns(data.result)
      const saved = m.columnLabels ?? {}
      editLabelRows.value = (data.result?.columns ?? []).map((c, i) => ({ column: c, fallback: defaults[i] || c }))
      const next: Record<string, string> = {}
      editLabelRows.value.forEach((r) => {
        next[r.column] = saved[r.column]?.trim() || r.fallback
      })
      editLabels.value = next
    } catch {
      // 拦截器已提示；保留已配置列的草稿，名称/描述仍可正常保存
    } finally {
      editColsLoading.value = false
    }
  }

  /** 手动重读结果列（首次读取失败时的兜底入口）。 */
  async function reloadEditCols(): Promise<void> {
    if (editing.value) {
      await loadEditCols(editing.value)
    }
  }

  /** 复制编辑抽屉里的原始 SQL（线上 HTTP 环境也能复制，见 utils/clipboard）。 */
  async function copySql(): Promise<void> {
    const sql = editing.value?.sql
    if (!sql) return
    const ok = await copyText(sql)
    if (ok) ElMessage.success('原始 SQL 已复制')
    else ElMessage.warning('复制失败，请手动选择文本复制')
  }

  /**
   * 指标血缘（需求 §3.3）：来源表/字段、口径表达式、被哪些看板引用。
   * 打开抽屉时懒加载一次；无血缘读权限时不请求也不渲染。
   */
  const lineage = ref<LineageVO | null>(null)
  const lineageLoading = ref(false)

  async function loadLineage(id: number): Promise<void> {
    if (!core.can('lineage:metric:read')) return
    lineage.value = null
    lineageLoading.value = true
    try {
      lineage.value = await lineageApi.metric(id)
    } catch {
      lineage.value = null // 血缘是增强信息，失败不打断编辑
    } finally {
      lineageLoading.value = false
    }
  }

  async function openEdit(m: MetricVO): Promise<void> {
    editing.value = m
    lineage.value = null
    editForm.name = m.name
    editForm.description = m.description || ''
    editForm.alias = m.alias || ''
    editForm.definition = m.definition || ''
    editForm.attributionDimension = m.attributionDimension || ''
    editForm.baselineType = m.baselineType || 'MOM'
    editForm.freshnessHours = m.freshnessHours ?? null
    editDimensions.value = []
    editResult.value = null
    editLabels.value = { ...(m.columnLabels ?? {}) }
    editLabelRows.value = Object.keys(m.columnLabels ?? {}).map((c) => ({ column: c, fallback: c }))
    // 预填已存标注（含后端存指标时自动推断的单位），用户在此基础上微调
    editStyle.value = m.chartSpec?.style ?? null
    editVisible.value = true
    if (m.id != null) {
      void loadLineage(m.id)
      void loadEditDimensions(m.id)
    }
    await loadEditCols(m)
  }

  /** 拉取归因默认维度候选；失败不影响编辑（下拉可手填）。 */
  async function loadEditDimensions(id: number): Promise<void> {
    try {
      const opt = await attributionApi.dimensions(id)
      editDimensions.value = opt.dimensions || []
    } catch {
      editDimensions.value = []
    }
  }

  async function submitEdit(): Promise<void> {
    if (!editFormRef.value || !editing.value) return
    const valid = await editFormRef.value.validate().catch(() => false)
    if (!valid) return
    const m = editing.value
    await runSubmit(async () => {
      const payload: MetricForm = {
        name: editForm.name.trim(),
        description: editForm.description.trim() || null,
        alias: editForm.alias.trim() || null,
        // canonical 口径：后端 update 是全量 set，必须显式回传；留空=未沉淀（归因会被后端拒绝）
        definition: editForm.definition.trim() || null,
        attributionDimension: editForm.attributionDimension.trim() || null,
        baselineType: editForm.baselineType || null,
        // 新鲜度 SLA 覆盖：留空/0 = 回落全局阈值
        freshnessHours: editForm.freshnessHours && editForm.freshnessHours > 0 ? editForm.freshnessHours : null,
        datasourceId: m.datasourceId ?? 0,
        question: m.question ?? null,
        sql: m.sql ?? '',
        chartSpec: withStyle(m.chartSpec, editStyle.value),
        // 拿到结果列时才收敛（剔除与默认名相同的条目）；否则原样透传，不拿旧配置冒险
        columnLabels: editResult.value
          ? pruneColumnLabels(editResult.value, editLabels.value)
          : (m.columnLabels ?? null)
      }
      await metricApi.update(m.id, payload)
      ElMessage.success('指标已更新')
      editVisible.value = false
      await core.load()
    })
  }

  return {
    editVisible,
    editing,
    submitting,
    editFormRef,
    editForm,
    editDimensions,
    baselineOptions,
    editRules,
    editLabels,
    editLabelRows,
    editResult,
    editColsLoading,
    editStyle,
    onEditStyle,
    reloadEditCols,
    copySql,
    lineage,
    lineageLoading,
    openEdit,
    submitEdit
  }
}
