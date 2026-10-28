<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EChartsOption } from 'echarts'
import WaterfallChart from '@/components/chart/WaterfallChart.vue'
import BaseChart from '@/components/chart/BaseChart.vue'
import { attributionApi } from '@/api/attribution'
import { metricApi } from '@/api/metric'
import type {
  AttributionDimension,
  AttributionReq,
  AttributionTrendPoint,
  AttributionVO,
  ContributionItem,
  MetricVO
} from '@/types'
import { formatNumber } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import { exportXlsx, fileStamp } from '@/utils/exportFile'

/** 导出归因贡献明细（Excel，全部维度成员）。 */
async function exportAttribution(): Promise<void> {
  const items = (result.value?.items ?? []) as unknown as Array<Record<string, unknown>>
  if (!items.length) {
    ElMessage.warning('暂无可导出的归因数据')
    return
  }
  const columns = [
    { key: 'label', label: '维度成员' },
    { key: 'cur', label: '本期' },
    { key: 'base', label: '基期' },
    { key: 'delta', label: '贡献' },
    { key: 'share', label: '占比' }
  ]
  await exportXlsx(`归因明细-${fileStamp()}`, columns, items, '归因明细')
}

/**
 * 维度归因面板（需求 §3.1）：回答「指标为什么涨 / 跌」。
 *
 * <p><b>为什么独立于问数主链路</b>：一次归因要同时给出趋势、量价因子、维度贡献三份语义不同的
 * 产物，并把三套 SQL 分别透出供审计复算；问数技能契约只能承载单一结果集，硬塞会让三份结果
 * 挤成一张表。因此本面板直接调用 /api/attribution。
 *
 * <p><b>置信度门控（§3.2）</b>：后端按可观测信号打分并给出 gate——
 * DIRECT 直出；CLARIFY 弹出单轮澄清（选基准 + 维度，最多 1 轮，重跑时带 clarified=true，
 * 后端不再回 CLARIFY，从机制上杜绝无限多轮）；BLOCK 不展示图表，提示去调优中心完善语义。
 *
 * <p><b>涨跌配色</b>：遵循中国市场惯例，正向拉动=红、负向拖累=绿。
 */
const props = withDefaults(
  defineProps<{
    /** 当前数据源（用于过滤可归因指标）。 */
    datasourceId?: number | null
    /** 用户问题文本：命中波动类语义时自动展开本模块。 */
    question?: string | null
    /** 预设指标（如本次问数命中了某指标），有则不显示指标下拉。 */
    metricId?: number | null
    /** 只读态（公开分享页）：隐藏交互式归因入口。 */
    readonly?: boolean
    /**
     * 常驻嵌入模式（问数调优「维度归因」tab）：默认展开、不渲染折叠按钮、去掉对话结果内的分隔样式。
     * 对话场景不传（默认 false），保持"波动类问题自动展开、其余可点开"的原行为。
     */
    embedded?: boolean
    /**
     * 发起归因的问数历史 id（对话问数场景由结果面板传入）。
     * 非空时后端把归因结果回写到该条 query_history，历史回放即可还原归因面板
     * （与解读 / 校验同口径：问数当次产物，只存不重算）。指标库等无历史上下文的场景留空。
     */
    historyId?: number | null
    /**
     * 回放预置结果：历史回放时把后端存的 attribution 直接喂进来展示，**不重跑**。
     * 有值即视为"当时已跑过"并展开面板；用户仍可改参数手动重跑（重跑会覆盖并回写）。
     */
    presetResult?: AttributionVO | null
  }>(),
  {
    datasourceId: null,
    question: null,
    metricId: null,
    readonly: false,
    embedded: false,
    historyId: null,
    presetResult: null
  }
)

/** 波动类问题关键词：命中则默认展开归因模块（不强制，用户仍可收起）。 */
const FLUCTUATION = /(为什么|为何|啥原因|什么原因|下降|下滑|上升|增长|波动|涨|跌|回落|骤)/

const open = ref(props.embedded)
const loading = ref(false)
const running = ref(false)

