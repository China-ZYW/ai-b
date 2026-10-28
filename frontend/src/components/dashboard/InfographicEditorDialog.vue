<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import InfographicWidget from './InfographicWidget.vue'
import { INFO_TEMPLATE_CATALOG, INFO_DEFAULT_TEMPLATE } from './infographicTemplates'
import { metricApi } from '@/api/metric'
import { applyEnumDicts } from '@/utils/format'
import type { InfographicGroup, InfographicItem, InfographicSpec, MetricVO } from '@/types'

/**
 * 信息图编辑器弹窗：编辑一个 widget 的 {@link InfographicSpec}。
 *
 * <p><b>两种数据形态</b>：
 * <ul>
 *   <li>flat（默认）：一组「要点」列表（列表 / 流程 / 金字塔 / 扇形 / 瀑布 / 关系）。</li>
 *   <li>grouped：一组「分组」，每个分组含自己的子要点（对比 / 层级类，如 SWOT、左右对比）。
 *       由所选模板的 {@code dataShape} 决定——目录里标了 grouped 的模板自动切到分组编辑器。</li>
 * </ul>
 *
 * <p><b>逐 item 绑定指标 + 字段绑定（V4.36 选项②）</b>：flat 要点、grouped 子要点、分组根节点均可绑定指标（metricId）。
 * 绑定后通过三个字段名分别取数（取指标结果首行对应列的值）：
 * <ul>
 *   <li>{@code valueField}：取该列首行值（转有限数）填入 value（数值）。</li>
 *   <li>{@code labelField}：取该列首行值填入 label（文案）。</li>
 *   <li>{@code descField}：取该列首行值填入 desc（说明）。</li>
 * </ul>
 * 不再需要手写「取值规则 extract」；extract 仅作为旧数据的向后兼容回落（后端生效，前端 UI 不再暴露）。
 * 未绑定指标的 item 保留手填 value 作为兜底。绑定配置随 spec 持久化；运行时由后端 {@code getData} 解析实时值覆盖
 * item.value/label/desc（原 layout 不动）。编辑器内通过 {@link metricApi#refresh} 做客户端实时预览（仅预览，不入 layout）。
 *
 * <p><b>实时预览</b>：右侧面板复用唯一的渲染封装 {@link InfographicWidget}，随模板 / 主题 / 内容变化即时重渲染，
 * 预览与落库使用同一份数据映射，所见即所得。
 *
 * <p><b>模板来源</b>：下拉与添加入口共用 {@code infographicTemplates} 目录；保留 allow-create 供高级用户手动输入模板名。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 当前信息图配置（null 时按默认新建）。 */
    spec: InfographicSpec | null
    /** 组件标题（与 widget 标题同步）。 */
    title: string
    /** 当前驾驶舱是否深色：用于挑选预览引擎主题（主题名无效时会自动降级）。 */
    dark?: boolean
    /** 当前驾驶舱主色（accent）：预览时注入引擎作为主色，使预览与实际渲染一致。 */
    accent?: string | null
    /** 新建时自动填充示例内容，便于立即看到模板效果（仅从画廊进入的新建流程传 true）。 */
    autoSample?: boolean
    /** 可绑定指标列表（来自编辑器已加载的 metrics）；缺省空数组（绑定 UI 仍显示，但无可选项）。 */
    metrics?: MetricVO[]
  }>(),
  { dark: false, accent: null, autoSample: false, metrics: () => [] }
)
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm', payload: { title: string; spec: InfographicSpec }): void
}>()

