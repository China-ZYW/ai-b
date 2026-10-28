<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, watch } from 'vue'
import { useConfigStore } from '@/stores/config'
import type { ChartStyle } from '@/types'
import { pruneChartStyle, resolveChartStyle, sameChartStyle } from '@/utils/chartStyle'

/**
 * 图表标注表单：轴名 / 单位 / 数值格式 / 小数位 / 图例位置 / 标签旋转。
 *
 * <p>问数结果面板（会话级覆盖）与驾驶舱编辑器（widget 级覆盖）共用——两者只是“覆盖存到哪”不同
 * （前者随存指标/分享写进 {@code chart_spec.style}，后者写进大屏 {@code layout.chartStyle}），
 * 字段与优先级口径完全一致，故收敛到一处，避免两套表单各自漂移。
 *
 * <p>空串 / null 一律表示“跟随默认”（{@code chart_spec} 自带 → 租户级 → 代码默认），由
 * {@link pruneChartStyle} 收敛掉，不会把一堆空值写进 JSON 白吃列宽。草稿改动去抖后上抛；
 * 与入参等价时不 emit，阻断“父级回填草稿 → 草稿再上抛 → 父级又回填”的回环。
 */
const props = withDefaults(
  defineProps<{
    /** 当前覆盖值（null 或空对象表示未自定义）。 */
    modelValue?: ChartStyle | null
    /** 图表类型：决定轴名占位提示（条形图两轴互换）与“标签旋转”是否可用。 */
    chartType?: string | null
    /** chart_spec 自带样式（后端推断的单位或指标已存设置）：仅用于“当前生效”提示。 */
    specStyle?: ChartStyle | null
    /** X / Y 轴名占位提示，留空则用通用文案。 */
    xPlaceholder?: string
    yPlaceholder?: string
  }>(),
  {
    modelValue: null,
    chartType: null,
    specStyle: null,
    xPlaceholder: '',
    yPlaceholder: ''
  }
)

const emit = defineEmits<{
  /** 上抛覆盖值（null 表示清除、回落 chart_spec 自带样式与租户级默认）。 */
  (e: 'update:modelValue', style: ChartStyle | null): void
}>()

const config = useConfigStore()

/** 表单草稿：一律用可空的基础类型承接输入控件，提交前再剪枝成 ChartStyle。 */
const draft = reactive<{
  xAxisName: string
  yAxisName: string
  valueUnit: string
  valueFormat: string
  valueDecimals: number | null | undefined
  legendPosition: string
  xLabelRotate: number | null | undefined
}>({
  xAxisName: '',
  yAxisName: '',
  valueUnit: '',
  valueFormat: '',
  valueDecimals: null,
  legendPosition: '',
  xLabelRotate: null
})

function fill(v?: ChartStyle | null): void {
  draft.xAxisName = v?.xAxisName ?? ''
  draft.yAxisName = v?.yAxisName ?? ''
  draft.valueUnit = v?.valueUnit ?? ''
  draft.valueFormat = v?.valueFormat ?? ''
  draft.valueDecimals = v?.valueDecimals ?? null
  draft.legendPosition = v?.legendPosition ?? ''
  draft.xLabelRotate = v?.xLabelRotate ?? null
}
fill(props.modelValue)

// 换了一条结果 / 换了一个 widget，或父级清空了覆盖时回填草稿
watch(() => props.modelValue, (v) => fill(v), { deep: true })

// 去抖上抛：每敲一个字就重绘图表太浪费
let timer: ReturnType<typeof setTimeout> | null = null
watch(
  draft,
  () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(apply, 250)
  },
  { deep: true }
)
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})

function apply(): void {
  const next = pruneChartStyle(draft)
  if (sameChartStyle(next, props.modelValue)) return
  emit('update:modelValue', next)
}

/** 清空覆盖，回落 chart_spec 自带样式（含后端自动推断的单位）与租户级默认。 */
function reset(): void {
  if (timer) clearTimeout(timer)
  fill(null)
  if (!pruneChartStyle(props.modelValue)) return
  emit('update:modelValue', null)
}

/** 是否已自定义（决定“恢复默认”是否可点与标题标记）。 */
const hasOverride = computed(() => !!pruneChartStyle(props.modelValue))

const FORMAT_LABELS: Record<string, string> = { COMPACT: '万亿紧凑', THOUSAND: '千分位', PLAIN: '原值' }
const LEGEND_LABELS: Record<string, string> = {
  top: '顶部',
  bottom: '底部',
  left: '左侧',
  right: '右侧',
  hidden: '隐藏'
}

