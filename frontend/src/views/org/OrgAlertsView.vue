<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertApi } from '@/api/alert'
import { metricApi } from '@/api/metric'
import { orgMemberApi } from '@/api/orgMember'
import { usePermissionStore } from '@/stores/permission'
import { useActionGuard, useRowBusy } from '@/utils/actionGuard'
import { formatDateTime } from '@/utils/format'
import type {
  AlertAttributionVO,
  AlertRecordVO,
  AlertRuleForm,
  AlertRuleVO,
  MemberVO,
  MetricVO
} from '@/types'

/**
 * 组织设置 · 智能预警（/api/org/alerts）。
 *
 * 闭环前端入口（补齐「后端完整、零前端」缺口）：规则 CRUD / 启停 / 手动检测 / 触发记录分页。
 * 权限码 alert:rule:* 、alert:record:read、alert:detect:execute（组织矩阵 YYNNNN → 仅 OWNER/ADMIN 可见此页），
 * 后端另有方法级 @RequiresPermission + assertManager 双重门禁。租户范围由后端强制收敛。
 *
 * 指标取值支持两族：内置平台键（query.count.24h 等）与业务指标键 metric:<id>（取该指标首行首列值）。
 */
const perm = usePermissionStore()

/** 内置平台指标键（与后端 DefaultMetricValueResolver 的 case 一一对应）。 */
const BUILTIN_METRICS = [
  { key: 'query.count.24h', label: '近 24h 问数（次）' },
  { key: 'query.fail.count.24h', label: '近 24h 失败问数（次）' },
  { key: 'query.fail.rate.24h', label: '近 24h 失败率（%）' },
  { key: 'datasource.count', label: '数据源数量（个）' }
]
const OPERATORS = [
  { value: 'GT', label: '大于 >' },
  { value: 'GTE', label: '大于等于 ≥' },
  { value: 'LT', label: '小于 <' },
  { value: 'LTE', label: '小于等于 ≤' }
]
const OPERATOR_SYMBOL: Record<string, string> = { GT: '>', GTE: '≥', LT: '<', LTE: '≤' }
const SEVERITIES = [
  { value: 'INFO', label: '提示', tag: 'info' as const },
  { value: 'WARN', label: '警告', tag: 'warning' as const },
  { value: 'CRITICAL', label: '严重', tag: 'danger' as const }
]

function operatorSymbol(op?: string | null): string {
  return (op && OPERATOR_SYMBOL[op]) || op || '—'
}
function severityLabel(s?: string | null): string {
  return SEVERITIES.find((o) => o.value === s)?.label || s || '—'
}
function severityTag(s?: string | null): 'info' | 'warning' | 'danger' {
  return SEVERITIES.find((o) => o.value === s)?.tag || 'info'
}

/* ---------------- 指标键选项（内置 + 业务指标） ---------------- */
const metrics = ref<MetricVO[]>([])

/** 全部可选指标键：内置平台键 + 指标库业务指标（metric:<id>）。 */
const metricOptions = computed(() => [
  ...BUILTIN_METRICS.map((m) => ({ key: m.key, label: m.label })),
  ...metrics.value.map((m) => ({ key: `metric:${m.id}`, label: `指标：${m.name}` }))
])
function metricLabel(key?: string | null): string {
  if (!key) return '—'
  return metricOptions.value.find((o) => o.key === key)?.label || key
}

/* ---------------- 站内信接收人（INAPP 渠道，规则指定用户） ---------------- */
const members = ref<MemberVO[]>([])

/** 组织成员下拉项（INAPP 收件人选择器数据源）。 */
async function loadMembers(): Promise<void> {
  try {
    members.value = await orgMemberApi.list()
  } catch {
    members.value = []
  }
}

/** 接收人展示名：昵称优先，自己追加「(我)」。 */
function memberLabel(m: MemberVO): string {
  return `${m.nickname || m.username || m.uid}${m.self ? ' (我)' : ''}`
}

/**
 * INAPP 接收人：表单里 notifyTarget 是逗号分隔的用户 id 串（与后端 InAppAlertNotifier.parseRecipients 对齐）；
 * 这里用一个 number[] 计算属性在「多选下拉」与「落库串」之间双向转换。
 */
