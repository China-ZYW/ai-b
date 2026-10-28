<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import BaseChart from '@/components/chart/BaseChart.vue'
import { exportChartPng, exportXlsx, fileStamp } from '@/utils/exportFile'
import CountUp from '@/components/common/CountUp.vue'
import InfographicWidget from '@/components/dashboard/InfographicWidget.vue'
import ImageWidget from '@/components/dashboard/ImageWidget.vue'
import ListWidget from '@/components/dashboard/ListWidget.vue'
import { buildChartOption } from '@/composables/useChartOption'
import { useConfigStore } from '@/stores/config'
import { resolveTheme, themeVersion, type DashboardTheme } from '@/theme/dashboardTheme'
import type { ChartSpec, ChartStyle, ImageWidgetSpec, InfographicSpec, ListWidgetSpec, QueryResult, WidgetStyle } from '@/types'
import { formatElapsed, formatNumber, displayColumns, applyEnumDicts } from '@/utils/format'
import { resolveChartStyle, formatChartValueWithUnit, type ResolvedChartStyle } from '@/utils/chartStyle'

/**
 * 驾驶舱/大屏单个 widget 卡片（纯展示）：按绑定指标的实时结果渲染图表，
 * TABLE 或不可视化时回退明细表；未绑定指标时显示占位。数据由父组件传入
 * （编辑器逐块 refresh、大屏批量 /data），本组件不发请求。
 *
 * 主题：优先用父组件传入的已合并令牌 {@code tokens}（含大屏级自定义背景/卡片/字体/配色覆盖）；
 * 未传时回落到按 {@code theme} key 解析的预设/远端主题。逐组件覆盖 {@code styleOverride}
 * 再叠加一层（卡片背景/边框/标题色/圆角），实现「逐组件自定义样式」。
 *
 * <p>图表标注优先级：{@code chartStyle}（widget 级覆盖，来自大屏 layout）&gt; {@code chartSpec.style}
 * （指标自身）&gt; 租户级默认 &gt; 代码默认；同一指标被多个 widget 引用时可各自标注不同。
 */
const props = withDefaults(
  defineProps<{
    title?: string | null
    metricId?: number | null
    chartSpec?: ChartSpec | null
    /** widget 级图表标注覆盖（轴名/单位/数值格式/图例），空则沿用指标自身的 chart_spec。 */
    chartStyle?: ChartStyle | null
    result?: QueryResult | null
    loading?: boolean
    theme?: string | null
    /** 已合并的大屏主题令牌（含自定义样式覆盖）；提供时优先于 theme key。 */
    tokens?: DashboardTheme | null
    /** 本 widget 的视觉样式覆盖（逐组件卡片/标题/强调色/圆角）。 */
    styleOverride?: WidgetStyle | null
    /** 图表高度（编辑态用固定 px，大屏用 100% 撑满）。 */
    chartHeight?: string
    compact?: boolean
    /** 是否处于可编辑上下文（编辑器）；大屏等非编辑场景置 false，占位提示改为中性文案。 */
    editable?: boolean
    /** 未绑定指标时的占位提示文案。 */
    emptyHint?: string
    /** 入场动画错峰序号（大屏展示用，编辑态不动画）；不传则不加延迟。 */
    index?: number
    /** 信息图配置：仅当本组件为信息图类型时传入，渲染驱动叙事组件（不取数）。 */
    infographic?: InfographicSpec | null
    /** 图片/图标配置：仅当本组件为图片类型时传入，渲染自由放置的图片（不取数）。 */
    image?: ImageWidgetSpec | null
    /** 列表组件配置：仅当本组件为列表类型时传入，渲染绑指标的滚动榜单。 */
    list?: ListWidgetSpec | null
    /**
     * 是否处于大屏刷新中：为真时叠加一道"刷新扫光"+ 外发光脉冲，给用户可见的刷新反馈
     * （图表的数据过渡动画由 ECharts 自身完成，这里补一层整卡可见提示）。
     */
    refreshing?: boolean
  }>(),
  {
    title: null,
    metricId: null,
    chartSpec: null,
    chartStyle: null,
    result: null,
    loading: false,
    theme: 'light',
    tokens: null,
    styleOverride: null,
    chartHeight: '100%',
    compact: false,
    editable: true,
    emptyHint: '从左侧拖入指标',
    infographic: null,
    image: null,
    list: null,
    refreshing: false
  }
)