/** 引擎主题选项：空=跟随驾驶舱明暗。 */
const THEME_OPTS = [
  { value: '', label: '跟随驾驶舱' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' }
]

/** 下拉选项来自目录（与添加入口一致）。 */
const TEMPLATE_OPTS = INFO_TEMPLATE_CATALOG.map((t) => ({ value: t.template, label: t.name }))

/** 模板名 → 目录项，用于判断数据形态。 */
const CATALOG_MAP = new Map(INFO_TEMPLATE_CATALOG.map((t) => [t.template, t]))

const draftTitle = ref('')
const draftTemplate = ref(INFO_DEFAULT_TEMPLATE)
const draftTheme = ref('')
const draftItems = ref<InfographicItem[]>([])
const draftGroups = ref<InfographicGroup[]>([])

/** 当前模板是否为分组形态。 */
const isGrouped = computed(() => CATALOG_MAP.get(draftTemplate.value.trim())?.dataShape === 'grouped')

function cloneSpec(s: InfographicSpec | null): void {
  draftTemplate.value = s?.template || INFO_DEFAULT_TEMPLATE
  draftTheme.value = s?.theme ?? '' ? (s?.theme as string) : ''
  draftItems.value = (s?.items || []).map((it) => ({
    label: it.label,
    desc: it.desc ?? null,
    value: it.value ?? null,
    metricId: it.metricId ?? null,
    extract: it.extract ?? null,
    valueField: it.valueField ?? null,
    labelField: it.labelField ?? null,
    descField: it.descField ?? null
  }))
  draftGroups.value = (s?.groups || []).map((g) => ({
    label: g.label,
    desc: g.desc ?? null,
    value: g.value ?? null,
    metricId: g.metricId ?? null,
    extract: g.extract ?? null,
    valueField: g.valueField ?? null,
    labelField: g.labelField ?? null,
    descField: g.descField ?? null,
    children: (g.children || []).map((it) => ({
      label: it.label,
      desc: it.desc ?? null,
      value: it.value ?? null,
      metricId: it.metricId ?? null,
      extract: it.extract ?? null,
      valueField: it.valueField ?? null,
      labelField: it.labelField ?? null,
      descField: it.descField ?? null
    }))
  }))
  if (isGrouped.value && draftGroups.value.length === 0) {
    draftGroups.value = defaultGroups(draftTemplate.value)
  }
}

/** 分组模板的空骨架：SWOT 预置 S/W/O/T 四分组，其余预置两个空分组。 */
function defaultGroups(tpl: string): InfographicGroup[] {
  const empty = (label: string): InfographicGroup => ({
    label,
    desc: null,
    value: null,
    metricId: null,
    extract: null,
    valueField: null,
    labelField: null,
    descField: null,
    children: []
  })
  if (tpl === 'compare-swot') return ['S', 'W', 'O', 'T'].map(empty)
  return [empty(''), empty('')]
}

/** 示例内容，一键填充。 */
function sampleGroups(tpl: string): InfographicGroup[] {
  const c = (label: string): InfographicItem => ({
    label,
    desc: null,
    value: null,
    metricId: null,
    extract: null,
    valueField: null,
    labelField: null,
    descField: null
  })
  if (tpl === 'compare-swot') {
    return [
      { label: 'S', desc: '优势', value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null, children: [c('品牌认知度高'), c('技术壁垒')] },
      { label: 'W', desc: '劣势', value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null, children: [c('渠道覆盖不足')] },
      { label: 'O', desc: '机会', value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null, children: [c('下沉市场增长')] },
      { label: 'T', desc: '威胁', value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null, children: [c('竞争加剧')] }
    ]
  }
  return [
    { label: '方案 A', value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null, children: [c('成本更低'), c('交付更快')] },
    { label: '方案 B', value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null, children: [c('功能更全'), c('生态更完善')] }
  ]
}
function fillSample(): void {
  if (isGrouped.value) {
    draftGroups.value = sampleGroups(draftTemplate.value)
  } else {
    draftItems.value = [
      { label: '新增用户', desc: '本月累计', value: 1280, metricId: null, extract: null, valueField: null, labelField: null, descField: null },
      { label: '成交金额', desc: 'GMV（万）', value: 36, metricId: null, extract: null, valueField: null, labelField: null, descField: null },
      { label: '活跃率', desc: '环比', value: 12, metricId: null, extract: null, valueField: null, labelField: null, descField: null }
    ]
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      draftTitle.value = props.title || ''
      cloneSpec(props.spec)
      // 新建流程自动灌示例，避免"打开是空的、看不到效果"
      if (props.autoSample) {
        const blank = isGrouped.value
          ? !draftGroups.value.some((g) => (g.label || '').trim() || g.children.length)
          : draftItems.value.length === 0
        if (blank) fillSample()
      }
    }
  },
  { immediate: true }
)

