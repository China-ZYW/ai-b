<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminAuditApi } from '@/api/adminAudit'
import type { AuditLogVO, AuditQuery, AuditQuerySlots } from '@/types'
import { useConfigStore } from '@/stores/config'
import AuditNlQueryPanel from '@/components/audit/AuditNlQueryPanel.vue'
import { formatDateTime } from '@/utils/format'
import {
  ACTION_OPTIONS,
  RESULT_OPTIONS,
  actionLabel,
  resultLabel,
  resultTagType,
  actorTypeLabel,
  ipLabel,
  resourceTypeLabel
} from '@/utils/auditLabels'

/**
 * 审计日志（平台后台 · 治理组，§9.2，只读跨租户检索）。
 *
 * 权限码 admin:audit:read（矩阵 YNYN → SUPER_ADMIN / AUDITOR，后端 platformOnly 强校）。
 * 全部为只读投影：按动作/结果/操作人/租户/时间过滤，服务端分页；详情抽屉展示脱敏 JSON、IP、UA、TraceID。
 * 导出走后端独立上限并会自审一条 AUDIT_EXPORT（§10.2「看日志的人也要被看」）。
 */

/* 动作/结果/身份/资源类型/IP 中文标签统一从 @/utils/auditLabels 引入（与后端 AuditAction 常量对齐，
   单一来源，避免两处视图字典漂移导致「INVITE_CREATEINVITE_CREATE」式裸码重复）。未知码原样展示。 */

function tenantLabel(row: AuditLogVO): string {
  if (row.tenantId == null) return '—'
  if (row.tenantId === 0) return '平台'
  return row.tenantName || `#${row.tenantId}`
}

/* ---------------- 过滤 + 服务端分页 ---------------- */
const loading = ref(false)
const rows = ref<AuditLogVO[]>([])
const total = ref(0)
const query = reactive<{ limit: number; offset: number }>({ limit: 20, offset: 0 })
const filters = reactive<{ tenantId: string; action: string | null; uid: string; result: string | null }>({
  tenantId: '',
  action: null,
  uid: '',
  result: null
})
const dateRange = ref<[string, string] | null>(null)

/** 文本框输入的 id 解析：空→undefined（不约束），非数字→undefined（避免脏参数打到后端）。 */
function parseId(v: string): number | undefined {
  const t = v.trim()
  if (!t) return undefined
  const n = Number(t)
  return Number.isFinite(n) ? n : undefined
}

function filterParams(): AuditQuery {
  return {
    tenantId: parseId(filters.tenantId),
    action: filters.action || undefined,
    uid: parseId(filters.uid),
    result: filters.result || undefined,
    from: dateRange.value?.[0] || undefined,
    to: dateRange.value?.[1] || undefined
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await adminAuditApi.list({ ...filterParams(), limit: query.limit, offset: query.offset })
    rows.value = page.items
    total.value = page.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onFilterChange(): void {
  query.offset = 0
  void load()
}
function onPageChange(p: number): void {
  query.offset = (p - 1) * query.limit
  void load()
}
function resetFilters(): void {
  filters.tenantId = ''
  filters.action = null
  filters.uid = ''
  filters.result = null
  dateRange.value = null
  onFilterChange()
}

/* ---------------- 审计智能查询（T11-3，门控 AUDIT/nl-query 默认关） ---------------- */
const configStore = useConfigStore()

/**
 * 把 AI 抽出的槽位映射进人工筛选表单重载：动作/结果/时间可落表单；
 * 操作人姓名/关键词/模块无对应控件，仅由面板 chips 回显（融合结果在 AI 面板命中数里体现）。
 */
function applyNlSlots(s: AuditQuerySlots): void {
  filters.action = s.action || null
  filters.result = s.result || null
  dateRange.value = s.timeFrom && s.timeTo ? [s.timeFrom, s.timeTo] : null
  onFilterChange()
}

/* ---------------- 详情抽屉 ---------------- */
const detailVisible = ref(false)
const current = ref<AuditLogVO | null>(null)
const prettyDetail = computed<string>(() => {
  const raw = current.value?.detail
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})
function openDetail(row: AuditLogVO): void {
  current.value = row
  detailVisible.value = true
}

/* ---------------- CSV 导出 ---------------- */
const exporting = ref(false)

function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v)
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}
function toCsv(list: AuditLogVO[]): string {
  const headers = [
    'ID', '时间', '动作', '动作码', '结果', '操作人', '操作人UID', '身份类型',
    '租户', '租户ID', '被操作人', '被操作人UID', '资源类型', '资源ID', 'IP', 'TraceID', '详情'
  ]
  const lines = list.map((r) =>
    [
      r.id, r.createdAt, actionLabel(r.action), r.action, resultLabel(r.result),
      r.username, r.uid, actorTypeLabel(r.actorType),
      tenantLabel(r), r.tenantId, r.targetUsername, r.targetUid,
      r.resourceType, r.resourceId, r.ip, r.traceId, r.detail
    ]
      .map(csvCell)
      .join(',')
  )
  return [headers.join(','), ...lines].join('\n')
}