/**
 * 联动下钻（V4.44）：点击图表点或明细表行时，向上抛 {dim, value, metricId}。
 * dim=指标结果的真实维度列名（chartSpec.xField，剔除合成列 __category__/__value__），
 * value=被点击的维度成员；父组件（大屏/编辑器预览）据此设置整屏联动筛选，后端按维度 SQL 切片。
 * 本组件只负责"发出意图"，是否真正下钻由父组件决定（编辑拖拽态可忽略）。
 */
const emit = defineEmits<{
  (e: 'drill', payload: { dim: string; value: string | number; metricId?: number | null }): void
}>()

/** 可下钻维度列名：取图表 xField（分类/地理/阶段维度），合成列（KPI 转置）不可下钻。 */
const drillDim = computed(() => {
  const x = props.chartSpec?.xField
  if (typeof x === 'string' && x && !x.startsWith('__')) return x
  return ''
})

function emitDrill(value: unknown): void {
  if (!drillDim.value) return
  if (value === undefined || value === null || value === '') return
  emit('drill', {
    dim: drillDim.value,
    value: value as string | number,
    metricId: props.metricId
  })
}

/** 图表点击：echarts 点击点的分类名即维度成员值（params.name）。 */
function onPointClick(p: {
  name?: string | number
  seriesName?: string
  value?: unknown
  componentType?: string
}): void {
  // 散点/气泡等的 value 可能是 [x, y] 数组，优先用分类名；无分类名时回退首个值
  const v = p.name !== undefined && p.name !== null && p.name !== '' ? p.name : (Array.isArray(p.value) ? p.value[0] : p.value)
  emitDrill(v)
}

/** 明细表行点击：取该行 xField 维度的成员值。 */
function onRowClick(row: Record<string, unknown>): void {
  emitDrill(row[drillDim.value])
}

/** 图表实例引用（下载 PNG 用）。 */
const chartRef = ref<{ getDataURL?: (t?: 'png' | 'jpeg', bg?: string) => string | null } | null>(null)

/** 下载图表为 PNG（仅图表形态的卡片可用）。 */
function onDownloadChart(): void {
  const name = `${props.title || '图表'}-${fileStamp()}`
  if (!exportChartPng(() => chartRef.value?.getDataURL?.() ?? null, name)) {
    ElMessage.warning('图表尚未渲染，无法下载')
  }
}

/** 导出本卡片的明细数据（Excel）。 */
async function onExportData(): Promise<void> {
  const data = tableData.value ?? []
  if (!data.length) {
    ElMessage.warning('暂无可导出的数据')
    return
  }
  const cols = columns.value.map((c: string, i: number) => ({ key: c, label: columnLabels.value[i] || c }))
  await exportXlsx(`${props.title || '数据'}-${fileStamp()}`, cols, data as Array<Record<string, unknown>>)
}

/** 解析后的主题令牌：优先父级已合并 tokens，否则按 key 解析（hydration 完成后自动重解析）。 */
const th = computed<DashboardTheme>(() => {
  void themeVersion.value
  if (props.tokens) return props.tokens
  return resolveTheme(props.theme)
})

/** 租户级图表标注默认：公开大屏无登录态时 store 不发请求，自然回落代码默认。 */
const config = useConfigStore()
onMounted(() => {
  void config.load()
})

