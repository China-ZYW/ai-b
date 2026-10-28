<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { adminDashboardTemplateApi } from '@/api/dashboardTemplate'
import { adminManualApi } from '@/api/manual'
import InfographicEditorDialog from '@/components/dashboard/InfographicEditorDialog.vue'
import InfographicTemplatePicker from '@/components/dashboard/InfographicTemplatePicker.vue'
import { INFO_DEFAULT_TEMPLATE, INFO_TEMPLATE_CATALOG } from '@/components/dashboard/infographicTemplates'
import type {
  DashboardTemplateForm,
  DashboardTemplateVO,
  InfographicSpec,
  ManualVO,
  WidgetLayout
} from '@/types'
import { formatDateTime, formatNumber } from '@/utils/format'
import { useActionGuard } from '@/utils/actionGuard'
import { useConfigStore } from '@/stores/config'

/**
 * 内容运营（平台后台 · 运营组，§9.2）。
 *
 * 聚合平台级内容运营入口：驾驶舱内置模板的全局 CRUD（模板为全局共享资源，无 tenant_id），
 * 以及用户手册发布的快捷入口卡。权限码 admin:content:write（矩阵 YYNN → SUPER_ADMIN / OPERATOR），
 * 与后端 AdminDashboardTemplateController 四端点所要求的权限点完全一致，故本页动作不会 403。
 *
 * <p>主题令牌 / 图表规则未纳入本页：二者是租户级配置（/api/theme、/api/chart-rule，需 tuning:panel:read），
 * 平台账号（tenant_id=0）无对应租户权限点、也无“全局项”平台级管理 API，强行聚合只会造出跳转即 403 的幻影入口。
 *
 * <p><b>数据安全</b>：后端 update 为整行覆盖（含 layout），故编辑对话框必须回填并提交完整 layout，
 * 否则会抹掉骨架；code 在编辑态只读，避免破坏既有驾驶舱通过 templateCode 建立的模板溯源。
 *
 * <p><b>骨架布局可视化（槽位设计器）</b>：旧版只提供一个 JSON textarea，要求运营手敲 {@code kind} /
 * {@code infographic} 嵌套结构才能预置信息图槽位，既易错又难用。现改为「槽位设计器 + 可选高级 JSON」：
 * <ul>
 *   <li>设计器：可视化增删槽位、调 12 列网格坐标、切图表 / 信息图；</li>
 *   <li>信息图槽位：直接调起与驾驶舱同源的「模板画廊 → 信息图编辑器（含实时预览 / 分组数据）」向导；</li>
 *   <li>高级 JSON：保留 textarea，设计器改动即时回写，手改后失焦即解析回填，两侧始终一致。</li>
 * </ul>
 * JSON 仍是落库的唯一事实源——设计器只是它的可视化编辑器，不引入第二套真值。
 */
const router = useRouter()
const config = useConfigStore()

const loading = ref(false)
const list = ref<DashboardTemplateVO[]>([])
const keyword = ref('')

/* ---------------- 手册入口卡状态 ---------------- */
const manual = ref<ManualVO | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await adminDashboardTemplateApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 手册状态尽力而为：失败不阻断本页（同为 YYNN 受众，正常可取）。 */
async function loadManual(): Promise<void> {
  try {
    manual.value = await adminManualApi.get()
  } catch {
    manual.value = null
  }
}

onMounted(() => {
  // 平台默认大屏主题（THEME / default-dashboard-theme）要先到位，否则新建模板对话框拿到的是兜底深色
  void config.load().catch(() => undefined)
  load()
  loadManual()
})

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return list.value
  return list.value.filter((t) =>
    [t.code, t.name, t.category, t.description].some((f) => (f ?? '').toLowerCase().includes(k))
  )
})

/** 顶部统计：模板总数、分类数、槽位合计。 */
const stats = computed(() => {
  const categories = new Set(list.value.map((t) => t.category).filter(Boolean))
  const slots = list.value.reduce((sum, t) => sum + (t.layout?.length ?? 0), 0)
  return { total: list.value.length, categories: categories.size, slots }
})

/* ---------------- 新建 / 编辑对话框 ---------------- */
const dialogVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const isEdit = computed(() => editingId.value != null)

interface TemplateFormState {
  code: string
  name: string
  category: string
  description: string
  icon: string
  theme: string
  layoutJson: string
}
/**
 * 新建内置模板时的默认大屏主题：取平台/租户配置 THEME / default-dashboard-theme，
 * 未配置回落内置深色。与业务端「新建驾驶舱」同源，避免两处各写一个默认。
 */
