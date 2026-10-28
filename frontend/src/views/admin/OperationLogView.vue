<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { operationLogApi } from '@/api/operationLog'
import type { OperationLogVO, OperationQuery } from '@/types'
import { formatDateTime } from '@/utils/format'
import { ipLabel, moduleLabel, MODULE_LABELS } from '@/utils/auditLabels'

/**
 * 操作日志（平台后台 · 治理组，§10.2 审计落地之二，只读跨租户检索）。
 *
 * 权限码 admin:audit:read（矩阵 YNYN → SUPER_ADMIN / AUDITOR，后端 platformOnly 强校）。
 * 与「审计日志」(AuditLogView，读 auth_audit_log 仅登录/权限事件) 互补：本页读 audit_log，
 * 覆盖每一次 HTTP 操作（module/uri/method/http_status/duration_ms/error_msg/request_body），
 * 用于回溯「谁在什么时刻对哪个接口做了什么」。
 * 全部为只读投影：按模块/方法/URI/操作人/时间过滤，服务端分页；详情抽屉展示脱敏请求体、IP、UA、耗时、错误；
 * 导出走后端独立上限并会自审一条 AUDIT_EXPORT（资源类型 OPERATION_LOG）。
 */

/* ---------------- 过滤 + 服务端分页 ---------------- */
const loading = ref(false)
const rows = ref<OperationLogVO[]>([])
const total = ref(0)
const query = reactive<{ limit: number; offset: number }>({ limit: 20, offset: 0 })
const filters = reactive<{
  module: string | null
  method: string | null
  uri: string
  username: string
}>({
  module: null,
  method: null,
  uri: '',
  username: ''
})
const dateRange = ref<[string, string] | null>(null)

/** 文本框输入的过滤值：空串归为 undefined（不约束）。 */
function blank(v: string): string | undefined {
  const t = v.trim()
  return t ? t : undefined
}

function filterParams(): OperationQuery {
  return {
    module: filters.module || undefined,
    method: filters.method || undefined,
    uri: blank(filters.uri),
    username: blank(filters.username),
    // 失败标记：后端无 result 字段，这里不传 result（详情/高亮由前端按 http_status 判断）
    from: dateRange.value?.[0] || undefined,
    to: dateRange.value?.[1] || undefined
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await operationLogApi.list({ ...filterParams(), limit: query.limit, offset: query.offset })
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
  filters.module = null
  filters.method = null
  filters.uri = ''
  filters.username = ''
  dateRange.value = null
  onFilterChange()
}

/* ---------------- 失败高亮 ---------------- */
/** http_status >= 400 视为失败（含 403 权限拒绝 / 500 业务异常），行高亮 + 状态标签标红。 */
function isFailure(r: OperationLogVO): boolean {
  return typeof r.httpStatus === 'number' && r.httpStatus >= 400
}
function statusTagType(r: OperationLogVO): 'success' | 'danger' | 'warning' | 'info' {
  const s = r.httpStatus
  if (typeof s !== 'number') return 'info'
  if (s >= 500) return 'danger'
  if (s >= 400) return 'warning'
  if (s >= 200 && s < 300) return 'success'
  return 'info'
}
function rowClassName({ row }: { row: OperationLogVO }): string {
  return isFailure(row) ? 'op-row-failed' : ''
}

/* ---------------- 详情抽屉 ---------------- */
const detailVisible = ref(false)
const current = ref<OperationLogVO | null>(null)
function openDetail(row: OperationLogVO): void {
  current.value = row
  detailVisible.value = true
}

/* ---------------- CSV 导出 ---------------- */
const exporting = ref(false)

function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v)
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}
function toCsv(list: OperationLogVO[]): string {
  const headers = [
    'ID', '时间', '模块', '方法', 'URI', '操作人', '操作人UID', '租户', '租户ID',
    'HTTP状态', '耗时(ms)', '错误', 'IP', '请求ID', '请求体', 'User-Agent'
  ]
  const lines = list.map((r) =>
    [
      r.id, r.createdAt, moduleLabel(r.module), r.method, r.uri, r.username, r.userId,
      r.tenantName || (r.tenantId != null ? `#${r.tenantId}` : '—'), r.tenantId,
      r.httpStatus, r.durationMs, r.errorMsg, ipLabel(r.ip), r.requestId, r.requestBody, r.userAgent
    ]
      .map(csvCell)
      .join(',')
  )
  return [headers.join(','), ...lines].join('\n')
}