/** 卡片配色以 CSS 变量下发：先取主题令牌，再叠加逐组件覆盖（任意预设/自定义通用）。 */
const cardVars = computed(() => {
  const vars: Record<string, string> = {
    '--wc-bg': th.value.cardBg,
    '--wc-border': th.value.cardBorder,
    '--wc-title': th.value.textPrimary,
    '--wc-meta': th.value.textSecondary,
    '--wc-muted': th.value.textMuted,
    // L1/L2 装饰着色所需的主色（四角标、扫描光、外发光）
    '--wc-accent': th.value.accent
  }
  if (th.value.fontFamily) vars['--wc-font'] = th.value.fontFamily
  if (typeof th.value.fontScale === 'number') vars['--wc-scale'] = String(th.value.fontScale)
  if (typeof th.value.cardRadius === 'number') vars['--wc-radius'] = `${th.value.cardRadius}px`
  const o = props.styleOverride
  if (o) {
    if (o.cardBg && o.cardBg.trim()) vars['--wc-bg'] = o.cardBg.trim()
    if (o.borderColor && o.borderColor.trim()) vars['--wc-border'] = o.borderColor.trim()
    if (o.titleColor && o.titleColor.trim()) vars['--wc-title'] = o.titleColor.trim()
    if (typeof o.radius === 'number') vars['--wc-radius'] = `${o.radius}px`
  }
  return vars
})

const loadingBg = computed(() => (th.value.dark ? 'rgba(2,12,38,0.55)' : 'rgba(255,255,255,0.7)'))

/**
 * L1/L2 装饰开关：主题令牌 deco='tech' 且非编辑态时渲染。
 * 编辑态刻意不渲染——拖拽调整布局时不该有角标/扫描光干扰，也避免重排闪烁。
 */
const showDeco = computed(() => !props.editable && th.value.deco === 'tech')

/**
 * 展示结果：语义列「枚举字典」配好后，码值换成中文（status=1 → 已支付）。
 * 图表与明细表都吃这份；导出仍用原始 rows，免得导出的文件里找不到原始码值。
 */
const displayResult = computed<QueryResult | null>(() => {
  const raw = props.result
  if (!raw) return null
  const replaced = applyEnumDicts(raw)
  return replaced === raw.rows ? raw : { ...raw, rows: replaced }
})
const chartOption = computed(() =>
  displayResult.value
    ? buildChartOption(props.chartSpec, displayResult.value, th.value, props.chartStyle, config.chartDefaults)
    : null
)
const bound = computed(() => props.metricId != null)
const hasData = computed(() => !!props.result && (props.result.rowCount ?? 0) > 0)

// ---------- KPI 单值读数（数字滚动） ----------
/** 展示样式：widget 覆盖 > spec.style > 租户默认，用于读数单位/小数位。 */
const kpiStyle = computed<ResolvedChartStyle>(() =>
  resolveChartStyle(props.chartSpec?.style ?? null, props.chartStyle, config.chartDefaults)
)
/** 单值指标（仅 1 行 1 度量）用大读数展示，更直观。 */
const isKpi = computed(
  () =>
    !!props.result &&
    (props.result.rowCount ?? props.result.rows.length) === 1 &&
    (props.chartSpec?.yFields?.length ?? 0) === 1
)
const kpiColIdx = computed(() => {
  const cols = props.result?.columns ?? []
  const y = props.chartSpec?.yFields?.[0]
  return y ? cols.indexOf(y) : -1
})
const kpiValue = computed<number>(() => {
  const row = props.result?.rows?.[0]
  if (!row || kpiColIdx.value < 0) return 0
  const v = row[kpiColIdx.value]
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : 0
})
const kpiUnit = computed(() => kpiStyle.value.valueUnit || '')
const kpiFormat = (n: number): string => formatChartValueWithUnit(n, kpiStyle.value)

// 入场动画根样式：仅非编辑态（大屏/公开分享）触发，按 index 错峰（--enter-d）延迟
const rootStyle = computed<Record<string, string>>(() => {
  const s: Record<string, string> = { ...cardVars.value }
  if (!props.editable && typeof props.index === 'number') s['--enter-d'] = String(props.index * 70)
  return s
})