/** 最终生效的样式：仅用于“当前生效”提示，让用户知道“跟随默认”到底跟的是什么。 */
const effectiveSummary = computed(() => {
  const s = resolveChartStyle(props.specStyle, props.modelValue, config.chartDefaults)
  return [
    s.valueUnit ? `单位 ${s.valueUnit}` : '无单位',
    FORMAT_LABELS[s.valueFormat] ?? s.valueFormat,
    s.valueDecimals != null ? `${s.valueDecimals} 位小数` : '小数位自动',
    `图例${LEGEND_LABELS[s.legendPosition] ?? s.legendPosition}`
  ].join(' · ')
})

const FORMAT_OPTIONS = [
  { value: 'COMPACT', label: '万亿紧凑（150万）' },
  { value: 'THOUSAND', label: '千分位（1,500,000）' },
  { value: 'PLAIN', label: '原值（1500000）' }
]
const LEGEND_OPTIONS = [
  { value: 'top', label: '顶部' },
  { value: 'bottom', label: '底部' },
  { value: 'left', label: '左侧' },
  { value: 'right', label: '右侧' },
  { value: 'hidden', label: '隐藏' }
]
const DECIMALS_OPTIONS = [0, 1, 2, 3, 4].map((n) => ({ value: n, label: n === 0 ? '整数' : `${n} 位` }))
const ROTATE_OPTIONS = [0, 30, 45, 60, 90].map((n) => ({ value: n, label: `${n}°` }))

/** 条形图两轴互换：占位提示要跟着换，否则用户会填错轴。 */
const isHBar = computed(() => props.chartType === 'HBAR')
const xPh = computed(() => props.xPlaceholder || (isHBar.value ? '度量名' : '维度名'))
const yPh = computed(() => props.yPlaceholder || (isHBar.value ? '维度名' : '度量名'))
/** 标签旋转只对“物理 X 轴是类别轴”的图型有意义（饼/漏斗/仪表盘/雷达/地图/条形图无此需求）。 */
const canRotate = computed(
  () => !isHBar.value && ['LINE', 'BAR', 'AREA', 'HEATMAP'].includes(props.chartType ?? '')
)
</script>

<template>
  <div class="cs-form">
    <div class="cs-head">
      <span>图表标注</span>
      <el-tag v-if="hasOverride" size="small" effect="light" type="primary" round>已自定义</el-tag>
    </div>
    <div class="cs-row">
      <span class="cs-label">X 轴名</span>
      <el-input v-model="draft.xAxisName" size="small" maxlength="32" clearable :placeholder="xPh" />
    </div>
    <div class="cs-row">
      <span class="cs-label">Y 轴名</span>
      <el-input v-model="draft.yAxisName" size="small" maxlength="32" clearable :placeholder="yPh" />
    </div>
    <div class="cs-row">
      <span class="cs-label">单位</span>
      <el-input
        v-model="draft.valueUnit"
        size="small"
        maxlength="8"
        clearable
        placeholder="如 元 / 件 / % / 次"
      />
    </div>
    <div class="cs-row">
      <span class="cs-label">数值格式</span>
      <el-select v-model="draft.valueFormat" size="small" clearable placeholder="跟随默认">
        <el-option v-for="o in FORMAT_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
    </div>
    <div class="cs-row">
      <span class="cs-label">小数位</span>
      <el-select v-model="draft.valueDecimals" size="small" clearable placeholder="跟随默认">
        <el-option v-for="o in DECIMALS_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
    </div>
    <div class="cs-row">
      <span class="cs-label">图例</span>
      <el-select v-model="draft.legendPosition" size="small" clearable placeholder="跟随默认">
        <el-option v-for="o in LEGEND_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
    </div>
    <div v-if="canRotate" class="cs-row">
      <span class="cs-label">标签旋转</span>
      <el-select v-model="draft.xLabelRotate" size="small" clearable placeholder="跟随默认">
        <el-option v-for="o in ROTATE_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
    </div>
    <p class="cs-sum">当前生效：{{ effectiveSummary }}</p>
    <div class="cs-foot">
      <span class="cs-tip">只改展示，不影响 SQL 与数据</span>
      <el-button size="small" text type="primary" :disabled="!hasOverride" @click="reset">
        <el-icon><RefreshLeft /></el-icon> 恢复默认
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.cs-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.cs-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cs-label {
  flex: 0 0 60px;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.cs-row .el-input,
.cs-row .el-select {
  flex: 1;
  min-width: 0;
}
.cs-sum {
  margin: 2px 0 0;
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  background: var(--fill-light, rgba(0, 47, 167, 0.05));
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-secondary);
}
.cs-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.cs-tip {
  font-size: 11.5px;
  color: var(--text-placeholder, var(--text-secondary));
}
</style>