const recipientIds = computed<number[]>({
  get: () =>
    (form.notifyTarget || '')
      .split(/[,，;；\s]+/)
      .filter(Boolean)
      .map(Number)
      .filter((n) => !Number.isNaN(n)),
  set: (v) => {
    form.notifyTarget = v.join(',')
  }
})

/**
 * 切换通知渠道时清空 notifyTarget：不同渠道的语义不同（INAPP=用户 id 串、EMAIL=邮箱串），
 * 残留旧值会导致校验误判或写入错误的接收人。
 */
function onChannelChange(): void {
  form.notifyTarget = ''
}

/* ---------------- 规则列表 ---------------- */
const loading = ref(false)
const rules = ref<AlertRuleVO[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    rules.value = await alertApi.listRules()
  } catch {
    rules.value = []
  } finally {
    loading.value = false
  }
}

async function loadMetrics(): Promise<void> {
  try {
    metrics.value = await metricApi.list()
  } catch {
    metrics.value = []
  }
}

onMounted(() => {
  void load()
  void loadMetrics()
  void loadMembers()
  void loadRecords()
})

const stats = computed(() => {
  const enabled = rules.value.filter((r) => r.enabled).length
  return { total: rules.value.length, enabled, disabled: rules.value.length - enabled }
})

/* ---------------- 规则编辑对话框 ---------------- */
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
// 保存 / 行内启停：连点只提交一次（启停按行分桶，两行并发互不干扰）
const { busy: submitting, run: runSubmit } = useActionGuard()
const toggleBusy = useRowBusy<number>()
const form = reactive<AlertRuleForm>({
  name: '',
  description: '',
  metricKey: '',
  operator: 'GT',
  threshold: 0,
  severity: 'WARN',
  enabled: true,
  notifyChannel: 'LOG',
  notifyTarget: '',
  windowMinutes: 1440,
  autoAttribution: false,
  attributionDimension: ''
})

function openCreate(): void {
  editingId.value = null
  Object.assign(form, {
    name: '',
    description: '',
    metricKey: BUILTIN_METRICS[0].key,
    operator: 'GT',
    threshold: 0,
    severity: 'WARN',
    enabled: true,
    notifyChannel: 'LOG',
    notifyTarget: '',
    windowMinutes: 1440,
    autoAttribution: false,
    attributionDimension: ''
  })
  dialogVisible.value = true
}

