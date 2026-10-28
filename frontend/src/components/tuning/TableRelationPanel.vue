<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { semanticApi } from '@/api/semantic'
import { datasourceApi } from '@/api/datasource'
import type {
  RelationSuggestion,
  RelationType,
  SemanticMeta,
  TableMeta,
  TableRelationDef
} from '@/types'
import { useActionGuard, useRowBusy } from '@/utils/actionGuard'
import { usePerm } from '@/composables/usePerm'
import RelationGraphCanvas from './RelationGraphCanvas.vue'

/**
 * 表关联登记面板（语义层第三级「关系语义」）：人工维护"哪两张表用哪对键 JOIN、基数是什么"。
 *
 * 为什么单独一个面板：语义管理只到表级与列级，多表问数下大模型知道"可以 JOIN"却不知道"该用哪个键 JOIN"。
 * 业务库与分析型库普遍不建 FOREIGN KEY，命名推断只能兜底且推不出多对多；缺了权威口径它会拿同名列硬凑
 * （orders.id = order_item.id）或逗号连接产生笛卡尔积，而一对多关联后直接 SUM 会造成金额翻倍这类
 * "SQL 跑通、结果非空、数值错"的静默错数——比报错更难发现。
 *
 * 登记的行以最高可信度并入关联图谱（同键压过外键与推断），并贯穿选表扩散、Prompt 的【关联关系】段
 * 与静态校验三处，保存后即时生效。
 */
const props = defineProps<{ datasourceId: number | null }>()

const { can } = usePerm()

const loading = ref(false)
const rows = ref<TableRelationDef[]>([])

/** 语义行（只读借用）：从中取表名与列名做下拉候选，免去手敲错字——连接键写错比不写更糟。 */
const semanticRows = ref<SemanticMeta[]>([])

/** 数据源当前表结构（已按表 ACL 过滤隐藏表）。语义层骨架未初始化时它才是表名/列名的唯一来源。 */
const schemaTables = ref<TableMeta[]>([])