function defaultTheme(): string {
  return config.defaultDashboardTheme || 'dark'
}

const form = reactive<TemplateFormState>({
  code: '',
  name: '',
  category: '',
  description: '',
  icon: 'DataBoard',
  theme: defaultTheme(),
  layoutJson: '[]'
})
const layoutError = ref('')
/** 高级 JSON 手改后的解析提示（合法则清空）。 */
const jsonParseError = ref('')
/** 高级 JSON 折叠面板展开态。 */
const jsonOpen = ref<string[]>([])

const rules: FormRules = {
  code: [
    { required: true, message: '模板编码不能为空', trigger: 'blur' },
    { pattern: /^[a-z0-9_-]+$/i, message: '仅允许字母 / 数字 / 下划线 / 连字符', trigger: 'blur' }
  ],
  name: [{ required: true, message: '模板名称不能为空', trigger: 'blur' }]
}

const THEME_OPTIONS = [
  { value: 'dark', label: '深色（dark）' },
  { value: 'light', label: '浅色（light）' }
]

/** 实时解析 layoutJson：合法数组返回槽位数，非法返回 null（用于内联预览与告警）。 */
const parsedSlotCount = computed<number | null>(() => {
  try {
    const v = JSON.parse(form.layoutJson || '[]')
    return Array.isArray(v) ? v.length : null
  } catch {
    return null
  }
})

/* ---------------- 骨架槽位设计器 ---------------- */
/** 槽位工作副本：设计器直接读写它，并在变化时回写 layoutJson。 */
const slots = ref<WidgetLayout[]>([])
/** 标记「本次 slots 变更来自 JSON 回填」，避免回填后又被回写覆盖（防格式抖动 / 循环）。 */
let fromJson = false

/** 槽位序号键：w1/w2…（跳过已占用，保证唯一）。 */
function nextKey(): string {
  const used = new Set(slots.value.map((s) => String(s.i || '')))
  let n = slots.value.length + 1
  while (used.has(`w${n}`)) n++
  return `w${n}`
}

/** 追加到现有槽位下方，保证不与既有槽位重叠（12 列网格，默认 6×4）。 */
function bottomY(): number {
  return slots.value.reduce((m, s) => Math.max(m, (Number(s.y) || 0) + (Number(s.h) || 0)), 0)
}

function addSlot(kind: 'chart' | 'infographic' = 'chart'): void {
  const slot: WidgetLayout = {
    i: nextKey(),
    x: 0,
    y: bottomY(),
    w: 6,
    h: 4,
    metricId: null,
    kind
  }
  if (kind === 'infographic') {
    slot.infographic = { template: INFO_DEFAULT_TEMPLATE, theme: null, mode: 'flat', items: [], groups: null }
  }
  slots.value.push(slot)
  if (kind === 'infographic') openInfoEditor(slots.value.length - 1)
}

function removeSlot(idx: number): void {
  slots.value.splice(idx, 1)
}

/** 模板名 → 目录项（取中文展示名）。 */
const CATALOG_MAP = new Map(INFO_TEMPLATE_CATALOG.map((t) => [t.template, t]))

/** 信息图槽位展示名：优先目录中文名，回退引擎模板名。 */
function tplLabel(spec?: InfographicSpec | null): string {
  const t = spec?.template
  if (!t) return '未选择模板'
  return CATALOG_MAP.get(t)?.name || t
}

/** 槽位实际类型（兼容历史数据：缺省即 chart）。 */
function kindOf(s: WidgetLayout): 'chart' | 'infographic' {
  return s.kind === 'infographic' ? 'infographic' : 'chart'
}

/** 切换槽位类型：转信息图时清指标并进入向导；转回图表时丢弃信息图配置。 */
function setKind(s: WidgetLayout, idx: number, kind: 'chart' | 'infographic'): void {
  s.kind = kind
  if (kind === 'infographic') {
    s.metricId = null
    if (!s.infographic || !s.infographic.template) {
      s.infographic = { template: INFO_DEFAULT_TEMPLATE, theme: null, mode: 'flat', items: [], groups: null }
    }
    openInfoEditor(idx)
  } else {
    s.infographic = null
  }
}