const metrics = ref<MetricVO[]>([])
const dimensions = ref<AttributionDimension[]>([])
const result = ref<AttributionVO | null>(null)
/** 当前指标是否尚未沉淀 canonical 口径（后端下发），未沉淀时给出治理指引而非空白。 */
const noDefinition = ref(false)
/**
 * 最近一次归因/维度加载失败的原因（面板内持久展示）。
 *
 * <p><b>为什么必须在面板内展示而不能只靠 toast</b>：全局拦截器已对失败弹过一次 toast（约 3 秒
 * 消失），若面板内不留痕，执行被拒（如非可加度量「已阻止归因」）后面板回到空状态，看起来
 * "什么都没发生/归因不见了"。持久 alert 让用户在面板里直接看到原因与治理建议；下次执行
 * 或切换指标时清除。
 */
const runError = ref('')

const metricId = ref<number | null>(props.metricId)
const dimension = ref<string>('')
const baseline = ref<string>('MOM')
/** 时间粒度：month=按月 / day=按日；后端已支持日粒度，前端此前写死 month，此处放开选择。 */
const granularity = ref<string>('month')

const granularityOptions = [
  { value: 'month', label: '按月' },
  { value: 'day', label: '按日' }
]
/** 单轮澄清弹窗（最多 1 轮）：CLARIFY 时弹出，确认后带 clarified=true 重跑一次。 */
const clarifyVisible = ref(false)
const clarified = ref(false)

const baselineOptions = [
  { value: 'MOM', label: '环比上期' },
  { value: 'YOY', label: '同比去年同期' },
  { value: 'PEAK', label: '对比历史高点' }
]

/** 可归因指标：必须已沉淀 canonical 口径表达式（否则后端会拒绝并提示先补口径）。 */
const availableMetrics = computed(() =>
  metrics.value.filter((m) => {
    if (!m.definition || !m.definition.trim()) return false
    return props.datasourceId == null || m.datasourceId === props.datasourceId
  })
)

const isFluctuation = computed(() => !!props.question && FLUCTUATION.test(props.question))
const canRun = computed(() => metricId.value != null && !!dimension.value && !running.value)

/** 门控：BLOCK 时不展示图表，只给治理指引。 */
const blocked = computed(() => result.value?.gate === 'BLOCK')
const confidenceText = computed(() => {
  const c = result.value?.confidence
  return c == null ? '—' : `${Math.round(c * 100)}%`
})
const confidenceTone = computed(() => {
  const c = result.value?.confidence
  if (c == null) return 'info'
  if (c >= 0.6) return 'success'
  if (c >= 0.3) return 'warning'
  return 'danger'
})

/** 瀑布图数据：基期水位 → 各成员贡献 → 本期水位。 */
const waterfallSteps = computed(() =>
  (result.value?.items ?? []).map((i: ContributionItem) => ({ name: i.label, value: i.delta }))
)

/** 趋势折线图 option：用后端实算的趋势序列（period → value）。 */
const trendOption = computed<EChartsOption | null>(() => {
  const pts = (result.value?.trend ?? []) as AttributionTrendPoint[]
  if (!pts.length) return null
  return {
    grid: { left: 12, right: 16, top: 24, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: pts.map((p) => p.period),
      axisLabel: { interval: 0, hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: (v: number) => formatNumber(v) }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        showSymbol: true,
        symbolSize: 5,
        data: pts.map((p) => p.value),
        areaStyle: { opacity: 0.12 },
        lineStyle: { width: 2 },
        itemStyle: { color: '#002FA7' }
      }
    ]
  } as EChartsOption
})

/** 因子表项类型着色：BASE/TOTAL 为水位，VOLUME/PRICE/CROSS 为效应。 */
function factorTone(kind: string): string {
  if (kind === 'BASE' || kind === 'TOTAL') return 'info'
  return 'primary'
}

function deltaClass(v: number | null | undefined): string {
  if (v == null || v === 0) return 'flat'
  return v > 0 ? 'up' : 'down'
}

async function loadMetrics(): Promise<void> {
  if (metrics.value.length) return
  try {
    metrics.value = await metricApi.list()
  } catch {
    // 失败提示由全局拦截器统一弹出（此前此处再弹一次 → 同一错误出现两条 toast）
  }
}