async function load(): Promise<void> {
  if (props.datasourceId == null) {
    rows.value = []
    semanticRows.value = []
    schemaTables.value = []
    return
  }
  loading.value = true
  try {
    const [relations, semantics, schema] = await Promise.all([
      semanticApi.relations(props.datasourceId),
      // 骨架未初始化时语义行为空，下拉退化为可自由输入，不阻断登记
      semanticApi.list(props.datasourceId).catch(() => [] as SemanticMeta[]),
      // 同理，连不上/未同步时退化为纯语义行，不阻断登记
      datasourceApi.schema(props.datasourceId).catch(() => [] as TableMeta[])
    ])
    rows.value = relations
    semanticRows.value = semantics
    schemaTables.value = schema
  } catch {
    rows.value = []
    semanticRows.value = []
    schemaTables.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.datasourceId, () => void load(), { immediate: true })

/**
 * 表名下拉候选（去重排序）：物理 schema 表 ∪ 语义行表。
 * 只看语义行会漏掉已同步但尚未补语义的表；骨架未初始化时则直接退化为空、无法登记关联。
 */
const tableOptions = computed(() => {
  const set = new Set<string>()
  schemaTables.value.forEach((t) => {
    if (t.name) set.add(t.name)
  })
  semanticRows.value.forEach((r) => {
    if (r.tableName) set.add(r.tableName)
  })
  return Array.from(set).sort()
})

/**
 * 某表的列名候选：物理列优先（连接键必须是表里真实存在的列），再并入语义行登记过的列。
 * 表未在语义层登记时仍返回 schema 列，避免"骨架没跑就选不了键"。
 */
function columnsOf(table: string): string[] {
  if (!table) return []
  const set = new Set<string>()
  const hit = schemaTables.value.find((t) => t.name === table)
  ;(hit?.columns ?? []).forEach((c) => {
    if (c.name) set.add(c.name)
  })
  semanticRows.value.forEach((r) => {
    if (r.objectType === 'COLUMN' && r.tableName === table && r.columnName) set.add(r.columnName)
  })
  return Array.from(set).sort()
}

const TYPE_OPTIONS: { value: RelationType; label: string; hint: string }[] = [
  {
    value: 'MANY_TO_ONE',
    label: '多对一',
    hint: '左表多行对应右表一行（最常见）。按右表统计左表的度量会因关联而重复计数。'
  },
  { value: 'ONE_TO_ONE', label: '一对一', hint: '关联不放大行数。' },
  {
    value: 'MANY_TO_MANY',
    label: '多对多',
    hint: '两侧行数都会翻倍，汇总度量前必须先去重或先在子查询里按统计粒度聚合，否则数值会被严重放大。'
  }
]

function typeLabel(t?: string | null): string {
  return TYPE_OPTIONS.find((o) => o.value === t)?.label || '未声明'
}

function typeHint(t?: string | null): string {
  return TYPE_OPTIONS.find((o) => o.value === t)?.hint || ''
}

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
const isEdit = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const enabledBusy = useRowBusy<number>()

interface FormState {
  leftTable: string
  leftColumn: string
  rightTable: string
  rightColumn: string
  relationType: RelationType
  description: string
  enabled: number
}
const emptyForm = (): FormState => ({
  leftTable: '',
  leftColumn: '',
  rightTable: '',
  rightColumn: '',
  relationType: 'MANY_TO_ONE',
  description: '',
  enabled: 1
})
const form = reactive<FormState>(emptyForm())

const leftColumns = computed(() => columnsOf(form.leftTable))
const rightColumns = computed(() => columnsOf(form.rightTable))

function openCreate(): void {
  editingId.value = null
  isEdit.value = false
  Object.assign(form, emptyForm())
  drawerVisible.value = true
}

function openEdit(r: TableRelationDef): void {
  editingId.value = r.id ?? null
  isEdit.value = true
  Object.assign(form, {
    leftTable: r.leftTable ?? '',
    leftColumn: r.leftColumn ?? '',
    rightTable: r.rightTable ?? '',
    rightColumn: r.rightColumn ?? '',
    relationType: (TYPE_OPTIONS.some((o) => o.value === r.relationType)
      ? r.relationType
      : 'MANY_TO_ONE') as RelationType,
    description: r.description ?? '',
    enabled: r.enabled ?? 1
  })
  drawerVisible.value = true
}

/** 采纳推荐：带出候选的表列与基数，交人工确认后再落库（推断的基数常是 UNKNOWN）。 */
function openAdopt(s: RelationSuggestion): void {
  editingId.value = s.registeredId ?? null
  isEdit.value = s.registeredId != null
  Object.assign(form, emptyForm(), {
    leftTable: s.leftTable,
    leftColumn: s.leftColumn,
    rightTable: s.rightTable,
    rightColumn: s.rightColumn,
    relationType: (TYPE_OPTIONS.some((o) => o.value === s.relationType)
      ? s.relationType
      : 'MANY_TO_ONE') as RelationType
  })
  drawerVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!form.leftTable.trim() || !form.leftColumn.trim() || !form.rightTable.trim() || !form.rightColumn.trim()) {
    ElMessage.warning('两侧的表名与连接列都必须填写')
    return
  }
  if (
    form.leftTable.trim().toLowerCase() === form.rightTable.trim().toLowerCase() &&
    form.leftColumn.trim().toLowerCase() === form.rightColumn.trim().toLowerCase()
  ) {
    ElMessage.warning('左右两侧不能是同表同列（自连接无意义）')
    return
  }
  await runSubmit(async () => {
    try {
      const payload: TableRelationDef = {
        datasourceId: props.datasourceId,
        leftTable: form.leftTable.trim(),
        leftColumn: form.leftColumn.trim(),
        rightTable: form.rightTable.trim(),
        rightColumn: form.rightColumn.trim(),
        relationType: form.relationType,
        description: form.description.trim() || null,
        enabled: form.enabled
      }
      if (isEdit.value && editingId.value != null) {
        await semanticApi.saveRelation({ ...payload, id: editingId.value })
        ElMessage.success('关联登记已更新')
      } else {
        await semanticApi.saveRelation(payload)
        ElMessage.success('关联登记已保存，下次问数即生效')
      }
      drawerVisible.value = false
      await load()
      await loadSuggestions()
    } catch {
      // 拦截器已提示（后端会校验表列是否真实存在）
    }
  })
}