/* ---------------- 实时预览：绑定指标时客户端拉取实时值（仅预览，不入 layout） ---------------- */
type LiveResult = { rows: unknown[][]; columns: string[]; rowCount: number } | null
/** 绑定指标的实时查询结果（按 metricId 缓存）：用于预览取数与字段下拉选项。 */
const liveResults = ref<Record<number, LiveResult>>({})
/** 每个绑定点的预览解析结果（value / label / desc）。 */
const live = ref<Record<string, { value?: number | string | null; label?: string | null; desc?: string | null }>>({})

/** 取某指标列名列表（供字段下拉）。 */
function colsOf(metricId: number | null | undefined): string[] {
  if (!metricId) return []
  const r = liveResults.value[metricId]
  return r && r.columns ? r.columns : []
}

/** 绑定签名（metricId + 三字段），变化即重新拉取预览。 */
const bindSig = computed(() =>
  JSON.stringify([
    draftItems.value.map((i) => [i.metricId, i.valueField, i.labelField, i.descField]),
    draftGroups.value.map((g) => [
      g.metricId,
      g.valueField,
      g.labelField,
      g.descField,
      g.children.map((c) => [c.metricId, c.valueField, c.labelField, c.descField])
    ])
  ])
)
watch(bindSig, resolveLive, { immediate: true })

