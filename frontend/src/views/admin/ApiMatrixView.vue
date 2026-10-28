<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminRbacApi } from '@/api/adminRbac'
import type { ApiMatrixRow, ApiMatrixVO } from '@/types'

/**
 * 接口权限矩阵（平台后台 · 治理组，§9.2 / 设计文档 §6.4）。
 *
 * 数据来自后端 ApiPermissionRegistry 启动期对全部 @RequestMapping 的扫描。治理价值在于把
 * 「新加接口忘了挂 @RequiresPermission」这一最易腐化点变成可见指标：落在 fail-closed 域却
 * 未挂权限点的接口（unannotatedFailClosed）即高危漏配，红色高亮，应尽快补注解。
 */
const loading = ref(false)
const data = ref<ApiMatrixVO | null>(null)

const keyword = ref('')
const onlyRisk = ref(false)
const onlyFailClosed = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await adminRbacApi.apiMatrix()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(load)

const rows = computed<ApiMatrixRow[]>(() => data.value?.endpoints ?? [])

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return rows.value.filter((r) => {
    if (onlyRisk.value && !isRisk(r)) return false
    if (onlyFailClosed.value && !r.failClosed) return false
    if (!k) return true
    return (
      r.pattern.toLowerCase().includes(k) ||
      (r.controller || '').toLowerCase().includes(k) ||
      (r.handler || '').toLowerCase().includes(k) ||
      (r.permissionCodes || []).some((c) => c.toLowerCase().includes(k))
    )
  })
})

/** 高危漏配：落在 fail-closed 域却未挂权限点。 */
function isRisk(r: ApiMatrixRow): boolean {
  return !r.annotated && r.failClosed
}

function rowClass({ row }: { row: ApiMatrixRow }): string {
  if (isRisk(row)) return 'row-risk'
  if (!row.annotated) return 'row-warn'
  return ''
}

function methodTagType(m: string): 'success' | 'primary' | 'warning' | 'danger' | 'info' {
  switch (m) {
    case 'GET':
      return 'success'
    case 'POST':
      return 'primary'
    case 'PUT':
      return 'warning'
    case 'DELETE':
      return 'danger'
    default:
      return 'info'
  }
}
</script>

<template>
  <div class="apimatrix-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">接口权限矩阵</h2>
        <p class="ph-desc">
          启动期扫描全部接口的权限挂载情况。fail-closed 域中未挂权限点的接口即越权风险，需补 @RequiresPermission。
        </p>
      </div>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 重新扫描</el-button>
    </div>

    <div class="stat-row">
      <div class="stat-card card">
        <span class="sc-label">接口总数</span>
        <span class="sc-value">{{ data?.total ?? 0 }}</span>
      </div>
      <div class="stat-card card">
        <span class="sc-label">已挂权限点</span>
        <span class="sc-value ok">{{ data?.annotated ?? 0 }}</span>
      </div>
      <div class="stat-card card" :class="{ 'is-risk': (data?.unannotatedFailClosed ?? 0) > 0 }">
        <span class="sc-label">fail-closed 域漏配</span>
        <span class="sc-value risk">{{ data?.unannotatedFailClosed ?? 0 }}</span>
      </div>
    </div>

    <div v-if="data?.failClosedDomains?.length" class="card domains-card">
      <span class="domains-label">fail-closed 域（未挂权限点即告警）：</span>
      <el-tag v-for="d in data.failClosedDomains" :key="d" size="small" effect="plain" type="warning" round class="mono">
        {{ d }}
      </el-tag>
    </div>

    <div class="card body-card">
      <div class="filters">
        <el-input v-model="keyword" placeholder="搜索 路径 / 控制器 / 权限码" clearable style="width: 300px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-checkbox v-model="onlyFailClosed">只看 fail-closed 域</el-checkbox>
        <el-checkbox v-model="onlyRisk">只看漏配（高危）</el-checkbox>
        <span class="filter-count">共 {{ filtered.length }} 个接口</span>
      </div>

      <el-table
        v-loading="loading"
        :data="filtered"
        height="540"
        size="small"
        border
        stripe
        :row-class-name="rowClass"
      >
        <el-table-column prop="httpMethod" label="方法" width="90">
          <template #default="{ row }">
            <el-tag :type="methodTagType(row.httpMethod)" size="small" effect="dark" round>{{ row.httpMethod }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pattern" label="路径" min-width="260" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.pattern }}</span></template>
        </el-table-column>
        <el-table-column label="处理器" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono dim">{{ row.controller }}.{{ row.handler }}</span></template>
        </el-table-column>
        <el-table-column label="权限点" min-width="220">
          <template #default="{ row }">
            <template v-if="row.permissionCodes?.length">
              <el-tag v-for="c in row.permissionCodes" :key="c" size="small" effect="light" round class="mono perm-tag">
                {{ c }}
              </el-tag>
            </template>
            <el-tag v-else type="danger" size="small" effect="plain" round>未挂权限点</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="资源" width="110">
          <template #default="{ row }">
            <span v-if="row.resourceType" class="mono dim">{{ row.resourceType }}</span>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="域" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.failClosed" type="warning" size="small" effect="plain" round>fail-closed</el-tag>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.apimatrix-view {
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
  max-width: 720px;
}
.stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.stat-card {
  padding: 14px 18px;
  min-width: 150px;
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
.sc-value.ok {
  color: var(--el-color-success);
}
.sc-value.risk {
  color: var(--el-color-danger);
}
.stat-card.is-risk {
  box-shadow: inset 0 0 0 1px var(--el-color-danger);
}
.domains-card {
  padding: 12px 16px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.domains-label {
  font-size: 12.5px;
  color: var(--text-secondary);
  font-weight: 600;
}
.body-card {
  padding: 12px 18px 18px;
}
.filters {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.filter-count {
  font-size: 12.5px;
  color: var(--text-secondary);
}
.mono {
  font-family: 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 12.5px;
}
.dim {
  color: var(--text-placeholder);
}
.perm-tag {
  margin: 2px 4px 2px 0;
}
:deep(.row-risk) {
  --el-table-tr-bg-color: var(--el-color-danger-light-9);
}
:deep(.row-warn) {
  --el-table-tr-bg-color: var(--el-color-warning-light-9);
}
</style>