async function onExport(): Promise<void> {
  exporting.value = true
  try {
    const list = await operationLogApi.export(filterParams())
    if (!list.length) {
      ElMessage.warning('当前过滤条件下无可导出的操作记录')
      return
    }
    const blob = new Blob(['﻿' + toCsv(list)], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `operation-log-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`
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

const methodOptions = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const moduleOptions = computed(() =>
  Object.entries(MODULE_LABELS).map(([value, label]) => ({ value, label }))
)
</script>

<template>
  <div class="audit-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">操作日志</h2>
        <p class="ph-desc">
          全量 HTTP 操作回溯（指标/驾驶舱/问数/配置等每一次请求），含方法、URI、响应状态、耗时与脱敏请求体；只读、只增不改。
          与「审计日志」互补：后者仅记录登录/权限类安全事件。
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
      <div class="filters">
        <el-select
          v-model="filters.module"
          placeholder="全部模块"
          clearable
          filterable
          style="width: 160px"
          @change="onFilterChange"
        >
          <el-option v-for="o in moduleOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select
          v-model="filters.method"
          placeholder="全部方法"
          clearable
          style="width: 120px"
          @change="onFilterChange"
        >
          <el-option v-for="m in methodOptions" :key="m" :label="m" :value="m" />
        </el-select>
        <el-input
          v-model="filters.uri"
          placeholder="URI 模糊（如 /api/metric）"
          clearable
          style="width: 220px"
          @change="onFilterChange"
          @keyup.enter="onFilterChange"
        />
        <el-input
          v-model="filters.username"
          placeholder="操作人"
          clearable
          style="width: 140px"
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

      <el-table
        v-loading="loading"
        :data="rows"
        height="540"
        size="small"
        border
        stripe
        style="width: 100%"
        :row-class-name="rowClassName"
      >
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="模块" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" type="info">{{ moduleLabel(row.module) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="方法" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.method === 'GET' ? 'info' : 'warning'" effect="light">
              {{ row.method || '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="URI" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.uri || row.action || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.username || (row.userId != null ? `#${row.userId}` : '—') }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row)" size="small" effect="light" round>
              {{ row.httpStatus != null ? row.httpStatus : '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">
            <span :class="(row.durationMs ?? 0) > 1000 ? 'slow' : ''">{{ row.durationMs != null ? row.durationMs + 'ms' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="IP" width="130" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ ipLabel(row.ip) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无操作记录" :image-size="80" />
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

    <!-- 详情抽屉：完整操作行 + 脱敏请求体 + 错误 -->
    <el-drawer v-model="detailVisible" title="操作详情" size="640px">
      <div v-if="current" class="detail-body">
        <div class="d-grid">
          <div class="d-field">
            <span class="d-label">时间</span>
            <span class="d-value">{{ formatDateTime(current.createdAt) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">模块</span>
            <span class="d-value">{{ moduleLabel(current.module) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">方法</span>
            <span class="d-value mono">{{ current.method || '—' }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">HTTP 状态</span>
            <el-tag :type="statusTagType(current)" size="small" effect="light" round>
              {{ current.httpStatus != null ? current.httpStatus : '—' }}
            </el-tag>
          </div>
          <div class="d-field">
            <span class="d-label">操作人</span>
            <span class="d-value">
              {{ current.username || '—' }}
              <span v-if="current.userId != null" class="mono dim">#{{ current.userId }}</span>
            </span>
          </div>
          <div class="d-field">
            <span class="d-label">租户</span>
            <span class="d-value">
              {{ current.tenantName || (current.tenantId != null ? `#${current.tenantId}` : '—') }}
            </span>
          </div>
          <div class="d-field">
            <span class="d-label">耗时</span>
            <span class="d-value">{{ current.durationMs != null ? current.durationMs + 'ms' : '—' }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">IP</span>
            <span class="d-value mono">{{ ipLabel(current.ip) }}</span>
          </div>
          <div class="d-field">
            <span class="d-label">请求 ID</span>
            <span class="d-value mono">{{ current.requestId || '—' }}</span>
          </div>
        </div>
        <div class="d-field">
          <span class="d-label">URI</span>
          <p class="d-value mono uri">{{ current.uri || current.action || '—' }}</p>
        </div>
        <div v-if="current.queryString" class="d-field">
          <span class="d-label">查询串</span>
          <p class="d-value mono uri">{{ current.queryString }}</p>
        </div>
        <div v-if="isFailure(current)" class="d-field">
          <span class="d-label">错误信息</span>
          <pre class="detail-block err mono">{{ current.errorMsg || '（无）' }}</pre>
        </div>
        <div class="d-field">
          <span class="d-label">请求体（脱敏）</span>
          <pre v-if="current.requestBody" class="detail-block mono">{{ current.requestBody }}</pre>
          <span v-else class="d-value dim">（无 / 非写请求）</span>
        </div>
        <div class="d-field">
          <span class="d-label">User-Agent</span>
          <p class="d-value ua">{{ current.userAgent || '—' }}</p>
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
.uri {
  font-size: 12.5px;
  line-height: 1.6;
  word-break: break-all;
  color: var(--text-regular);
  margin: 0;
}
.slow {
  color: #e37318;
  font-weight: 600;
}
/* 失败行高亮（http_status >= 400） */
:deep(.op-row-failed) {
  background: rgba(245, 108, 108, 0.08);
}
:deep(.op-row-failed:hover > td) {
  background: rgba(245, 108, 108, 0.16) !important;
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
.detail-block.err {
  background: linear-gradient(135deg, #3a0d12, #7a1f29);
  color: #ffd9de;
}
</style>