// 明细表兜底：把二维行按列名映射为记录
const columns = computed(() => props.result?.columns ?? [])
/** 列展示名（语义管理的业务别名，无别名兜底列名）：仅作表头文案，prop/key 仍用列名保证取值正确。 */
const columnLabels = computed(() => displayColumns(props.result))
const tableData = computed(() => {
  const rows = displayResult.value?.rows ?? []
  return rows.slice(0, 50).map((r, ri) => {
    const obj: Record<string, unknown> = { __key: ri }
    columns.value.forEach((c, ci) => {
      obj[c] = r[ci]
    })
    return obj
  })
})
</script>

<template>
  <div
    class="widget-card"
    :class="[{ compact, 'is-dark': th.dark, 'is-glow': showDeco && th.glow, 'no-border': th.showCardBorder === false }, !editable ? 'enter-anim' : '']"
    :style="rootStyle"
  >
    <!-- L1 科技装饰层：绝对定位不占布局，因此不影响 smartLayout 的尺寸与装箱结果 -->
    <div v-if="showDeco" class="wc-deco">
      <template v-if="th.corner">
        <i class="wc-corner wc-tl" />
        <i class="wc-corner wc-tr" />
        <i class="wc-corner wc-bl" />
        <i class="wc-corner wc-br" />
      </template>
      <i v-if="th.scan" class="wc-scan" />
    </div>

    <!-- 刷新中可见反馈：一道对角线扫光 + 外发光脉冲（图表数据过渡由 ECharts 自身完成） -->
    <div v-if="refreshing" class="wc-refresh" />

    <div v-if="!compact && (title || !image)" class="wc-head">
      <span class="wc-title truncate">{{ title || (infographic ? '信息图' : (image ? '' : (bound ? '未命名指标' : '空槽位'))) }}</span>
      <span v-if="hasData && !infographic" class="wc-meta">
        {{ formatNumber(result?.rowCount) }} 行 · {{ formatElapsed(result?.executionTimeMs) }}
      </span>
      <!-- 下载 / 导出：有数据才出现（信息图无数据集，不提供） -->
      <span v-if="hasData && !infographic" class="wc-export">
        <el-tooltip v-if="chartOption" content="下载图表 PNG" placement="top">
          <el-icon class="wc-export-btn" @click.stop="onDownloadChart"><Picture /></el-icon>
        </el-tooltip>
        <el-tooltip content="导出数据 Excel" placement="top">
          <el-icon class="wc-export-btn" @click.stop="onExportData"><Download /></el-icon>
        </el-tooltip>
      </span>
    </div>

    <div class="wc-body" v-loading="loading" :element-loading-background="loadingBg">
      <!-- 信息图组件（内容驱动叙事，不取数）：优先于图表/占位渲染。
           accent 注入驾驶舱主色，使信息图主色与驾驶舱一致（详见 InfographicWidget#accent） -->
      <InfographicWidget
        v-if="infographic"
        :spec="infographic"
        :dark="th.dark"
        :font-family="th.fontFamily"
        :accent="th.accent"
        class="wc-info"
      />

      <!-- 图片/图标组件（自由放置的 logo/图标/装饰图片，不取数）：优先于占位渲染 -->
      <ImageWidget v-else-if="image" :spec="image" class="wc-image" />

      <!-- 列表组件（绑定指标，取数后渲染滚动榜单）：与图表同属"有数据"分支 -->
      <ListWidget v-else-if="list" :spec="list" :result="displayResult" class="wc-list" />

      <!-- 未绑定指标 -->
      <div v-else-if="!bound" class="wc-placeholder">
        <el-icon :size="compact ? 26 : 30"><component :is="editable ? 'Plus' : 'DataLine'" /></el-icon>
        <p v-if="!compact">{{ emptyHint }}</p>
      </div>

      <!-- 已绑定但无数据 -->
      <div v-else-if="!hasData && !loading" class="wc-placeholder">
        <el-icon :size="compact ? 24 : 28"><Warning /></el-icon>
        <p v-if="!compact">暂无数据</p>
      </div>

      <template v-else>
        <!-- 单值指标：大读数（数字滚动动画） -->
        <div v-if="isKpi" class="wc-kpi">
          <CountUp class="wc-kpi-value" :value="kpiValue" :format="kpiFormat" :duration="1000" />
          <span v-if="kpiUnit" class="wc-kpi-unit">{{ kpiUnit }}</span>
        </div>

        <!-- 图表 -->
        <BaseChart
          v-else-if="chartOption"
          ref="chartRef"
          :option="chartOption"
          :height="chartHeight"
          :flow="!!th.flow"
          class="wc-chart"
          :class="{ 'is-drillable': !!drillDim }"
          @point-click="onPointClick"
        />

        <!-- 明细表兜底 -->
        <div v-else class="wc-table">
          <el-table :data="tableData" size="small" height="100%" :border="!compact" @row-click="onRowClick">
            <el-table-column
              v-for="(c, i) in columns"
              :key="`${c}-${i}`"
              :prop="c"
              :label="columnLabels[i] || c"
              :min-width="90"
              show-overflow-tooltip
            />
          </el-table>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.widget-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
  border-radius: var(--wc-radius, var(--radius-md));
  background: var(--wc-bg);
  border: 1px solid var(--wc-border);
  font-family: var(--wc-font, inherit);
}
.widget-card.is-dark {
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}
.wc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 12px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--wc-border);
}
.wc-export {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
  flex-shrink: 0;
  opacity: 0.55;
  transition: opacity 0.18s ease;
}
.wc-export:hover {
  opacity: 1;
}
.wc-export-btn {
  cursor: pointer;
  font-size: 14px;
  color: var(--wc-title);
}
.wc-export-btn:hover {
  color: var(--ikb);
}
.wc-title {
  font-size: calc(13.5px * var(--wc-scale, 1));
  font-weight: 700;
  flex: 1;
  min-width: 0;
  color: var(--wc-title);
}
.wc-meta {
  font-size: calc(11.5px * var(--wc-scale, 1));
  color: var(--wc-meta);
  flex-shrink: 0;
}
.wc-body {
  flex: 1;
  min-height: 0;
  position: relative;
  padding: 6px;
}
.compact .wc-body {
  padding: 4px 8px 8px;
}
.wc-chart {
  height: 100%;
}
/* 可下钻图表：鼠标提示可点击（联动下钻 V4.44） */
.wc-chart.is-drillable :deep(canvas) {
  cursor: pointer;
}
.wc-info {
  height: 100%;
}
.wc-table {
  height: 100%;
  overflow: hidden;
}
.wc-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wc-muted);
}
.wc-placeholder p {
  margin: 0;
  font-size: calc(12.5px * var(--wc-scale, 1));
}
/* 深色系主题下的明细表反色，避免白底刺眼、文字不可读 */
.is-dark .wc-table :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(255, 255, 255, 0.05);
  --el-table-text-color: var(--wc-title);
  --el-table-header-text-color: var(--wc-title);
  --el-table-border-color: var(--wc-border);
  --el-table-row-hover-bg-color: rgba(255, 255, 255, 0.07);
  background: transparent;
}
.is-dark .wc-table :deep(.el-table__empty-text) {
  color: var(--wc-muted);
}
/* 单值指标大读数（数字滚动） */
.wc-kpi {
  height: 100%;
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 8px;
  padding: 4px 8px;
}
.wc-kpi-value {
  font-size: calc(30px * var(--wc-scale, 1));
  font-weight: 800;
  color: var(--wc-title);
  letter-spacing: 0.5px;
  line-height: 1.15;
}
.wc-kpi-unit {
  font-size: calc(13px * var(--wc-scale, 1));
  font-weight: 600;
  color: var(--wc-meta);
}
/* 大屏/公开分享：卡片入场淡入+上浮，按 --enter-d 错峰（编辑态不加 enter-anim，避免拖拽闪烁） */
@keyframes wcEnter {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.widget-card.enter-anim {
  animation: wcEnter 0.55s cubic-bezier(0.22, 0.61, 0.36, 1) both;
  animation-delay: calc(var(--enter-d, 0) * 1ms);
}

/* ---------- L1 科技装饰（主题令牌 deco 驱动，编辑态不渲染） ---------- */
.widget-card.is-glow {
  box-shadow: 0 0 18px -8px var(--wc-accent), inset 0 0 0 1px var(--wc-border);
}
/* 隐藏卡片外边框（大屏「显示指标边框」=关）：用透明边框保持盒尺寸不变，避免布局抖动 */
.widget-card.no-border {
  border-color: transparent;
}
/* 无边框时一并去掉发光主题的 inset 描边（那也是一圈 1px 边框），只保留外发光 */
.widget-card.no-border.is-glow {
  box-shadow: 0 0 18px -8px var(--wc-accent);
}
/* 无边框时：卡片标题分隔线本质是「边框横杠」，一并隐藏，避免残留一条横线 */
.widget-card.no-border .wc-head {
  border-bottom-color: transparent;
}
/* 无边框时：四角科技角标（L1 deco）本质是「边框」装饰，一并隐藏；扫描光带/外发光属动效保留 */
.widget-card.no-border .wc-corner {
  display: none;
}
.wc-deco {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 2;
  overflow: hidden;
  border-radius: inherit;
}
/* 四角科技角标：每个只画相邻两边，形成直角括号 */
.wc-corner {
  position: absolute;
  width: 9px;
  height: 9px;
  border: 1.5px solid var(--wc-accent);
  opacity: 0.72;
}
.wc-tl {
  top: 1px;
  left: 1px;
  border-right: none;
  border-bottom: none;
}
.wc-tr {
  top: 1px;
  right: 1px;
  border-left: none;
  border-bottom: none;
}
.wc-bl {
  bottom: 1px;
  left: 1px;
  border-right: none;
  border-top: none;
}
.wc-br {
  bottom: 1px;
  right: 1px;
  border-left: none;
  border-top: none;
}
/* 顶部扫描光带：一道高光横向循环划过卡片 */
.wc-scan {
  position: absolute;
  top: 0;
  left: 0;
  width: 38%;
  height: 1.5px;
  background: linear-gradient(90deg, transparent, var(--wc-accent), transparent);
  animation: wcScan 3.6s ease-in-out infinite;
}
@keyframes wcScan {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(320%);
  }
}
@media (prefers-reduced-motion: reduce) {
  .wc-scan {
    animation: none;
  }
}
/* ---------- 刷新中可见反馈（大屏自动刷新 / 手动刷新触发） ---------- */
.wc-refresh {
  position: absolute;
  inset: 0;
  z-index: 3;
  pointer-events: none;
  overflow: hidden;
  border-radius: inherit;
  border: 1px solid var(--wc-accent);
  animation: wcRefreshPulse 0.6s ease-out both;
}
.wc-refresh::before {
  content: '';
  position: absolute;
  top: -50%;
  left: -40%;
  width: 42%;
  height: 200%;
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--wc-accent) 55%, transparent),
    transparent
  );
  transform: rotate(18deg);
  animation: wcRefreshSweep 0.7s ease-in-out both;
}
@keyframes wcRefreshSweep {
  from {
    transform: translateX(0) rotate(18deg);
    opacity: 0;
  }
  18% {
    opacity: 1;
  }
  to {
    transform: translateX(360%) rotate(18deg);
    opacity: 0;
  }
}
@keyframes wcRefreshPulse {
  from {
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--wc-accent) 60%, transparent);
  }
  to {
    box-shadow: 0 0 0 7px transparent;
  }
}
.wc-list {
  height: 100%;
}
@media (prefers-reduced-motion: reduce) {
  .wc-refresh,
  .wc-refresh::before {
    animation: none;
  }
}
</style>