/** 设计器 → JSON（唯一回写出口）。 */
function pushSlotsToJson(): void {
  const clean = slots.value.map((s) => {
    const o: Record<string, unknown> = { ...s }
    o.i = String(o.i ?? '').trim()
    o.x = Number(o.x) || 0
    o.y = Number(o.y) || 0
    o.w = Number(o.w) || 4
    o.h = Number(o.h) || 4
    if (o.metricId === '') o.metricId = null
    return o
  })
  form.layoutJson = JSON.stringify(clean, null, 2)
}

/** 解析当前 JSON 为槽位数组；非法返回 null。 */
function parseLayoutJson(): WidgetLayout[] | null {
  try {
    const v = JSON.parse(form.layoutJson || '[]')
    return Array.isArray(v) ? (v as WidgetLayout[]) : null
  } catch {
    return null
  }
}

/** 以「外部来源」写入槽位：置位守卫，跳过随之而来的回写（避免无谓重排 / 覆盖文本）。 */
function setSlotsFromOutside(next: WidgetLayout[]): void {
  fromJson = true
  slots.value = next
  nextTick(() => {
    fromJson = false
  })
}

/** JSON → 设计器（高级模式手改后失焦时调用）。 */
function pullJsonToSlots(): void {
  const parsed = parseLayoutJson()
  if (!parsed) {
    jsonParseError.value = '当前 JSON 不是合法数组，槽位列表未同步'
    return
  }
  jsonParseError.value = ''
  setSlotsFromOutside(parsed)
}

/** 设计器改动 → 回写 JSON；JSON 回填引起的变更跳过，避免覆盖用户正在编辑的文本。 */
watch(
  slots,
  () => {
    if (fromJson) return
    pushSlotsToJson()
  },
  { deep: true }
)

/* ---------------- 信息图向导（画廊 → 编辑器） ---------------- */
const infoPickerVisible = ref(false)
const infoDialogVisible = ref(false)
/** 目标槽位下标；-1 表示新建（此时不预先落槽位，取消即无痕）。 */
const infoTargetIdx = ref(-1)
const infoCreating = ref(false)
const pendingSpec = ref<InfographicSpec | null>(null)
const pendingTitle = ref('')

/** 打开「添加信息图 · 选择模板」画廊（新建流程）。 */
function openInfoPicker(): void {
  infoCreating.value = true
  infoTargetIdx.value = -1
  pendingSpec.value = null
  pendingTitle.value = ''
  infoPickerVisible.value = true
}

/** 画廊选定模板：进入编辑器新建模式（自动灌示例，便于立即看效果）。 */
function onPickTemplate(tpl: string): void {
  pendingSpec.value = { template: tpl, theme: null, items: [] }
  pendingTitle.value = CATALOG_MAP.get(tpl)?.name || '信息图'
  infoCreating.value = true
  infoTargetIdx.value = -1
  infoDialogVisible.value = true
}

/** 编辑指定槽位的信息图（图表槽位切过来也走这里）。 */
function openInfoEditor(idx: number): void {
  const slot = slots.value[idx]
  if (!slot) return
  if (!slot.infographic || !slot.infographic.template) {
    slot.infographic = { template: INFO_DEFAULT_TEMPLATE, theme: null, mode: 'flat', items: [], groups: null }
  }
  infoCreating.value = false
  infoTargetIdx.value = idx
  pendingSpec.value = slot.infographic
  pendingTitle.value = slot.title || ''
  infoDialogVisible.value = true
}

/**
 * 编辑器确认：
 * - 编辑模式：写回目标槽位（含标题）；
 * - 新建模式：此时才追加槽位——取消不残留空白槽位。
 */
function onInfoConfirm(payload: { title: string; spec: InfographicSpec }): void {
  if (infoTargetIdx.value >= 0) {
    const slot = slots.value[infoTargetIdx.value]
    if (slot) {
      slot.kind = 'infographic'
      slot.infographic = payload.spec
      slot.title = payload.title || null
    }
  } else {
    slots.value.push({
      i: nextKey(),
      x: 0,
      y: bottomY(),
      w: 6,
      h: 4,
      metricId: null,
      kind: 'infographic',
      title: payload.title || null,
      infographic: payload.spec
    })
  }
  infoDialogVisible.value = false
  infoCreating.value = false
  infoTargetIdx.value = -1
  pendingSpec.value = null
}

/* ---------------- 表单载入 / 提交 ---------------- */
function resetForm(): void {
  Object.assign(form, {
    code: '',
    name: '',
    category: '',
    description: '',
    icon: 'DataBoard',
    theme: defaultTheme(),
    layoutJson: '[]'
  })
  layoutError.value = ''
  jsonParseError.value = ''
  jsonOpen.value = []
  setSlotsFromOutside([])
}