async function onExport(): Promise<void> {
  exporting.value = true
  try {
    const list = await adminAuditApi.export(filterParams())
    if (!list.length) {
      ElMessage.warning('当前过滤条件下无可导出的审计记录')
      return
    }
    // BOM 防 Excel 打开中文乱码
    const blob = new Blob(['\uFEFF' + toCsv(list)], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `audit-log-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${list.length} 条`)
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="audit-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">审计日志</h2>
        <p class="ph-desc">
          跨租户检索认证/鉴权与运营高危动作（登录登出、权限拒绝、平台角色授予回收、席位变更、租户停用等），只读、只增不改。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button type="primary" :loading="exporting" @click="onExport">
          <el-icon><Download /></el-icon> 导出 CSV
        </el-button>
      </div>
    </div>

    <div class="card body-card">
      <AuditNlQueryPanel
        v-if="configStore.auditNlQueryEnabled"
        :nl-query="adminAuditApi.nlQuery"
        @apply="applyNlSlots"
      />
      <div class="filters">
        <el-select
          v-model="filters.action"
          placeholder="全部动作"
          clearable
          filterable
          allow-create
          default-first-option
          style="width: 190px"
          @change="onFilterChange"
        >
          <el-option v-for="o in ACTION_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select
          v-model="filters.result"
          placeholder="全部结果"
          clearable
          style="width: 130px"
          @change="onFilterChange"
        >
          <el-option v-for="o in RESULT_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input
          v-model="filters.uid"
          placeholder="操作人 UID"
          clearable
          style="width: 140px"
          @change="onFilterChange"
          @keyup.enter="onFilterChange"
        />
        <el-input
          v-model="filters.tenantId"
          placeholder="租户 ID（0=平台）"
          clearable
          style="width: 160px"
          @change="onFilterChange"
          @keyup.enter="onFilterChange"
        />
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width: 360px"
          @change="onFilterChange"
        />
        <el-button @click="resetFilters"><el-icon><RefreshLeft /></el-icon> 重置</el-button>
        <span class="filter-count">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" height="540" size="small" border stripe style="width: 100%">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="动作" min-width="150">
          <template #default="{ row }">
            <span>{{ actionLabel(row.action) }}</span>
            <span class="mono dim action-code">{{ row.action }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="resultTagType(row.result)" size="small" effect="light" round>
              {{ resultLabel(row.result) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作人" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.username || (row.uid != null ? `#${row.uid}` : '—') }}</span>
            <el-tag size="small" type="info" effect="plain" round class="actor-tag">
              {{ actorTypeLabel(row.actorType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="租户" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ tenantLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="被操作人" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.targetUsername || (row.targetUid != null ? `#${row.targetUid}` : '—') }}
          </template>
        </el-table-column>
        <el-table-column label="资源" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.resourceType || row.resourceId" class="mono">
              {{ resourceTypeLabel(row.resourceType) }}{{ row.resourceId ? ' · ' + row.resourceId : '' }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="IP" width="140" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.ip || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无审计记录" :image-size="80" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="query.limit"
          :current-page="query.offset / query.limit + 1"
          @current-change="onPageChange"
        />
      </div>
    </div>

    <!-- 详情抽屉：完整审计行 + 脱敏 detail JSON -->
    <el-drawer v-model="detailVisible" title="审计详情" size="620px">
      <div v-if="current" class="detail-body">
        <div class="d-grid">
          <div class="d-field">
            <span class="d-label">动作</span>
            <span class="d-value">{{ actionLabel(current.action) }}</span>
            <span class="mono dim">{{ current.action }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">结果</span>
            <el-tag :type="resultTagType(current.result)" size="small" effect="light" round>
              {{ resultLabel(current.result) }}
            </el-tag>
          </div>
          <div class="d-field">
            <span class="d-label">时间</span>
            <span class="d-value">{{ formatDateTime(current.createdAt) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">租户</span>
            <span class="d-value">{{ tenantLabel(current) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">操作人</span>
            <span class="d-value">
              {{ current.username || '—' }}
              <span v-if="current.uid != null" class="mono dim">#{{ current.uid }}</span>
            </span>
          </div>
          <div class="d-field">
            <span class="d-label">身份类型</span>
            <span class="d-value">{{ actorTypeLabel(current.actorType) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">被操作人</span>
            <span class="d-value">
              {{ current.targetUsername || '—' }}
              <span v-if="current.targetUid != null" class="mono dim">#{{ current.targetUid }}</span>
            </span>
          </div>
          <div class="d-field">
            <span class="d-label">身份 ID</span>
            <span class="d-value mono">{{ current.identityId ?? '—' }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">资源类型</span>
            <span class="d-value mono">{{ current.resourceType || '—' }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">资源标识</span>
            <span class="d-value mono">{{ current.resourceId || '—' }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">IP</span>
            <span class="d-value mono">{{ ipLabel(current.ip) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">TraceID</span>
            <span class="d-value mono">{{ current.traceId || '—' }}</span>
          </div>
        </div>
        <div class="d-field">
          <span class="d-label">User-Agent</span>
          <p class="d-value ua">{{ current.userAgent || '—' }}</p>
        </div>
        <div class="d-field">
          <span class="d-label">变更详情（脱敏 JSON）</span>
          <pre v-if="prettyDetail" class="detail-block mono">{{ prettyDetail }}</pre>
          <span v-else class="d-value dim">（无）</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.audit-view {
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
.body-card {
  padding: 12px 18px 18px;
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
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
.mono {
  font-family: 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 12.5px;
}
.dim {
  color: var(--text-placeholder);
}
.action-code {
  margin-left: 8px;
}
.actor-tag {
  margin-left: 8px;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.d-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}
.d-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.d-label {
  font-size: 12px;
  color: var(--text-placeholder);
  font-weight: 600;
}
.d-value {
  font-size: 13.5px;
  color: var(--text-primary);
  margin: 0;
}
.d-value.ua {
  font-size: 12.5px;
  line-height: 1.6;
  word-break: break-all;
  color: var(--text-regular);
}
.detail-block {
  margin: 0;
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
</style>
