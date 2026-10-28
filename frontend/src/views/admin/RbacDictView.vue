<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminRbacApi } from '@/api/adminRbac'
import type { PermissionVO, RolePermissionMatrix, RoleVO } from '@/types'

/**
 * 权限字典（平台后台 · 治理组，§9.2）。只读投影自后端单一事实源（permission/role/role_permission）。
 *
 * 两个视角：
 * - 「权限点字典」：全量权限点，按 module/level/type 过滤检索；
 * - 「角色权限矩阵」：选一个角色，逐权限点标出该角色是否持有（✓/—），一眼看清角色授权面。
 *
 * 字典的编辑属 admin:rbac:write，本页不提供写入口，避免治理页误改事实源。
 */
const loading = ref(false)
const permissions = ref<PermissionVO[]>([])
const roles = ref<RoleVO[]>([])
const matrix = ref<RolePermissionMatrix>({})

const activeTab = ref('dict')
const keyword = ref('')
const levelFilter = ref('')
const typeFilter = ref('')
const selectedRole = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const [p, r, m] = await Promise.all([
      adminRbacApi.permissions(),
      adminRbacApi.roles(),
      adminRbacApi.matrix()
    ])
    permissions.value = p
    roles.value = r
    matrix.value = m
    // 默认选中第一个平台角色，其次第一个角色
    if (!selectedRole.value) {
      const firstPlatform = r.find((x) => x.level === 'PLATFORM')
      selectedRole.value = (firstPlatform ?? r[0])?.code ?? ''
    }
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(load)

/* ---------------- 权限点字典过滤 ---------------- */
const filteredPermissions = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return permissions.value.filter((p) => {
    if (levelFilter.value && p.level !== levelFilter.value) return false
    if (typeFilter.value && p.type !== typeFilter.value) return false
    if (!k) return true
    return (
      p.code.toLowerCase().includes(k) ||
      (p.name || '').toLowerCase().includes(k) ||
      (p.module || '').toLowerCase().includes(k) ||
      (p.description || '').toLowerCase().includes(k)
    )
  })
})

/** 当前选中角色持有的权限码集合（矩阵视角用 Set 提速）。 */
const selectedRoleCodes = computed<Set<string>>(() => new Set(matrix.value[selectedRole.value] ?? []))
const selectedRoleInfo = computed(() => roles.value.find((r) => r.code === selectedRole.value) ?? null)

/** 按 module 分组统计（顶部概览）。 */
const stats = computed(() => {
  const byLevel: Record<string, number> = {}
  const byType: Record<string, number> = {}
  permissions.value.forEach((p) => {
    const lv = p.level || '—'
    const tp = p.type || '—'
    byLevel[lv] = (byLevel[lv] || 0) + 1
    byType[tp] = (byType[tp] || 0) + 1
  })
  return { total: permissions.value.length, roleCount: roles.value.length, byLevel, byType }
})

const LEVELS = ['PLATFORM', 'TENANT', 'TEAM', 'RESOURCE']
const TYPES = ['MENU', 'BUTTON', 'API', 'DATA']

function levelTagType(level?: string | null): 'danger' | 'warning' | 'success' | 'info' {
  switch (level) {
    case 'PLATFORM':
      return 'danger'
    case 'TENANT':
      return 'warning'
    case 'TEAM':
      return 'success'
    default:
      return 'info'
  }
}
</script>

<template>
  <div class="rbac-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">权限字典</h2>
        <p class="ph-desc">
          平台权限体系的单一事实源：{{ stats.total }} 个权限点、{{ stats.roleCount }} 个内置角色。此处只读，编辑属更高权限。
        </p>
      </div>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
    </div>

    <div class="stat-row">
      <div class="stat-card card">
        <span class="sc-label">权限点</span>
        <span class="sc-value">{{ stats.total }}</span>
      </div>
      <div class="stat-card card">
        <span class="sc-label">角色</span>
        <span class="sc-value">{{ stats.roleCount }}</span>
      </div>
      <div v-for="(n, lv) in stats.byLevel" :key="lv" class="stat-card card">
        <span class="sc-label">{{ lv }} 权限点</span>
        <span class="sc-value">{{ n }}</span>
      </div>
    </div>

    <div class="card body-card">
      <el-tabs v-model="activeTab">
        <!-- ============ 权限点字典 ============ -->
        <el-tab-pane label="权限点字典" name="dict">
          <div class="filters">
            <el-input v-model="keyword" placeholder="搜索 code / 名称 / 模块 / 说明" clearable style="width: 280px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-select v-model="levelFilter" placeholder="全部层级" clearable style="width: 150px">
              <el-option v-for="lv in LEVELS" :key="lv" :label="lv" :value="lv" />
            </el-select>
            <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 150px">
              <el-option v-for="tp in TYPES" :key="tp" :label="tp" :value="tp" />
            </el-select>
            <span class="filter-count">共 {{ filteredPermissions.length }} 项</span>
          </div>

          <el-table v-loading="loading" :data="filteredPermissions" height="520" size="small" border stripe>
            <el-table-column prop="code" label="权限码" min-width="220" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="level" label="层级" width="110">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.level)" size="small" effect="light" round>{{ row.level || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="90">
              <template #default="{ row }"><el-tag size="small" effect="plain" round>{{ row.type || '—' }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="module" label="模块" width="120" show-overflow-tooltip />
            <el-table-column label="菜单/接口" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.menuCode" class="mono dim">menu:{{ row.menuCode }}</span>
                <span v-else-if="row.apiPattern" class="mono dim">{{ row.apiMethod || '' }} {{ row.apiPattern }}</span>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <!-- ============ 角色权限矩阵 ============ -->
        <el-tab-pane label="角色权限矩阵" name="matrix">
          <div class="filters">
            <el-select v-model="selectedRole" placeholder="选择角色" style="width: 260px">
              <el-option
                v-for="r in roles"
                :key="r.code"
                :label="`${r.name}（${r.code}）`"
                :value="r.code"
              />
            </el-select>
            <span v-if="selectedRoleInfo" class="role-meta">
              <el-tag :type="levelTagType(selectedRoleInfo.level)" size="small" effect="light" round>
                {{ selectedRoleInfo.level }}
              </el-tag>
              <el-tag size="small" effect="plain" round>数据范围 {{ selectedRoleInfo.dataScope || '—' }}</el-tag>
              <span class="dim">持有 {{ selectedRoleCodes.size }} 个权限点</span>
            </span>
          </div>
          <p v-if="selectedRoleInfo?.description" class="role-desc">{{ selectedRoleInfo.description }}</p>

          <el-table v-loading="loading" :data="permissions" height="500" size="small" border stripe>
            <el-table-column label="持有" width="70" align="center">
              <template #default="{ row }">
                <el-icon v-if="selectedRoleCodes.has(row.code)" class="yes"><CircleCheckFilled /></el-icon>
                <span v-else class="no">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="code" label="权限码" min-width="220" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="level" label="层级" width="110">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.level)" size="small" effect="light" round>{{ row.level || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="90">
              <template #default="{ row }"><el-tag size="small" effect="plain" round>{{ row.type || '—' }}</el-tag></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.rbac-view {
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
.role-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.role-desc {
  margin: 0 0 12px;
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
.yes {
  color: var(--el-color-success);
  font-size: 16px;
}
.no {
  color: var(--text-placeholder);
}
</style>