function openCreate(): void {
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(t: DashboardTemplateVO): void {
  editingId.value = t.id
  // 深拷贝：避免设计器直接改写列表行数据（关闭对话框不保存时不应污染内存中的列表）。
  const layout = JSON.parse(JSON.stringify(t.layout ?? [])) as WidgetLayout[]
  Object.assign(form, {
    code: t.code,
    name: t.name,
    category: t.category ?? '',
    description: t.description ?? '',
    icon: t.icon ?? 'DataBoard',
    theme: t.theme ?? defaultTheme(),
    layoutJson: JSON.stringify(layout, null, 2)
  })
  layoutError.value = ''
  jsonParseError.value = ''
  jsonOpen.value = []
  setSlotsFromOutside(layout)
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  // layout 必须是 JSON 数组；update 整行覆盖，必须携带完整骨架，非法则阻断提交。
  let layout: WidgetLayout[]
  try {
    const parsed = JSON.parse(form.layoutJson || '[]')
    if (!Array.isArray(parsed)) {
      layoutError.value = '骨架布局必须是 JSON 数组（WidgetLayout[]）'
      return
    }
    layout = parsed as WidgetLayout[]
  } catch {
    layoutError.value = '骨架布局不是合法 JSON，请检查括号与引号'
    return
  }
  layoutError.value = ''

  const payload: DashboardTemplateForm = {
    code: form.code.trim(),
    name: form.name.trim(),
    category: form.category.trim() || null,
    description: form.description.trim() || null,
    icon: form.icon.trim() || null,
    theme: form.theme || null,
    layout
  }
  await runSubmit(async () => {
    try {

      if (isEdit.value && editingId.value != null) {
        await adminDashboardTemplateApi.update(editingId.value, payload)
        ElMessage.success('模板已更新')
      } else {
        await adminDashboardTemplateApi.create(payload)
        ElMessage.success('模板已创建')
      }
      dialogVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

async function onDelete(t: DashboardTemplateVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除模板「${t.name}」（${t.code}）？此操作不可撤销；已用该模板创建的驾驶舱不受影响。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminDashboardTemplateApi.remove(t.id)
    ElMessage.success('模板已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 展示辅助 ---------------- */
function slotCount(t: DashboardTemplateVO): number {
  return t.layout?.length ?? 0
}
/** 模板内含的信息图槽位数（列表用于一眼识别含信息图的模板）。 */
function infoSlotCount(t: DashboardTemplateVO): number {
  return (t.layout ?? []).filter((s) => s.kind === 'infographic').length
}
function goManual(): void {
  router.push('/admin/manual')
}
const manualStatusText = computed(() => (manual.value?.status === 'PUBLISHED' ? '已发布' : '草稿'))
</script>

<template>
  <div class="content-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">内容运营</h2>
        <p class="ph-desc">管理全局共享的驾驶舱内置模板，并快捷进入用户手册发布。</p>
      </div>
      <div class="row gap-12">
        <el-input v-model="keyword" placeholder="搜索编码/名称/分类" clearable :prefix-icon="'Search'" style="width: 220px" />
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 新建模板
        </el-button>
      </div>
    </div>

    <!-- 用户手册入口卡（跳转 /admin/manual，同为 admin:content:write/admin:manual:publish 的 YYNN 受众） -->
    <div class="manual-card card">
      <div class="mc-icon">
        <el-icon :size="26"><Document /></el-icon>
      </div>
      <div class="mc-body">
        <div class="mc-title">用户手册发布</div>
        <div class="mc-desc">
          编辑草稿并发布面向全体用户的操作手册。
          <span v-if="manual" class="muted small">
            当前{{ manualStatusText }} · v{{ manual.version ?? 0 }}
            <template v-if="manual.publishedAt">· 发布于 {{ formatDateTime(manual.publishedAt) }}</template>
          </span>
        </div>
      </div>
      <el-button type="primary" plain @click="goManual">
        前往编辑 <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span class="stat-num">{{ formatNumber(stats.total) }}</span>
        <span class="stat-label">模板总数</span>
      </div>
      <div class="stat-card">
        <span class="stat-num">{{ formatNumber(stats.categories) }}</span>
        <span class="stat-label">分类数</span>
      </div>
      <div class="stat-card">
        <span class="stat-num">{{ formatNumber(stats.slots) }}</span>
        <span class="stat-label">指标槽位合计</span>
      </div>
    </div>

    <div class="card table-card">
      <el-table v-loading="loading" :data="filtered" style="width: 100%" row-key="id">
        <el-table-column prop="code" label="编码" width="160" show-overflow-tooltip />
        <el-table-column label="名称" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="tpl-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.category" size="small" effect="plain" round>{{ row.category }}</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="图标" width="70" align="center">
          <template #default="{ row }">
            <el-icon :size="18"><component :is="row.icon || 'DataBoard'" /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="主题" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.theme === 'light' ? 'warning' : 'info'" size="small" effect="light" round>
              {{ row.theme || 'dark' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="槽位" width="120" align="center">
          <template #default="{ row }">
            <span>{{ slotCount(row) }}</span>
            <el-tag
              v-if="infoSlotCount(row)"
              size="small"
              type="success"
              effect="plain"
              round
              class="info-slot-tag"
            >
              信息图 {{ infoSlotCount(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="muted">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEdit(row)">
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button size="small" text type="danger" class="ops-danger" @click="onDelete(row)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无模板" />
        </template>
      </el-table>
    </div>

    <!-- 新建 / 编辑模板 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑模板' : '新建模板'" width="720px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" :disabled="isEdit" placeholder="如 sales-overview（创建后不可改）" />
          <span class="field-hint muted">模板唯一编码；编辑态只读以保护既有驾驶舱的模板溯源。</span>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="模板展示名" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="如 销售 / 财务 / 运营（可空）" />
        </el-form-item>
        <el-form-item label="图标">
          <div class="icon-field">
            <el-input v-model="form.icon" placeholder="Element Plus 图标名，如 DataBoard" />
            <el-icon :size="20" class="icon-preview"><component :is="form.icon || 'DataBoard'" /></el-icon>
          </div>
        </el-form-item>
        <el-form-item label="主题">
          <el-select v-model="form.theme" style="width: 100%">
            <el-option v-for="o in THEME_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="模板用途简述（可空）" />
        </el-form-item>
        <el-form-item label="骨架布局">
          <div class="slot-area">
            <div class="slot-head">
              <span class="muted small">
                每个槽位在 12 列网格中的位置；<code>metricId</code> 留空即占位，实例化后再由用户绑定。
              </span>
              <div class="slot-ops">
                <el-tag size="small" effect="plain" round>{{ slots.length }} 个槽位</el-tag>
                <el-button size="small" type="primary" plain @click="addSlot('chart')">
                  <el-icon><Plus /></el-icon> 图表槽位
                </el-button>
                <el-button size="small" type="success" plain @click="openInfoPicker">
                  <el-icon><Plus /></el-icon> 信息图槽位
                </el-button>
              </div>
            </div>

            <div v-if="slots.length" class="slot-list">
              <div v-for="(s, idx) in slots" :key="idx" class="slot-card">
                <div class="slot-card-head">
                  <span class="slot-idx">#{{ idx + 1 }}</span>
                  <el-tag size="small" :type="kindOf(s) === 'infographic' ? 'success' : 'info'" effect="plain" round>
                    {{ kindOf(s) === 'infographic' ? '信息图' : '图表' }}
                  </el-tag>
                  <el-input v-model="s.i" size="small" class="slot-key" placeholder="键，如 w1" />
                  <el-button size="small" text type="danger" @click="removeSlot(idx)">删除</el-button>
                </div>

                <div class="slot-row">
                  <span class="slot-field-label">类型</span>
                  <el-select
                    :model-value="kindOf(s)"
                    size="small"
                    class="slot-kind"
                    @change="(v: 'chart' | 'infographic') => setKind(s, idx, v)"
                  >
                    <el-option label="数据图表" value="chart" />
                    <el-option label="信息图" value="infographic" />
                  </el-select>

                  <span class="slot-field-label">坐标</span>
                  <div class="slot-coords">
                    <el-input-number v-model="s.x" size="small" :controls="false" :min="0" :max="11" placeholder="x" />
                    <el-input-number v-model="s.y" size="small" :controls="false" :min="0" placeholder="y" />
                    <el-input-number v-model="s.w" size="small" :controls="false" :min="1" :max="12" placeholder="w" />
                    <el-input-number v-model="s.h" size="small" :controls="false" :min="1" placeholder="h" />
                  </div>
                </div>

                <div class="slot-row">
                  <template v-if="kindOf(s) === 'chart'">
                    <span class="slot-field-label">指标</span>
                    <el-input-number
                      v-model="s.metricId"
                      size="small"
                      :controls="false"
                      :min="1"
                      class="slot-metric"
                      placeholder="留空占位，实例化后绑定"
                    />
                    <el-input v-model="s.title" size="small" class="slot-title" placeholder="卡片标题（可空）" />
                  </template>
                  <template v-else>
                    <span class="slot-field-label">信息图</span>
                    <span class="slot-info-name">{{ tplLabel(s.infographic) }}</span>
                    <el-button size="small" text type="primary" @click="openInfoEditor(idx)">
                      <el-icon><Setting /></el-icon> 配置
                    </el-button>
                    <el-input v-model="s.title" size="small" class="slot-title" placeholder="卡片标题（可空）" />
                  </template>
                </div>
              </div>
            </div>
            <el-empty v-else :image-size="46" description="暂无槽位，点上方按钮添加图表 / 信息图" />

            <el-collapse v-model="jsonOpen" class="slot-json">
              <el-collapse-item title="高级：直接编辑 JSON（WidgetLayout[]）" name="json">
                <el-input
                  v-model="form.layoutJson"
                  type="textarea"
                  :rows="8"
                  class="layout-json"
                  placeholder='WidgetLayout[] JSON，如 [{"i":"w1","x":0,"y":0,"w":6,"h":4}]'
                  @change="pullJsonToSlots"
                />
                <div class="layout-hint">
                  <span class="muted small">
                    槽位字段：<code>i</code> 唯一键、<code>x/y/w/h</code> 12 列网格坐标；信息图槽位另带
                    <code>kind:"infographic"</code> 与 <code>infographic</code> 配置。
                  </span>
                  <el-tag v-if="parsedSlotCount != null" size="small" effect="plain" round>
                    {{ parsedSlotCount }} 个槽位
                  </el-tag>
                </div>
                <el-alert
                  v-if="jsonParseError"
                  type="warning"
                  :closable="false"
                  show-icon
                  class="mt-8"
                  :title="jsonParseError"
                />
              </el-collapse-item>
            </el-collapse>

            <el-alert v-if="layoutError" type="error" :closable="false" show-icon class="mt-8" :title="layoutError" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 信息图向导：与驾驶舱同源（画廊 → 编辑器，含实时预览 / 分组数据） -->
    <InfographicTemplatePicker v-model="infoPickerVisible" @select="onPickTemplate" />
    <InfographicEditorDialog
      v-model="infoDialogVisible"
      :spec="pendingSpec"
      :title="pendingTitle"
      :auto-sample="infoCreating"
      @confirm="onInfoConfirm"
    />
  </div>
</template>

<style scoped>
.content-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
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
}
.row {
  display: flex;
  align-items: center;
}
.gap-12 {
  gap: 12px;
}
/* 手册入口卡 */
.manual-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
}
.mc-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  color: #fff;
  background: linear-gradient(150deg, #00164f, #002fa7 60%, #0b3fc4);
  flex-shrink: 0;
}
.mc-body {
  flex: 1;
  min-width: 0;
}
.mc-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}
.mc-desc {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.5;
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
}
.stat-num {
  font-size: 24px;
  font-weight: 800;
  line-height: 1.1;
}
.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.table-card {
  padding: 8px 12px 12px;
}
.tpl-name {
  font-weight: 600;
}
.info-slot-tag {
  margin-left: 6px;
}
.icon-field {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}
.icon-preview {
  color: var(--text-secondary);
  flex-shrink: 0;
}
/* ---- 槽位设计器 ---- */
.slot-area {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.slot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.slot-ops {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.slot-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 42vh;
  overflow-y: auto;
  padding-right: 4px;
}
.slot-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--el-fill-color-blank);
}
.slot-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.slot-idx {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.slot-key {
  width: 110px;
  flex-shrink: 0;
}
.slot-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.slot-field-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.slot-kind {
  width: 120px;
  flex-shrink: 0;
}
.slot-coords {
  display: flex;
  align-items: center;
  gap: 6px;
}
.slot-coords :deep(.el-input-number) {
  width: 62px;
}
.slot-metric {
  width: 150px;
}
.slot-title {
  flex: 1;
  min-width: 120px;
}
.slot-info-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-success);
}
.slot-json {
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 2px;
}
.slot-json :deep(.el-collapse-item__header) {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.layout-json :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.5;
}
.layout-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
}
.layout-hint code,
.slot-head code {
  padding: 0 3px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 11.5px;
}
.field-hint {
  font-size: 12px;
  margin-left: 2px;
}
.small {
  font-size: 12px;
}
.mt-8 {
  margin-top: 8px;
}
</style>