/**
 * 行内启停：用行级护栏而非无护栏。
 *
 * 开关是单向绑定的，翻转后到回读完成之间它停在"新值"上——此时再点一下就发出与在途请求相反的第二次写入，
 * 请求层去重救不了（body 不同），最终落库值取决于谁后到。故在途期间必须禁用该行开关。
 */
async function toggleEnabled(r: TableRelationDef, enabled: number): Promise<void> {
  if (r.id == null) return
  await enabledBusy.run(r.id, async () => {
    try {
      await semanticApi.saveRelation({ ...r, enabled })
    } catch {
      // 拦截器已提示
    } finally {
      // 无论成败都回读，避免开关停留在与后端不一致的位置
      await load()
    }
  })
}

async function onRemove(r: TableRelationDef): Promise<void> {
  if (r.id == null) return
  try {
    await ElMessageBox.confirm(
      `确定删除「${r.leftTable}.${r.leftColumn} = ${r.rightTable}.${r.rightColumn}」这条关联登记吗？删除后问数将退回外键与命名推断。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await semanticApi.removeRelation(r.id)
    ElMessage.success('已删除')
    await load()
    await loadSuggestions()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 推荐关联（治理推断误差的正规出口） ---------------- */
const suggestVisible = ref(false)
const suggestLoading = ref(false)
const suggestions = ref<RelationSuggestion[]>([])

async function loadSuggestions(): Promise<void> {
  if (props.datasourceId == null) {
    suggestions.value = []
    return
  }
  suggestLoading.value = true
  try {
    suggestions.value = await semanticApi.relationSuggestions(props.datasourceId)
  } catch {
    suggestions.value = []
  } finally {
    suggestLoading.value = false
  }
}

async function openSuggest(): Promise<void> {
  suggestVisible.value = true
  await loadSuggestions()
}

/** 未登记的推荐数（已登记的只用于对照，不计入待办）。 */
const pendingCount = computed(() => suggestions.value.filter((s) => s.registeredId == null).length)

function sourceLabel(s: string): string {
  return s === 'FOREIGN_KEY' ? '库中外键' : '命名推断'
}

/* ---------------- 关联图谱画布 ---------------- */
const graphVisible = ref(false)
/** 推荐关联是否一起画在图上（默认开：图上同时看到"已登记"与"待登记"才有对比，才知道漏了哪块）。 */
const showSuggestions = ref(true)
/** 是否连已停用的登记一起画（停用不参与问数，默认隐藏）。 */
const showDisabled = ref(false)
const graphRef = ref<InstanceType<typeof RelationGraphCanvas> | null>(null)

/** 打开前现拉一次：收藏式快照会让人看到陈旧拓扑（邻接关系刚改动过尤其）。 */
async function openGraph(): Promise<void> {
  graphVisible.value = true
  await load()
  await loadSuggestions()
  void nextTick(() => graphRef.value?.fit())
}

function onGraphEdit(r: TableRelationDef): void {
  openEdit(r)
}

function onGraphRemove(r: TableRelationDef): void {
  void onRemove(r)
}

/* ---------------- 全部采纳 ---------------- */
const adoptAllBusy = ref(false)

/**
 * 一键采纳全部待登记推荐。
 *
 * 与逐条采纳同口径（后端走同一条落库逻辑）：已登记的跳过、绝不覆盖人工口径；推不出基数的行
 * 保留「待确认」而不是悄悄断言成多对一——误判多对一会直接放大度量，比漏登记更糟。
 */
async function onAdoptAll(): Promise<void> {
  if (props.datasourceId == null || pendingCount.value === 0) return
  if (!can('semantic:meta:write')) {
    ElMessage.warning('当前账号没有语义层写权限，无法采纳')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将采纳全部 ${pendingCount.value} 条推荐关联并写入人工登记（已登记的 ${suggestions.value.length - pendingCount.value} 条会跳过，不会被覆盖）。\n` +
        `推不出基数的行会保留「待确认」，请采纳后到列表里核对——基数标错会让度量翻倍。`,
      '全部采纳',
      { type: 'warning', confirmButtonText: '全部采纳', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  adoptAllBusy.value = true
  try {
    const r = await semanticApi.adoptAllRelations(props.datasourceId, 'MANY_TO_ONE')
    const msg = `已采纳 ${r.adopted ?? 0} 条关联登记`
    ElMessage.success(r.unknownTypes > 0 ? `${msg}，其中 ${r.unknownTypes} 条基数待确认` : msg)
    await load()
    await loadSuggestions()
  } catch {
    // 拦截器已提示
  } finally {
    adoptAllBusy.value = false
  }
}
</script>

<template>
  <div class="relation-panel">
    <div class="panel-toolbar">
      <p class="tb-tip">
        登记「哪两张表用哪对键 JOIN、基数是什么」。库中没建外键约束时，这是多表问数唯一可靠的连接键来源；
        登记的口径以最高可信度覆盖外键与命名推断，保存后下次问数即生效。
      </p>
      <div class="tb-actions">
        <el-button size="default" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="default" :disabled="datasourceId == null" :loading="suggestLoading" @click="openSuggest">
          <el-icon><MagicStick /></el-icon> 推荐关联
        </el-button>
        <el-button size="default" :disabled="datasourceId == null" @click="openGraph">
          <el-icon><Connection /></el-icon> 关联图谱
        </el-button>
        <el-button
          v-if="can('semantic:meta:write')"
          type="primary"
          size="default"
          :disabled="datasourceId == null"
          @click="openCreate"
        >
          <el-icon><Plus /></el-icon> 新增登记
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

    <el-table v-loading="loading" :data="rows" style="width: 100%" row-key="id" border stripe size="small">
      <el-table-column label="多方（左）" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.leftTable }}.{{ row.leftColumn }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="44" align="center">
        <template #default><span class="muted">=</span></template>
      </el-table-column>
      <el-table-column label="一方（右）" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.rightTable }}.{{ row.rightColumn }}</span>
        </template>
      </el-table-column>
      <el-table-column label="基数" width="104" align="center">
        <template #default="{ row }">
          <el-tooltip :content="typeHint(row.relationType)" :disabled="!typeHint(row.relationType)" placement="top">
            <el-tag
              :type="row.relationType === 'MANY_TO_MANY' ? 'danger' : row.relationType === 'ONE_TO_ONE' ? 'info' : 'warning'"
              effect="light"
              size="small"
              round
            >
              {{ typeLabel(row.relationType) }}
            </el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="业务口径说明" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.description || '—' }}</template>
      </el-table-column>
      <el-table-column label="启用" width="76" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :active-value="1"
            :inactive-value="0"
            :loading="enabledBusy.isBusy(row.id)"
            :disabled="enabledBusy.isBusy(row.id) || !can('semantic:meta:write')"
            @change="(v: number) => toggleEnabled(row, v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-if="can('semantic:meta:write')" size="small" text @click="openEdit(row)">
            <el-icon><Edit /></el-icon> 编辑
          </el-button>
          <el-button
            v-if="can('semantic:meta:delete')"
            size="small"
            text
            type="danger"
            @click="onRemove(row)"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          description="暂无关联登记，点击「推荐关联」让系统按外键与命名约定推出候选，确认后一键采纳"
          :image-size="80"
        />
      </template>
    </el-table>

    <!-- 推荐关联：把"推断（可能错）"升级为"人工登记（权威）"的治理出口 -->
    <el-drawer v-model="suggestVisible" title="推荐关联" size="720px">
      <el-alert type="info" :closable="false" show-icon class="mb12">
        <template #title>
          下面是系统按库中外键约束与命名约定（如 order_id → orders.id）推出的连接键，共
          {{ suggestions.length }} 条，其中 {{ pendingCount }} 条尚未登记。
        </template>
        <div class="suggest-desc">
          命名推断是启发式的、可能推错，且推不出多对多。采纳前请核对基数：登记后它以最高可信度覆盖推断，
          登记错的连接键比不登记更糟——会让问数稳定地按错键 JOIN，产出"跑通但数值错"的结果。
        </div>
      </el-alert>
      <div class="suggest-bar">
        <span class="dim">共 {{ suggestions.length }} 条推荐，{{ pendingCount }} 条待采纳</span>
        <el-button
          v-if="can('semantic:meta:write')"
          type="primary"
          size="small"
          :loading="adoptAllBusy"
          :disabled="pendingCount === 0"
          @click="onAdoptAll"
        >
          <el-icon><Select /></el-icon> 全部采纳
        </el-button>
      </div>
      <el-table v-loading="suggestLoading" :data="suggestions" style="width: 100%" border stripe size="small">
        <el-table-column label="连接键" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.leftTable }}.{{ row.leftColumn }} = {{ row.rightTable }}.{{ row.rightColumn }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="row.source === 'FOREIGN_KEY' ? 'success' : 'info'" effect="plain" size="small" round>
              {{ sourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="推导基数" width="104" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.relationType === 'UNKNOWN'" type="warning" effect="plain" size="small" round>
              待确认
            </el-tag>
            <span v-else>{{ typeLabel(row.relationType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="112" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.registeredId != null"
              size="small"
              text
              :disabled="!can('semantic:meta:write')"
              @click="openAdopt(row)"
            >
              <el-icon><Edit /></el-icon> 已登记
            </el-button>
            <el-button
              v-else
              size="small"
              type="primary"
              text
              :disabled="!can('semantic:meta:write')"
              @click="openAdopt(row)"
            >
              <el-icon><Plus /></el-icon> 采纳
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            description="没有可推荐的关联：库里既无外键约束，命名也无从推断。请手工「新增登记」。"
            :image-size="80"
          />
        </template>
      </el-table>
    </el-drawer>

    <!-- 关联图谱：把"一张张登记"折叠成"一张图看全库怎么连" -->
    <el-dialog v-model="graphVisible" title="关联图谱" width="1180px" top="6vh" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon class="mb12">
        <template #title>
          实线为已登记（箭头由多方指向一方），虚线为系统推荐、尚未登记的候选。
          拖动空白处平移、滚轮缩放，节点可拖动钉住位置。
        </template>
        <div class="suggest-desc">
          看不出问题的图不等于图没问题：基数标错（尤其把多对多误判成多对一）不会报错，只会让度量成倍放大。
          点开每条连线核对基数，比扫一眼布局有用得多。
        </div>
      </el-alert>
      <div class="graph-bar">
        <el-switch v-model="showSuggestions" size="small" inline-prompt active-text="显示推荐" inactive-text="隐藏推荐" />
        <el-switch v-model="showDisabled" size="small" inline-prompt active-text="显示停用" inactive-text="仅启用" />
        <span class="dim">提示：右侧「未接入的表」列出了还没登记任何关联的表，是漏配的重点。</span>
      </div>
      <RelationGraphCanvas
        ref="graphRef"
        :relations="rows"
        :suggestions="suggestions"
        :show-suggestions="showSuggestions"
        :only-enabled="!showDisabled"
        height="58vh"
        @edit-relation="onGraphEdit"
        @remove-relation="onGraphRemove"
      />
    </el-dialog>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑关联登记' : '新增关联登记'" size="520px">
      <el-form :model="form" label-width="88px">
        <el-form-item label="多方表" required>
          <el-select
            v-model="form.leftTable"
            placeholder="选择或输入表名"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option v-for="t in tableOptions" :key="t" :label="t" :value="t" />
          </el-select>
          <div class="field-tip">持有连接键的一侧；跨库联合分析时表名需带「库名.」前缀。</div>
        </el-form-item>
        <el-form-item label="多方连接列" required>
          <el-select
            v-model="form.leftColumn"
            placeholder="选择或输入列名"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option v-for="c in leftColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="一方表" required>
          <el-select
            v-model="form.rightTable"
            placeholder="选择或输入表名"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option v-for="t in tableOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="一方连接列" required>
          <el-select
            v-model="form.rightColumn"
            placeholder="选择或输入列名"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option v-for="c in rightColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="基数" required>
          <el-select v-model="form.relationType" style="width: 100%">
            <el-option v-for="o in TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <div class="field-tip">{{ typeHint(form.relationType) }}</div>
        </el-form-item>
        <el-form-item label="口径说明">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="可选：为何这样关联、有无业务例外（如「退款单不参与金额汇总」）"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.relation-panel {
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
  max-width: 620px;
}
.tb-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mb12 {
  margin-bottom: 12px;
}
.muted {
  color: var(--text-secondary);
}
.field-tip {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-top: 2px;
}
.suggest-desc {
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}
/* 推荐清单的工具条：左计数、右「全部采纳」，与表格之间留出间距。 */
.suggest-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
/* 图谱弹窗的筛选条：两个开关 + 一句提示，与画布之间留出间距。 */
.graph-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.graph-bar .dim {
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