async function resolveLive(): Promise<void> {
  // 1) 收集需要拉取的指标 id（已缓存的跳过，避免重复请求）
  const ids = new Set<number>()
  draftItems.value.forEach((i) => i.metricId && ids.add(i.metricId))
  draftGroups.value.forEach((g) => {
    g.metricId && ids.add(g.metricId)
    g.children.forEach((c) => c.metricId && ids.add(c.metricId))
  })
  const cached = liveResults.value
  const toFetch = [...ids].filter((id) => !(id in cached))
  const fetched: Record<number, LiveResult> = {}
  await Promise.all(
    toFetch.map(async (id) => {
      try {
        const mv = await metricApi.refresh(id)
        const res = (mv.result || null) as { rows?: unknown[][]; columns?: string[]; rowCount?: number; enumDicts?: Record<string, Record<string, string>> | null } | null
        // 预览行：把语义列「枚举字典」配好的码值换成中文（status=1 → 已支付），与正式卡片（WidgetCard）显示一致。
        fetched[id] = res && res.rows ? { rows: applyEnumDicts(res), columns: res.columns || [], rowCount: res.rowCount || res.rows.length } : null
      } catch {
        fetched[id] = null
      }
    })
  )
  const merged: Record<number, LiveResult> = {}
  ids.forEach((id) => {
    merged[id] = id in fetched ? fetched[id] : cached[id] ?? null
  })
  liveResults.value = merged

  // 1.5) 绑定了指标但还没选「数值字段」的绑定点：自动带出首个可转有限数的列。
  // 与后端 firstCell 兜底同口径——让"绑定即可见值"，用户想换列仍可手动改；解析不到数值列则不动。
  const autoPickValueField = (
    row: { metricId?: number | null; valueField?: string | null; extract?: string | null }
  ): void => {
    if (!row.metricId || row.valueField || row.extract) return
    const r = merged[row.metricId]
    if (!r || !r.columns || !r.rows || !r.rows.length) return
    for (let i = 0; i < r.columns.length; i++) {
      const v = r.rows[0][i]
      const n = v == null || v === '' ? NaN : Number(v)
      if (Number.isFinite(n)) {
        row.valueField = r.columns[i]
        return
      }
    }
  }
  draftItems.value.forEach(autoPickValueField)
  draftGroups.value.forEach((g) => {
    autoPickValueField(g)
    g.children.forEach(autoPickValueField)
  })

  // 2) 解析每个绑定点的 value / label / desc
  const firstCell = (
    r: { rows?: unknown[][]; columns?: string[]; rowCount?: number } | null | undefined,
    name?: string | null
  ): unknown => {
    if (!r || !r.columns || !r.rows || !r.rows.length || !name) return null
    const idx = r.columns.indexOf(name)
    return idx >= 0 ? r.rows[0][idx] : null
  }
  const num = (o: unknown): number | null =>
    o == null ? null : typeof o === 'number' ? o : Number.isFinite(Number(o)) ? Number(o) : null
  /** 未选字段且无 extract 时的预览兜底 = 后端 firstCell 同口径（首行首列转有限数），保证预览与大屏一致。 */
  const fallbackCell = (r: LiveResult): number | null =>
    r && r.rows && r.rows.length ? num(r.rows[0][0]) : null

  const next: Record<string, { value?: number | string | null; label?: string | null; desc?: string | null }> = {}
  draftItems.value.forEach((it, i) => {
    if (!it.metricId) return
    const r = merged[it.metricId]
    const value = it.valueField ? firstCell(r, it.valueField) : it.extract ? extractClient(r, it.extract) : fallbackCell(r)
    const label = it.labelField ? firstCell(r, it.labelField) : null
    const desc = it.descField ? firstCell(r, it.descField) : null
    next['i' + i] = {
      value: value == null ? null : num(value),
      label: label == null ? null : String(label),
      desc: desc == null ? null : String(desc)
    }
  })
  draftGroups.value.forEach((g, gi) => {
    if (g.metricId) {
      const r = merged[g.metricId]
      const value = g.valueField ? firstCell(r, g.valueField) : g.extract ? extractClient(r, g.extract) : fallbackCell(r)
      next['g' + gi] = { value: value == null ? null : num(value) }
    }
    g.children.forEach((c, ci) => {
      if (!c.metricId) return
      const r = merged[c.metricId]
      const value = c.valueField ? firstCell(r, c.valueField) : c.extract ? extractClient(r, c.extract) : fallbackCell(r)
      const label = c.labelField ? firstCell(r, c.labelField) : null
      const desc = c.descField ? firstCell(r, c.descField) : null
      next['c' + gi + '_' + ci] = {
        value: value == null ? null : num(value),
        label: label == null ? null : String(label),
        desc: desc == null ? null : String(desc)
      }
    })
  })
  live.value = next
}

/** 客户端预览版取值（与后端 extractInfographicValue 同规则，仅供预览回落）。 */
function extractClient(
  r: { rows?: unknown[][]; columns?: string[]; rowCount?: number } | null | undefined,
  extract: string | null | undefined
): number | null {
  if (!r || !r.rows || r.rows.length === 0) return null
  const rule = extract || 'firstCell'
  const cols: string[] = r.columns || []
  const num = (o: unknown): number | null =>
    o == null ? null : typeof o === 'number' ? o : Number.isFinite(Number(o)) ? Number(o) : null
  if (rule.startsWith('column:')) {
    const idx = cols.indexOf(rule.slice('column:'.length))
    return idx >= 0 ? num(r.rows[0][idx]) : null
  }
  if (rule === 'scalar') return r.rowCount === 1 && cols.length === 1 ? num(r.rows[0][0]) : null
  if (rule === 'rowSum' || rule.startsWith('rowSum:')) {
    let idx = 0
    if (rule.includes(':')) {
      const col = rule.slice(rule.indexOf(':') + 1)
      idx = cols.indexOf(col)
    }
    if (idx < 0) idx = 0
    let s = 0
    for (const row of r.rows) {
      const n = num(row[idx])
      if (n != null) s += n
    }
    return s
  }
  return num(r.rows[0][0])
}