async function loadDimensions(): Promise<void> {
  dimension.value = ''
  dimensions.value = []
  runError.value = ''
  // 回放态（有 presetResult）不清空：loadDimensions 由 metricId 变化触发，
  // 若无条件清空会把刚回填的历史归因结果抹掉（表现为"回放看不到归因"）
  if (props.presetResult == null) result.value = null
  noDefinition.value = false
  if (metricId.value == null) return
  try {
    const opt = await attributionApi.dimensions(metricId.value)
    dimensions.value = opt.dimensions || []
    noDefinition.value = !opt.hasDefinition
    // P1：优先回填指标沉淀的归因默认值，其次首个可用维度
    const presetDim =
      opt.defaultDimension && dimensions.value.some((d) => d.column === opt.defaultDimension)
        ? opt.defaultDimension
        : dimensions.value.length
          ? dimensions.value[0].column
          : ''
    dimension.value = presetDim
    if (opt.defaultBaseline) baseline.value = opt.defaultBaseline
  } catch (e: any) {
    // 拦截器已 toast 一次；此处把原因持久落到面板内（如"未沉淀口径/语义层未标记维度"）
    runError.value = e?.message || '维度列表加载失败（请确认指标已沉淀口径且语义层已标记维度）'
  }
}

/**
 * 回放回填：历史详情带回的 attribution 直接作为结果展示，**不重跑**——
 * 归因是"问数当时"的结论，重跑既耗资源也可能因数据/口径已变而与当时不一致。
 * 用 immediate 保证首帧即有值；父组件切换历史（换 presetResult）时同样同步。
 * 用户仍可改参数手动重跑，重跑会覆盖展示并回写覆盖库里的旧结论。
 */
watch(
  () => props.presetResult,
  (v) => {
    if (!v) return
    result.value = v
    open.value = true
    // 回填当时的维度/基准，避免用户点"重跑"时参数与当时结论对不上
    if (v.dimension) dimension.value = v.dimension
    if (v.baseline) baseline.value = v.baseline
  },
  { immediate: true }
)

async function run(opts?: { clarified?: boolean }): Promise<void> {
  if (!canRun.value) return
  running.value = true
  runError.value = ''
  try {
    const payload: AttributionReq = {
      metricId: metricId.value as number,
      dimension: dimension.value,
      baseline: baseline.value,
      granularity: granularity.value,
      topN: 10,
      clarified: opts?.clarified ?? clarified.value,
      // 带上历史 id：后端据此把结果回写到该条问数历史，回放时才能还原（指标库场景为 null，不回写）
      historyId: props.historyId
    }
    result.value = await attributionApi.run(payload)
    // 中等置信度且尚未澄清过 → 弹单轮澄清（确认后带 clarified=true 重跑，仅此一轮）
    if (result.value.gate === 'CLARIFY' && !clarified.value) {
      clarifyVisible.value = true
    }
  } catch (e: any) {
    // 拦截器已 toast 一次；被治理性拒绝（如非可加度量"已阻止归因"）时，
    // 原因必须在面板内持久可见——否则 toast 消失后面板回到空状态，形同"归因不展示"
    runError.value = e?.message || '归因执行失败'
  } finally {
    running.value = false
  }
}

/** 澄清确认：带上用户选定的基准与维度重跑一次，并标记已澄清（后续不再弹窗）。 */
async function confirmClarify(): Promise<void> {
  clarifyVisible.value = false
  clarified.value = true
  await run({ clarified: true })
}

/** 展开时的惰性加载：指标列表必加载，已预置指标时顺带取维度。 */
async function onOpen(): Promise<void> {
  loading.value = true
  try {
    await loadMetrics()
    if (metricId.value != null) await loadDimensions()
  } finally {
    loading.value = false
  }
}

function toggle(): void {
  open.value = !open.value
}

const sqlTab = ref<'trend' | 'factor' | 'attribution'>('attribution')
const showSql = ref(false)
const currentSql = computed(() => {
  if (!result.value) return ''
  if (sqlTab.value === 'trend') return result.value.trendSql || '（本次未生成趋势 SQL）'
  if (sqlTab.value === 'factor') return result.value.factorSql || '（缺量字段，未做量价拆解）'
  return result.value.attributionSql || ''
})