function openEdit(r: AlertRuleVO): void {
  editingId.value = r.id
  Object.assign(form, {
    name: r.name ?? '',
    description: r.description ?? '',
    metricKey: r.metricKey ?? '',
    operator: r.operator ?? 'GT',
    threshold: r.threshold ?? 0,
    severity: r.severity ?? 'WARN',
    enabled: r.enabled ?? true,
    notifyChannel: r.notifyChannel ?? 'LOG',
    notifyTarget: r.notifyTarget ?? '',
    windowMinutes: r.windowMinutes ?? 1440,
    autoAttribution: !!r.autoAttribution,
    attributionDimension: r.attributionDimension ?? ''
  })
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.name || !form.name.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  if (!form.metricKey) {
    ElMessage.warning('请选择或填写监控指标')
    return
  }
  if (form.threshold == null) {
    ElMessage.warning('请填写阈值')
    return
  }
  // 邮件渠道必须填收件人：否则规则能存下来但永远发不出去（后端也会拦，这里先给即时反馈）
  if (form.notifyChannel === 'EMAIL' && !(form.notifyTarget || '').trim()) {
    ElMessage.warning('通知渠道为「邮件」时，请填写收件人邮箱')
    return
  }
  // 站内信渠道必须指定接收人：命中时按规则 notify_target 写的就是这些人，空则后端回落失败、告警丢失
  if (form.notifyChannel === 'INAPP' && !(form.notifyTarget || '').trim()) {
    ElMessage.warning('通知渠道为「站内信」时，请选择接收人')
    return
  }
  await runSubmit(async () => {
    try {
      if (editingId.value == null) {
        await alertApi.createRule({ ...form })
        ElMessage.success('规则已创建')
      } else {
        await alertApi.updateRule(editingId.value, { ...form })
        ElMessage.success('规则已保存')
      }
      dialogVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

/**
 * 行内启停。
 *
 * <p>此前完全没有护栏：开关 {@code :model-value} 单向绑定，在途期间再点一下会发出方向相反的
 * 第二次写入（enable 与 disable 是两个不同 body，请求层去重也拦不住），最终状态取决于谁后到。
 * 故按行分桶 + 在途禁用该行开关。
 */
async function onToggle(r: AlertRuleVO, value: boolean): Promise<void> {
  await toggleBusy.run(r.id, async () => {
    try {
      if (value) await alertApi.enableRule(r.id)
      else await alertApi.disableRule(r.id)
      ElMessage.success(value ? '已启用' : '已停用')
    } catch {
      // 拦截器已提示
    } finally {
      // 无论成败都回读，避免开关停留在与后端不一致的位置
      await load()
    }
  })
}

async function onRemove(r: AlertRuleVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除规则「${r.name || r.id}」吗？删除后其定时检测即刻停止。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await alertApi.removeRule(r.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 归因试跑（P1：预警触发预跑归因） ---------------- */
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewData = ref<AlertAttributionVO | null>(null)

/** 试跑：用当前表单的指标键 + 维度立刻跑一次归因，不落库、不触发告警。 */
async function onPreviewAttribution(): Promise<void> {
  if (!form.metricKey) {
    ElMessage.warning('请先选择或填写监控指标')
    return
  }
  previewLoading.value = true
  previewData.value = null
  previewVisible.value = true
  try {
    previewData.value = await alertApi.previewAttribution({
      metricKey: form.metricKey,
      attributionDimension: form.attributionDimension || ''
    })
  } catch {
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

/** 试跑是否产出了真正的归因结论（有结论才渲染贡献清单，否则展示原因）。 */
function attrHasResult(a?: AlertAttributionVO | null): boolean {
  return !!a && !a.reason && !!a.drivers && a.drivers.length > 0
}

/** 数值格式化：带千分位、保留 2 位，负号前置。 */
function fmtNum(v?: number | null): string {
  if (v == null) return '—'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtPct(v?: number | null): string {
  if (v == null) return '—'
  return (v * 100).toFixed(1) + '%'
}
/** 涨红跌绿（中国区习惯）：正贡献红、负贡献绿。 */
function deltaClass(v?: number | null): string {
  if (v == null) return ''
  return v >= 0 ? 'up' : 'down'
}

/* ---------------- 手动检测 ---------------- */
const detecting = ref(false)
async function onDetect(): Promise<void> {
  detecting.value = true
  try {
    const res = await alertApi.detect()
    ElMessage.success(`检测完成，触发 ${res.fired ?? 0} 条`)
    await loadRecords()
  } catch {
    // 拦截器已提示
  } finally {
    detecting.value = false
  }
}

/* ---------------- 触发记录 ---------------- */
const recordsLoading = ref(false)
const records = ref<AlertRecordVO[]>([])
const recordTotal = ref(0)
const recordLimit = ref(20)
const recordOffset = ref(0)
/** 记录筛选：按规则过滤（0=全部）。 */
const recordRuleId = ref<number>(0)

async function loadRecords(): Promise<void> {
  recordsLoading.value = true
  try {
    const res = await alertApi.records({
      ruleId: recordRuleId.value > 0 ? recordRuleId.value : undefined,
      limit: recordLimit.value,
      offset: recordOffset.value
    })
    records.value = res.items
    recordTotal.value = res.total
  } catch {
    records.value = []
    recordTotal.value = 0
  } finally {
    recordsLoading.value = false
  }
}

function onRecordRuleChange(): void {
  recordOffset.value = 0
  void loadRecords()
}
function onRecordPage(page: number): void {
  recordOffset.value = (page - 1) * recordLimit.value
  void loadRecords()
}

const activeTab = ref<'rules' | 'records'>('rules')
</script>

<template>
  <div class="alerts-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">智能预警</h2>
        <p class="ph-desc">
          为组织配置指标阈值预警：命中阈值即生成触发记录（按去重窗口避重），定时扫描每小时自动执行，也可手动立即检测。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button v-if="perm.has('alert:detect:execute')" :loading="detecting" @click="onDetect">
          <el-icon><BellFilled /></el-icon> 立即检测
        </el-button>
        <el-button v-if="perm.has('alert:rule:write')" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 新建规则
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card card">
        <span class="sc-label">规则总数</span>
        <span class="sc-value">{{ stats.total }}</span>
      </div>
      <div class="stat-card card">
        <span class="sc-label">启用中</span>
        <span class="sc-value">{{ stats.enabled }}</span>
      </div>
      <div class="stat-card card">
        <span class="sc-label">已停用</span>
        <span class="sc-value">{{ stats.disabled }}</span>
      </div>
      <div class="stat-card card">
        <span class="sc-label">触发记录</span>
        <span class="sc-value">{{ recordTotal }}</span>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="card tabs-card">
      <!-- ============ 规则 ============ -->
      <el-tab-pane label="预警规则" name="rules">
        <el-table v-loading="loading" :data="rules" height="480" size="small" border stripe>
          <el-table-column label="规则名" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="rule-name">{{ row.name || '—' }}</span>
              <div v-if="row.description" class="rule-desc dim">{{ row.description }}</div>
            </template>
          </el-table-column>
          <el-table-column label="监控指标" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ metricLabel(row.metricKey) }}</span>
              <span class="mono dim metric-code">{{ row.metricKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="触发条件" width="150" align="center">
            <template #default="{ row }">
              <span class="mono cond">{{ operatorSymbol(row.operator) }} {{ row.threshold }}</span>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="severityTag(row.severity)" size="small" effect="light" round>
                {{ severityLabel(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="去重窗口" width="110" align="center">
            <template #default="{ row }">
              {{ row.windowMinutes != null ? row.windowMinutes + ' 分钟' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="通知" width="120" align="center">
            <template #default="{ row }">
              <el-tooltip
                v-if="row.notifyChannel === 'INAPP' && row.notifyTarget"
                :content="`接收人：${row.notifyTarget}`"
                placement="top"
              >
                <el-tag size="small" type="primary" effect="plain" round>站内信</el-tag>
              </el-tooltip>
              <el-tag
                v-else-if="row.notifyChannel === 'INAPP'"
                size="small"
                type="warning"
                effect="plain"
                round
              >
                站内信·缺接收人
              </el-tag>
              <el-tooltip
                v-else-if="row.notifyChannel === 'EMAIL' && row.notifyTarget"
                :content="`收件人：${row.notifyTarget}`"
                placement="top"
              >
                <el-tag size="small" effect="plain" round>邮件</el-tag>
              </el-tooltip>
              <el-tag v-else-if="row.notifyChannel === 'EMAIL'" size="small" type="warning" effect="plain" round>
                邮件·缺收件人
              </el-tag>
              <span v-else class="dim">{{ row.notifyChannel || 'LOG' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="80" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="!!row.enabled"
                :loading="toggleBusy.isBusy(row.id)"
                :disabled="!perm.has('alert:rule:write') || toggleBusy.isBusy(row.id)"
                @change="(v: string | number | boolean) => onToggle(row, !!v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="perm.has('alert:rule:write')" size="small" text @click="openEdit(row)">
                <el-icon><Edit /></el-icon> 编辑
              </el-button>
              <el-button
                v-if="perm.has('alert:rule:delete')"
                size="small"
                text
                type="danger"
                class="ops-danger"
                title="删除预警规则"
                @click="onRemove(row)"
              >
                <el-icon><Delete /></el-icon>
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无预警规则，点击「新建规则」创建" :image-size="80" />
          </template>
        </el-table>
      </el-tab-pane>

      <!-- ============ 触发记录 ============ -->
      <el-tab-pane label="触发记录" name="records">
        <div class="filters">
          <el-select
            v-model="recordRuleId"
            placeholder="按规则筛选"
            style="width: 220px"
            @change="onRecordRuleChange"
          >
            <el-option label="全部规则" :value="0" />
            <el-option v-for="r in rules" :key="r.id" :label="r.name || `#${r.id}`" :value="r.id" />
          </el-select>
          <el-button :loading="recordsLoading" @click="loadRecords">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
          <span class="filter-count">共 {{ recordTotal }} 条</span>
        </div>

        <el-table v-loading="recordsLoading" :data="records" height="420" size="small" border stripe>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div v-if="attrHasResult(row.attribution)" class="attr-box">
                <div class="attr-head">
                  <span class="attr-metric">{{ row.attribution.metricName || '指标' }}</span>
                  <span class="dim">
                    维度 {{ row.attribution.dimensionLabel || row.attribution.dimension }}
                    ｜基准 {{ row.attribution.baseline }}
                    ｜{{ row.attribution.basePeriod }} → {{ row.attribution.currentPeriod }}
                  </span>
                  <span class="mono">
                    总变化 <b :class="deltaClass(row.attribution.totalDelta)">{{ fmtNum(row.attribution.totalDelta) }}</b>
                  </span>
                  <el-tag v-if="row.attribution.gate" size="small" effect="plain" round>
                    {{ row.attribution.gate }}
                  </el-tag>
                  <span v-if="row.attribution.confidence != null" class="dim">
                    置信度 {{ fmtPct(row.attribution.confidence) }}
                  </span>
                </div>
                <ul class="driver-list">
                  <li v-for="(d, i) in row.attribution.drivers || []" :key="i">
                    <span class="dl-name">{{ d.label || d.member || '—' }}</span>
                    <span class="mono" :class="deltaClass(d.delta)">{{ fmtNum(d.delta) }}</span>
                    <span class="dim">占比 {{ fmtPct(d.share) }}</span>
                  </li>
                </ul>
                <p v-if="row.attribution.insight" class="attr-insight">{{ row.attribution.insight }}</p>
              </div>
              <div v-else-if="row.attribution" class="attr-box dim">
                未生成归因：{{ row.attribution.reason || '无归因数据' }}
              </div>
              <div v-else class="attr-box dim">该规则未开启自动归因，或触发时尚无归因结论。</div>
            </template>
          </el-table-column>
          <el-table-column label="触发时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="规则" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ruleName || '—' }}</template>
          </el-table-column>
          <el-table-column label="指标" min-width="160" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono small">{{ row.metricKey }}</span></template>
          </el-table-column>
          <el-table-column label="实测值" width="110" align="center">
            <template #default="{ row }"><span class="mono">{{ row.value }}</span></template>
          </el-table-column>
          <el-table-column label="条件" width="120" align="center">
            <template #default="{ row }">
              <span class="mono">{{ operatorSymbol(row.operator) }} {{ row.threshold }}</span>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="severityTag(row.severity)" size="small" effect="light" round>
                {{ severityLabel(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'FIRED' ? 'danger' : 'success'" size="small" effect="plain" round>
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="通知" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.notified ? 'success' : 'info'" size="small" effect="plain" round>
                {{ row.notified ? '已通知' : '未通知' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="归因" width="130" align="center">
            <template #default="{ row }">
              <el-tag
                v-if="attrHasResult(row.attribution)"
                type="warning"
                size="small"
                effect="light"
                round
              >
                {{ (row.attribution.drivers || [])[0]?.label || (row.attribution.drivers || [])[0]?.member || '已归因' }}
              </el-tag>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" min-width="240" show-overflow-tooltip />
          <template #empty>
            <el-empty description="暂无触发记录" :image-size="80" />
          </template>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="prev, pager, next, total"
            :total="recordTotal"
            :page-size="recordLimit"
            :current-page="Math.floor(recordOffset / recordLimit) + 1"
            @current-change="onRecordPage"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 规则编辑对话框 ============ -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新建预警规则' : '编辑预警规则'"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="规则名称">
          <el-input v-model="form.name" placeholder="例如：失败率过高告警" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
        <el-form-item label="监控指标">
          <el-select
            v-model="form.metricKey"
            filterable
            allow-create
            default-first-option
            placeholder="选择内置指标或业务指标（metric:<id>）"
            style="width: 100%"
          >
            <el-option v-for="m in metricOptions" :key="m.key" :label="m.label" :value="m.key" />
          </el-select>
          <span class="form-hint dim">业务指标取该指标查询结果的首行首列值；也可直接输入 metric:&lt;id&gt;。</span>
        </el-form-item>
        <el-form-item label="触发条件">
          <el-select v-model="form.operator" style="width: 140px">
            <el-option v-for="o in OPERATORS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input-number v-model="form.threshold" :precision="2" :step="1" style="width: 160px; margin-left: 10px" />
        </el-form-item>
        <el-form-item label="严重级别">
          <el-radio-group v-model="form.severity">
            <el-radio-button v-for="s in SEVERITIES" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="去重窗口">
          <el-input-number v-model="form.windowMinutes" :min="1" :max="10080" />
          <span class="form-hint dim inline">分钟内已触发过则不重复（默认 1440 = 24 小时）。</span>
        </el-form-item>
        <el-form-item label="通知渠道">
          <el-select v-model="form.notifyChannel" style="width: 200px" @change="onChannelChange">
            <el-option label="站内日志 (LOG)" value="LOG" />
            <el-option label="站内信 (INAPP)" value="INAPP" />
            <el-option label="邮件 (EMAIL)" value="EMAIL" />
            <el-option label="短信 (SMS)" value="SMS" />
            <el-option label="钉钉 (DINGTALK)" value="DINGTALK" />
            <el-option label="企业微信 (WECOM)" value="WECOM" />
            <el-option label="飞书 (FEISHU)" value="FEISHU" />
            <el-option label="PushPlus (PUSHPLUS)" value="PUSHPLUS" />
          </el-select>
          <span class="form-hint dim inline">
            渠道参数在「平台后台 · 通知配置」维护（密钥不回显）；选中未配置或不可用的渠道会自动回落兜底渠道。INAPP=站内信。提示：站内信为<b>默认触发</b>——无论主渠道选什么，每条预警命中都会额外写入相关人（规则指定接收人 + 规则创建人）的站内信收件箱，顶栏铃铛可见。
          </span>
        </el-form-item>
        <el-form-item v-if="form.notifyChannel === 'INAPP'" label="接收人">
          <el-select
            v-model="recipientIds"
            multiple
            filterable
            placeholder="选择接收人（组织成员）"
            style="width: 100%"
          >
            <el-option
              v-for="m in members"
              :key="m.uid"
              :label="memberLabel(m)"
              :value="m.uid"
            />
          </el-select>
          <span class="form-hint dim">
            预警命中时，系统向所选成员各写一条站内信（顶栏铃铛可见）。可多选；至少选择 1 人，否则规则保存后触发会失败并标记为未通知。
          </span>
        </el-form-item>
        <el-form-item v-if="form.notifyChannel === 'EMAIL'" label="收件人">
          <el-input
            v-model="form.notifyTarget"
            placeholder="ops@example.com, data-team@example.com"
            maxlength="512"
            clearable
          />
          <span class="form-hint dim">
            多个收件人用逗号或分号分隔。发件服务器与账号由部署方在服务端通过环境变量
            AIBI_MAIL_HOST / AIBI_MAIL_USERNAME / AIBI_MAIL_PASSWORD / AIBI_MAIL_FROM 配置；
            未配置时该规则自动回落站内日志，告警不会丢失。
          </span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-divider content-position="left">触发时归因（P1）</el-divider>
        <el-form-item label="自动归因">
          <el-switch v-model="form.autoAttribution" />
          <span class="form-hint dim inline">
            开启后，规则触发瞬间自动跑一次单维归因，把"是谁拖累的"随告警一起记录。
          </span>
        </el-form-item>
        <el-form-item v-if="form.autoAttribution" label="归因维度">
          <el-input
            v-model="form.attributionDimension"
            placeholder="留空则用该指标在指标库沉淀的默认维度"
            maxlength="64"
            clearable
          />
          <span class="form-hint dim">仅业务指标 metric:&lt;id&gt; 可归因；内置平台键会自动跳过。</span>
        </el-form-item>
        <el-form-item v-if="form.autoAttribution" label="">
          <el-button :loading="previewLoading" @click="onPreviewAttribution">
            <el-icon><DataAnalysis /></el-icon> 试跑归因
          </el-button>
          <span class="form-hint dim inline">立即预跑一次，确认维度可用且能算出结论（不落库、不触发告警）。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ editingId == null ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 归因试跑结果对话框 ============ -->
    <el-dialog v-model="previewVisible" title="归因试跑结果" width="620px">
      <div v-loading="previewLoading" class="preview-box">
        <template v-if="attrHasResult(previewData)">
          <div class="attr-head">
            <span class="attr-metric">{{ previewData?.metricName || '指标' }}</span>
            <span class="dim">
              维度 {{ previewData?.dimensionLabel || previewData?.dimension }}｜基准 {{ previewData?.baseline }}
            </span>
            <span class="mono">
              总变化 <b :class="deltaClass(previewData?.totalDelta)">{{ fmtNum(previewData?.totalDelta) }}</b>
            </span>
          </div>
          <el-alert
            type="success"
            :closable="false"
            show-icon
            title="该规则可自动归因，保存后触发时会带上归因结论。"
            class="pv-alert"
          />
          <ul class="driver-list">
            <li v-for="(d, i) in previewData?.drivers || []" :key="i">
              <span class="dl-name">{{ d.label || d.member || '—' }}</span>
              <span class="mono" :class="deltaClass(d.delta)">{{ fmtNum(d.delta) }}</span>
              <span class="dim">占比 {{ fmtPct(d.share) }}</span>
            </li>
          </ul>
          <p v-if="previewData?.insight" class="attr-insight">{{ previewData?.insight }}</p>
          <p v-if="previewData?.gate === 'BLOCK'" class="pv-warn">
            置信度不足（门控 BLOCK），自动归因仍会执行但结论仅供参考，建议先完善语义与口径。
          </p>
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          :title="previewData?.reason || '正在预跑…'"
          description="开启自动归因不会导致告警失败——只是该规则不附带归因结论。"
        />
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.alerts-view {
  height: 100%;
  overflow-y: auto;
  padding: 4px 4px 40px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
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
.ph-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.stat-card {
  padding: 14px 18px;
  min-width: 120px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sc-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.sc-value {
  font-size: 24px;
  font-weight: 800;
  color: var(--ikb);
}
.tabs-card {
  padding: 6px 18px 18px;
}
.filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.filter-count {
  font-size: 12.5px;
  color: var(--text-secondary);
}
.rule-name {
  font-weight: 600;
}
.rule-desc {
  font-size: 12px;
  margin-top: 2px;
}
.cond {
  font-weight: 700;
}
.mono {
  font-family: 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 12.5px;
}
.mono.small {
  font-size: 12px;
}
.dim {
  color: var(--text-placeholder);
}
.metric-code {
  margin-left: 8px;
}
.small {
  font-size: 12px;
}
.form-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
}
.form-hint.inline {
  margin: 0 0 0 10px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
/* ---- 预警归因摘要（触发记录展开 / 试跑结果共用）---- */
.attr-box {
  padding: 10px 14px;
}
.attr-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12.5px;
}
.attr-metric {
  font-weight: 700;
  font-size: 13px;
}
.driver-list {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.driver-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}
.dl-name {
  min-width: 90px;
  font-weight: 600;
}
.attr-insight {
  margin: 10px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-secondary);
}
/* 涨红跌绿（中国区习惯） */
.up {
  color: #f5222d;
  font-weight: 700;
}
.down {
  color: #13a463;
  font-weight: 700;
}
.preview-box {
  min-height: 90px;
}
.pv-alert {
  margin: 10px 0 0;
}
.pv-warn {
  margin: 10px 0 0;
  font-size: 12.5px;
  color: #e6a23c;
}
</style>