/** 组装落库 spec（flat / grouped 各自映射）。持久化只存「手填兜底 + 绑定配置（metricId / extract / 三字段）」，实时值由后端运行时解析覆盖。 */
function buildSpec(): InfographicSpec {
  const template = draftTemplate.value.trim() || INFO_DEFAULT_TEMPLATE
  if (isGrouped.value) {
    return {
      template,
      theme: draftTheme.value || null,
      mode: 'grouped',
      items: [],
      groups: draftGroups.value
        .filter((g) => (g.label || '').trim().length > 0)
        .map((g) => ({
          label: g.label.trim(),
          desc: g.desc && g.desc.trim() ? g.desc.trim() : null,
          value: g.value ?? null,
          metricId: g.metricId ?? null,
          extract: g.extract ?? null,
          valueField: g.valueField ?? null,
          labelField: g.labelField ?? null,
          descField: g.descField ?? null,
          children: g.children
            .filter((it) => (it.label || '').trim().length > 0)
            .map((it) => ({
              label: it.label.trim(),
              desc: it.desc && it.desc.trim() ? it.desc.trim() : null,
              value: it.value ?? null,
              metricId: it.metricId ?? null,
              extract: it.extract ?? null,
              valueField: it.valueField ?? null,
              labelField: it.labelField ?? null,
              descField: it.descField ?? null
            }))
        }))
    }
  }
  return {
    template,
    theme: draftTheme.value || null,
    mode: 'flat',
    items: draftItems.value
      .filter((it) => (it.label || '').trim().length > 0)
      .map((it) => ({
        label: it.label.trim(),
        desc: it.desc && it.desc.trim() ? it.desc.trim() : null,
        value: it.value ?? null,
        metricId: it.metricId ?? null,
        extract: it.extract ?? null,
        valueField: it.valueField ?? null,
        labelField: it.labelField ?? null,
        descField: it.descField ?? null
      })),
    groups: null
  }
}

/** 预览用 spec：在落库 spec 基础上叠加客户端实时值/文案/说明（仅预览，确认时仍落库手填兜底）。 */
const previewSpec = computed<InfographicSpec>(() => {
  const base = buildSpec()
  if (base.mode === 'grouped') {
    base.groups = (base.groups || []).map((g, gi) => {
      const lv = live.value['g' + gi]
      return {
        ...g,
        value: lv?.value ?? g.value ?? null,
        children: g.children.map((c, ci) => {
          const lvc = live.value['c' + gi + '_' + ci]
          return {
            ...c,
            value: lvc?.value ?? c.value ?? null,
            label: lvc?.label ?? c.label,
            desc: lvc?.desc ?? c.desc ?? null
          }
        })
      }
    })
  } else {
    base.items = base.items.map((it, i) => {
      const lv = live.value['i' + i]
      return {
        ...it,
        value: lv?.value ?? it.value ?? null,
        label: lv?.label ?? it.label,
        desc: lv?.desc ?? it.desc ?? null
      }
    })
  }
  return base
})

/* ---------------- flat：要点 ---------------- */
function addItem(): void {
  draftItems.value.push({ label: '', desc: null, value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null })
}
function removeItem(idx: number): void {
  draftItems.value.splice(idx, 1)
}
function moveItem(idx: number, dir: -1 | 1): void {
  const j = idx + dir
  const arr = draftItems.value
  if (j < 0 || j >= arr.length) return
  ;[arr[idx], arr[j]] = [arr[j], arr[idx]]
}