async function copyCurrentSql(): Promise<void> {
  const ok = await copyText(currentSql.value)
  if (ok) ElMessage.success('已复制归因 SQL')
  else ElMessage.warning('复制失败，请手动选择文本')
}

watch(
  () => props.metricId,
  (v) => {
    metricId.value = v
    if (v != null && open.value) loadDimensions()
  }
)

// 波动类问题自动展开：保持"一键查询"体验，用户无需先点开再分析。
// 这里只负责「展开」，加载统一由下面的 open 监听承担，避免两条链路对同一指标重复请求。
watch(
  () => props.question,
  (q) => {
    if (q && isFluctuation.value && !props.readonly) open.value = true
  },
  { immediate: true }
)

// 展开即加载。immediate 覆盖常驻嵌入模式（embedded=true）的初始展开，
// 对话模式初始 open=false，不会产生多余请求。
watch(
  open,
  (v) => {
    if (v) void onOpen()
  },
  { immediate: true }
)
</script>

<template>
  <div v-if="!readonly" class="attribution-panel" :class="{ 'is-embedded': embedded }">
    <button v-if="!embedded" class="ap-toggle" @click="toggle">
      <el-icon><component :is="open ? 'ArrowDown' : 'ArrowRight'" /></el-icon>
      <span>维度波动归因</span>
      <el-tag v-if="isFluctuation" size="small" type="warning" effect="light" round>
        检测到波动类问题
      </el-tag>
      <el-tag v-if="result" size="small" :type="confidenceTone" effect="light" round>
        置信度 {{ confidenceText }}
      </el-tag>
    </button>

    <div v-if="open" class="ap-body">
      <!-- 参数区：指标 → 维度 → 对比基准 -->
      <div class="ap-params">
        <div v-if="props.metricId == null" class="ap-field">
          <span class="ap-label">指标</span>
          <el-select
            v-model="metricId"
            size="small"
            placeholder="选择已沉淀口径的指标"
            style="width: 200px"
            @change="loadDimensions"
          >
            <el-option
              v-for="m in availableMetrics"
              :key="m.id"
              :label="m.alias || m.name || ''"
              :value="m.id"
            />
          </el-select>
        </div>
        <div v-if="noDefinition && metricId != null" class="ap-tip">
          该指标尚未沉淀 canonical 口径，归因需先在指标库补全口径表达式
        </div>
        <div class="ap-field">
          <span class="ap-label">归因维度</span>
          <el-select v-model="dimension" size="small" placeholder="选择维度" style="width: 160px">
            <el-option
              v-for="d in dimensions"
              :key="d.column"
              :label="d.label"
              :value="d.column"
            />
          </el-select>
        </div>
        <div class="ap-field">
          <span class="ap-label">对比基准</span>
          <el-select v-model="baseline" size="small" style="width: 140px">
            <el-option
              v-for="b in baselineOptions"
              :key="b.value"
              :label="b.label"
              :value="b.value"
            />
          </el-select>
        </div>
        <div class="ap-field">
          <span class="ap-label">时间粒度</span>
          <el-select v-model="granularity" size="small" style="width: 110px">
            <el-option
              v-for="g in granularityOptions"
              :key="g.value"
              :label="g.label"
              :value="g.value"
            />
          </el-select>
        </div>
        <el-button size="small" type="primary" :loading="running" :disabled="!canRun" @click="run()">
          执行归因
        </el-button>
      </div>

      <!-- 失败原因持久展示：拦截器只弹一次性 toast，此处保证被拒原因（如非可加度量）留在面板内 -->
      <el-alert
        v-if="runError"
        type="error"
        show-icon
        :closable="false"
        :title="runError"
        class="ap-alert"
      />

      <el-empty
        v-if="!result && !running"
        description="选择指标与维度后执行归因，将给出各维度成员的拉动贡献"
        :image-size="56"
      />

      <!-- BLOCK：置信度 <30%，禁止自动归因，只给治理指引 -->
      <el-alert
        v-if="blocked"
        type="error"
        show-icon
        :closable="false"
        title="置信度不足 30%，已阻止自动归因"
        :description="result?.insight || '请先完善指标口径与语义层标记'"
        class="ap-alert"
      />

      <template v-if="result && !blocked">
        <div class="ap-summary">
          <span class="ap-metric">{{ result.metricName }}</span>
          <span class="ap-dim">按「{{ result.dimensionLabel }}」拆解</span>
          <span class="ap-period">{{ result.basePeriod }} → {{ result.currentPeriod }}</span>
        </div>

        <!-- 趋势折线（后端实算的按期序列，独立展示，与三套审计 SQL 解耦） -->
        <div v-if="trendOption" class="ap-block">
          <div class="ap-block-title">指标趋势</div>
          <BaseChart :option="trendOption" height="240px" />
        </div>

        <!-- 量价因子瀑布（有量字段时才产出） -->
        <div v-if="result.factors && result.factors.length" class="ap-block">
          <div class="ap-block-title">量价因子拆解</div>
          <div class="ap-factors">
            <div v-for="f in result.factors" :key="f.kind + f.name" class="ap-factor">
              <el-tag size="small" :type="factorTone(f.kind)" effect="light">{{ f.name }}</el-tag>
              <span class="ap-factor-value" :class="deltaClass(f.value)">
                {{ formatNumber(f.value) }}
              </span>
              <span class="ap-factor-desc">{{ f.desc }}</span>
            </div>
          </div>
        </div>

        <!-- 维度贡献瀑布 -->
        <div class="ap-block">
          <div class="ap-block-title">维度贡献瀑布</div>
          <WaterfallChart
            :title="`${result.dimensionLabel} 贡献分解`"
            base-name="基期"
            :base-value="result.baseTotal ?? 0"
            total-name="本期"
            :total-value="result.curTotal ?? 0"
            :steps="waterfallSteps"
            :height="260"
          />
        </div>

        <!-- 贡献明细表（按贡献绝对值降序） -->
        <div class="ap-block">
          <div class="ap-block-title">
            贡献明细
            <el-button size="small" text @click="exportAttribution">
              <el-icon><Download /></el-icon> 导出
            </el-button>
          </div>
          <el-table :data="result.items ?? []" size="small" max-height="260">
            <el-table-column prop="label" label="维度成员" min-width="120" />
            <el-table-column label="本期" align="right" min-width="100">
              <template #default="{ row }">{{ formatNumber(row.cur) }}</template>
            </el-table-column>
            <el-table-column label="基期" align="right" min-width="100">
              <template #default="{ row }">{{ formatNumber(row.base) }}</template>
            </el-table-column>
            <el-table-column label="贡献" align="right" min-width="110">
              <template #default="{ row }">
                <span :class="deltaClass(row.delta)">{{ formatNumber(row.delta) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="占比" align="right" min-width="80">
              <template #default="{ row }">
                {{ row.share == null ? '—' : (row.share * 100).toFixed(1) + '%' }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 归因解读（由结构化结果确定性拼装生成，不参与任何数值计算；非 AI 生成） -->
        <div v-if="result.insight" class="ap-insight">
          <el-icon><Document /></el-icon>
          <span>{{ result.insight }}</span>
        </div>

        <!-- 质量校验命中项 -->
        <div v-if="result.issues && result.issues.length" class="ap-issues">
          <p v-for="(s, i) in result.issues" :key="'is' + i">
            <el-icon><WarningFilled /></el-icon>{{ s }}
          </p>
        </div>

        <!-- 三套 SQL 分离展示（审计要求：可分别查看、复制、重跑）
             §4 SQL 查看权限：无权角色后端不下发 SQL 字段，此处只给不可见提示（非前端隐藏）。 -->
        <div v-if="result.sqlVisible === false" class="ap-sql-denied">
          <el-icon><Lock /></el-icon>
          <span>当前角色不可查看原始 SQL，如需权限请联系管理员配置「SQL 查看角色」</span>
        </div>
        <div v-else class="ap-sql">
          <div class="ap-sql-tabs">
            <button
              v-for="t in [
                { k: 'trend', n: '趋势 SQL' },
                { k: 'factor', n: '因子 SQL' },
                { k: 'attribution', n: '归因 SQL' }
              ]"
              :key="t.k"
              :class="['ap-sql-tab', { active: sqlTab === t.k }]"
              @click="sqlTab = t.k as 'trend' | 'factor' | 'attribution'"
            >
              {{ t.n }}
            </button>
            <button class="ap-sql-toggle" @click="showSql = !showSql">
              {{ showSql ? '收起' : '查看' }}
            </button>
            <el-button v-if="showSql" size="small" text @click="copyCurrentSql">
              <el-icon><CopyDocument /></el-icon> 复制
            </el-button>
          </div>
          <pre v-if="showSql" class="ap-sql-block mono">{{ currentSql }}</pre>
        </div>
      </template>
    </div>

    <!-- 单轮澄清：仅在 CLARIFY 门控且未澄清过时弹出一次 -->
    <el-dialog v-model="clarifyVisible" title="请确认归因口径" width="420px">
      <p class="ap-clarify-tip">
        本次归因置信度偏低，确认对比基准与维度后将立即重跑（仅此一轮，不会再追问）。
      </p>
      <div class="ap-field">
        <span class="ap-label">对比基准</span>
        <el-select v-model="baseline" size="small" style="width: 160px">
          <el-option
            v-for="b in baselineOptions"
            :key="b.value"
            :label="b.label"
            :value="b.value"
          />
        </el-select>
      </div>
      <div class="ap-field">
        <span class="ap-label">归因维度</span>
        <el-select v-model="dimension" size="small" style="width: 160px">
          <el-option v-for="d in dimensions" :key="d.column" :label="d.label" :value="d.column" />
        </el-select>
      </div>
      <template #footer>
        <el-button size="small" @click="clarifyVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmClarify">确认并重跑</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.attribution-panel {
  margin: 10px 0 4px;
  border-top: 1px dashed var(--el-border-color-light, #e4e7ed);
  padding-top: 8px;
}
/* 常驻嵌入（调优 tab）：无折叠头，也就不需要与上方结果分隔的虚线 */
.attribution-panel.is-embedded {
  margin: 0;
  border-top: none;
  padding-top: 0;
}
.attribution-panel.is-embedded .ap-body {
  margin-top: 0;
}
.ap-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
  padding: 2px 0;
}
.ap-body {
  margin-top: 8px;
}
.ap-params {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.ap-tip {
  margin-bottom: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.5;
}
.ap-field {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.ap-label {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
.ap-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin-bottom: 8px;
}
.ap-metric {
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}
.ap-block {
  margin-bottom: 12px;
}
.ap-block-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--el-text-color-regular, #606266);
}
.ap-factors {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ap-factor {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
}
.ap-factor-value {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.ap-factor-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
/* 中国市场惯例：涨红跌绿 */
.up {
  color: #d93026;
}
.down {
  color: #13a463;
}
.flat {
  color: var(--el-text-color-secondary, #909399);
}
.ap-insight {
  display: flex;
  gap: 6px;
  font-size: 13px;
  line-height: 1.6;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter, #f5f7fa);
  margin-bottom: 8px;
}
.ap-issues p {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 2px 0;
  font-size: 12px;
  color: var(--el-color-warning, #e6a23c);
}
.ap-alert {
  margin-bottom: 10px;
}
.ap-sql-denied {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
}
.ap-sql {
  margin-top: 6px;
}
.ap-sql-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ap-sql-tab {
  background: none;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 4px;
  font-size: 12px;
  padding: 2px 8px;
  cursor: pointer;
  color: var(--el-text-color-regular, #606266);
}
.ap-sql-tab.active {
  border-color: var(--el-color-primary, #002fa7);
  color: var(--el-color-primary, #002fa7);
}
.ap-sql-toggle {
  background: none;
  border: none;
  font-size: 12px;
  cursor: pointer;
  color: var(--el-text-color-secondary, #909399);
}
.ap-sql-block {
  margin: 6px 0 0;
  padding: 8px;
  background: var(--el-fill-color-lighter, #f5f7fa);
  border-radius: 6px;
  font-size: 12px;
  max-height: 180px;
  overflow: auto;
}
.ap-clarify-tip {
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
  margin: 0 0 10px;
}
</style>