/* ---------------- grouped：分组 + 组内要点 ---------------- */
function addGroup(): void {
  draftGroups.value.push({ label: '', desc: null, value: null, children: [], metricId: null, extract: null, valueField: null, labelField: null, descField: null })
}
function removeGroup(gi: number): void {
  draftGroups.value.splice(gi, 1)
}
function moveGroup(gi: number, dir: -1 | 1): void {
  const j = gi + dir
  const arr = draftGroups.value
  if (j < 0 || j >= arr.length) return
  ;[arr[gi], arr[j]] = [arr[j], arr[gi]]
}
function addChild(gi: number): void {
  draftGroups.value[gi].children.push({ label: '', desc: null, value: null, metricId: null, extract: null, valueField: null, labelField: null, descField: null })
}
function removeChild(gi: number, ci: number): void {
  draftGroups.value[gi].children.splice(ci, 1)
}
function moveChild(gi: number, ci: number, dir: -1 | 1): void {
  const arr = draftGroups.value[gi].children
  const j = ci + dir
  if (j < 0 || j >= arr.length) return
  ;[arr[ci], arr[j]] = [arr[j], arr[ci]]
}

function onConfirm(): void {
  emit('confirm', { title: draftTitle.value.trim(), spec: buildSpec() })
  emit('update:modelValue', false)
}
function onCancel(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="编辑信息图"
    width="960px"
    append-to-body
    @update:model-value="onCancel"
  >
    <div class="info-editor">
      <!-- 左：表单 -->
      <div class="ie-form">
        <div class="ie-row">
          <label class="ie-label">组件标题</label>
          <el-input v-model="draftTitle" size="small" placeholder="信息图" clearable />
        </div>

        <div class="ie-row">
          <label class="ie-label">模板</label>
          <el-select
            v-model="draftTemplate"
            size="small"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入模板名"
            class="ie-full"
          >
            <el-option v-for="t in TEMPLATE_OPTS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </div>

        <div class="ie-row">
          <label class="ie-label">引擎主题</label>
          <el-select v-model="draftTheme" size="small" class="ie-full">
            <el-option v-for="t in THEME_OPTS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </div>

        <!-- grouped：分组编辑 -->
        <div v-if="isGrouped" class="ie-row ie-col">
          <div class="ie-items-head">
            <label class="ie-label">分组内容</label>
            <div class="ie-items-ops">
              <el-button size="small" type="primary" plain @click="fillSample">填充示例</el-button>
              <el-button size="small" type="primary" plain @click="addGroup">
                <el-icon><Plus /></el-icon> 添加分组
              </el-button>
            </div>
          </div>
          <p class="ie-hint muted">每个分组是一根节点（如 S/W/O/T、方案 A），组内再填要点。分组根节点与组内要点均可绑定指标取实时值。</p>

          <div v-for="(g, gi) in draftGroups" :key="gi" class="ie-group">
            <div class="ie-group-head">
              <span class="ie-idx">分组 #{{ gi + 1 }}</span>
              <div class="ie-item-ops">
                <el-button size="small" text :disabled="gi === 0" @click="moveGroup(gi, -1)">↑</el-button>
                <el-button size="small" text :disabled="gi === draftGroups.length - 1" @click="moveGroup(gi, 1)">↓</el-button>
                <el-button size="small" text type="danger" @click="removeGroup(gi)">删除分组</el-button>
              </div>
            </div>
            <div class="ie-item-2">
              <el-input v-model="g.label" size="small" placeholder="分组主文案（必填，如 S / 方案 A）" class="ie-full" />
              <el-input v-model="g.desc" size="small" placeholder="分组说明（可选）" class="ie-full" />
            </div>
            <div class="ie-group-bind">
              <el-select v-model="g.metricId" size="small" filterable clearable placeholder="绑定指标（可选）" class="ie-bind-metric">
                <el-option v-for="m in metrics" :key="m.id" :label="m.name" :value="m.id" />
              </el-select>
              <template v-if="g.metricId">
                <el-select v-model="g.valueField" size="small" filterable clearable placeholder="数值字段" class="ie-bind-field">
                  <el-option v-for="col in colsOf(g.metricId)" :key="col" :label="col" :value="col" />
                </el-select>
                <el-select v-model="g.labelField" size="small" filterable clearable placeholder="文案字段" class="ie-bind-field">
                  <el-option v-for="col in colsOf(g.metricId)" :key="col" :label="col" :value="col" />
                </el-select>
                <el-select v-model="g.descField" size="small" filterable clearable placeholder="说明字段" class="ie-bind-field">
                  <el-option v-for="col in colsOf(g.metricId)" :key="col" :label="col" :value="col" />
                </el-select>
              </template>
            </div>

            <div class="ie-children">
              <div v-for="(c, ci) in g.children" :key="ci" class="ie-child">
                <el-input v-model="c.label" size="small" placeholder="要点（必填）" class="ie-full" />
                <el-input v-model="c.desc" size="small" placeholder="补充（可选）" class="ie-child-desc" />
                <el-button size="small" text :disabled="ci === 0" @click="moveChild(gi, ci, -1)">↑</el-button>
                <el-button size="small" text :disabled="ci === g.children.length - 1" @click="moveChild(gi, ci, 1)">↓</el-button>
                <el-button size="small" text type="danger" @click="removeChild(gi, ci)">删</el-button>
                <div class="ie-child-bind">
                  <el-select v-model="c.metricId" size="small" filterable clearable placeholder="绑定指标" class="ie-bind-metric">
                    <el-option v-for="m in metrics" :key="m.id" :label="m.name" :value="m.id" />
                  </el-select>
                  <template v-if="c.metricId">
                    <el-select v-model="c.valueField" size="small" filterable clearable placeholder="数值" class="ie-bind-field">
                      <el-option v-for="col in colsOf(c.metricId)" :key="col" :label="col" :value="col" />
                    </el-select>
                    <el-select v-model="c.labelField" size="small" filterable clearable placeholder="文案" class="ie-bind-field">
                      <el-option v-for="col in colsOf(c.metricId)" :key="col" :label="col" :value="col" />
                    </el-select>
                    <el-select v-model="c.descField" size="small" filterable clearable placeholder="说明" class="ie-bind-field">
                      <el-option v-for="col in colsOf(c.metricId)" :key="col" :label="col" :value="col" />
                    </el-select>
                  </template>
                </div>
              </div>
              <el-button size="small" text type="primary" @click="addChild(gi)">
                <el-icon><Plus /></el-icon> 添加要点
              </el-button>
            </div>
          </div>

          <el-empty v-if="draftGroups.length === 0" :image-size="50" description="暂未添加分组" />
        </div>

        <!-- flat：要点编辑 -->
        <div v-else class="ie-row ie-col">
          <div class="ie-items-head">
            <label class="ie-label">要点内容</label>
            <div class="ie-items-ops">
              <el-button size="small" type="primary" plain @click="fillSample">填充示例</el-button>
              <el-button size="small" type="primary" plain @click="addItem">
                <el-icon><Plus /></el-icon> 添加要点
              </el-button>
            </div>
          </div>
          <p class="ie-hint muted">label 为必填主文案，desc 为补充说明，value 为可选数值。每个要点可绑定指标，并指定数值/文案/说明字段（取实时值，手填值作兜底）。</p>

          <div v-for="(it, idx) in draftItems" :key="idx" class="ie-item">
            <div class="ie-item-head">
              <span class="ie-idx">#{{ idx + 1 }}</span>
              <div class="ie-item-ops">
                <el-button size="small" text :disabled="idx === 0" @click="moveItem(idx, -1)">↑</el-button>
                <el-button size="small" text :disabled="idx === draftItems.length - 1" @click="moveItem(idx, 1)">↓</el-button>
                <el-button size="small" text type="danger" @click="removeItem(idx)">删除</el-button>
              </div>
            </div>
            <el-input v-model="it.label" size="small" placeholder="主文案（必填）" class="ie-full" />
            <div class="ie-item-2">
              <el-input v-model="it.desc" size="small" placeholder="补充说明（可选）" class="ie-full" />
              <el-input v-model="it.value" size="small" placeholder="数值（手填兜底）" class="ie-full" />
            </div>
            <div class="ie-item-3 ie-bind">
              <el-select v-model="it.metricId" size="small" filterable clearable placeholder="绑定指标（可选）" class="ie-bind-metric">
                <el-option v-for="m in metrics" :key="m.id" :label="m.name" :value="m.id" />
              </el-select>
              <template v-if="it.metricId">
                <el-select v-model="it.valueField" size="small" filterable clearable placeholder="数值字段" class="ie-bind-field">
                  <el-option v-for="col in colsOf(it.metricId)" :key="col" :label="col" :value="col" />
                </el-select>
                <el-select v-model="it.labelField" size="small" filterable clearable placeholder="文案字段" class="ie-bind-field">
                  <el-option v-for="col in colsOf(it.metricId)" :key="col" :label="col" :value="col" />
                </el-select>
                <el-select v-model="it.descField" size="small" filterable clearable placeholder="说明字段" class="ie-bind-field">
                  <el-option v-for="col in colsOf(it.metricId)" :key="col" :label="col" :value="col" />
                </el-select>
              </template>
            </div>
          </div>

          <el-empty v-if="draftItems.length === 0" :image-size="50" description="暂未添加要点" />
        </div>
      </div>

      <!-- 右：实时预览 -->
      <div class="ie-preview">
        <div class="ie-preview-head">
          <span class="ie-label">实时预览</span>
          <span class="muted ie-preview-sub">切换模板 / 增删内容即时生效（绑定指标显示实时值）</span>
        </div>
        <div class="ie-preview-body">
          <InfographicWidget :spec="previewSpec" :dark="dark ?? false" :accent="accent ?? null" />
        </div>
      </div>
    </div>

    <template #footer>
      <el-button size="small" @click="onCancel">取消</el-button>
      <el-button size="small" type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.info-editor {
  display: flex;
  gap: 18px;
  align-items: stretch;
}
.ie-form {
  flex: 1 1 50%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 4px;
}
.ie-preview {
  flex: 1 1 50%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: 18px;
}
.ie-preview-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.ie-preview-sub {
  font-size: 12px;
}
.ie-preview-body {
  flex: 1;
  min-height: 320px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-light);
  overflow: hidden;
  padding: 8px;
}
.ie-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ie-col {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}
.ie-label {
  width: 72px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.ie-full {
  flex: 1;
  min-width: 0;
}
.ie-items-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ie-items-ops {
  display: flex;
  gap: 6px;
}
.ie-hint {
  margin: 0;
  font-size: 12px;
}
.ie-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--el-fill-color-blank);
}
.ie-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ie-idx {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 600;
}
.ie-item-ops {
  display: flex;
  gap: 2px;
}
.ie-item-2 {
  display: flex;
  gap: 8px;
}
.ie-item-3 {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.ie-bind {
  align-items: center;
}
.ie-bind-metric {
  flex: 1 1 140px;
  min-width: 0;
}
.ie-bind-field {
  flex: 1 1 130px;
  min-width: 0;
}
.ie-group {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--el-fill-color-light);
}
.ie-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ie-group-bind {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.ie-children {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-left: 8px;
  border-left: 2px solid var(--el-border-color-lighter);
}
.ie-child {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.ie-child-desc {
  width: 130px;
  flex-shrink: 0;
}
.ie-child-bind {
  display: flex;
  gap: 6px;
  width: 100%;
  margin-top: 2px;
  flex-wrap: wrap;
}
</style>
