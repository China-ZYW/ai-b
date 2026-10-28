<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminPlatformRoleApi } from '@/api/adminPlatformRole'
import { adminRbacApi } from '@/api/adminRbac'
import { adminUserApi } from '@/api/admin'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/format'
import type { AdminUserVO, PlatformRoleHolderVO, RoleVO } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 平台角色（平台后台 · 治理组，§9.2，唯一写侧治理页）。
 *
 * 授予/回收平台账号（tenant_id=0）的平台角色（SUPER_ADMIN/OPERATOR/AUDITOR/SUPPORT）。
 * 权限码 admin:role:assign（矩阵 YNNN，仅 SUPER_ADMIN 可见此页）。护栏（不可回收自己/最后一个
 * SUPER_ADMIN）在后端强制，前端仅呈现错误 + 对"自己的 SUPER_ADMIN"预置禁用避免无谓请求。
 */
const userStore = useUserStore()
const currentUid = computed(() => userStore.profile?.id ?? null)

const loading = ref(false)
const holders = ref<PlatformRoleHolderVO[]>([])
const platformRoles = ref<RoleVO[]>([])
const candidates = ref<AdminUserVO[]>([])
const keyword = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const [h, roles, users] = await Promise.all([
      adminPlatformRoleApi.holders(),
      adminRbacApi.roles(),
      adminUserApi.list(0)
    ])
    holders.value = h
    platformRoles.value = roles.filter((r) => r.level === 'PLATFORM')
    candidates.value = users
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(load)

const filteredHolders = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return holders.value
  return holders.value.filter(
    (h) =>
      (h.username || '').toLowerCase().includes(k) ||
      (h.nickname || '').toLowerCase().includes(k) ||
      (h.email || '').toLowerCase().includes(k) ||
      h.roleCode.toLowerCase().includes(k)
  )
})

/** 按角色统计持有者数（顶部概览）。 */
const stats = computed(() => {
  const byRole: Record<string, number> = {}
  holders.value.forEach((h) => {
    byRole[h.roleCode] = (byRole[h.roleCode] || 0) + 1
  })
  return { total: holders.value.length, byRole }
})

function roleName(code: string): string {
  return platformRoles.value.find((r) => r.code === code)?.name || code
}

function roleTagType(code: string): 'danger' | 'warning' | 'success' | 'info' {
  switch (code) {
    case 'SUPER_ADMIN':
      return 'danger'
    case 'OPERATOR':
      return 'warning'
    case 'SUPPORT':
      return 'success'
    default:
      return 'info'
  }
}

/** 是否不可回收：自己的 SUPER_ADMIN（后端另有"最后一个"护栏，此处仅拦最显然的自锁）。 */
function isSelfSuperAdmin(h: PlatformRoleHolderVO): boolean {
  return h.roleCode === 'SUPER_ADMIN' && h.uid === currentUid.value
}

/* ---------------- 授予对话框 ---------------- */
const grantVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const grantForm = reactive<{ uid: number | null; roleCode: string }>({ uid: null, roleCode: '' })

/** 已持有映射：uid → 该账号已持有的平台角色码集合，用于置灰重复选项。 */
const heldByUid = computed<Map<number, Set<string>>>(() => {
  const m = new Map<number, Set<string>>()
  holders.value.forEach((h) => {
    if (!m.has(h.uid)) m.set(h.uid, new Set())
    m.get(h.uid)!.add(h.roleCode)
  })
  return m
})

function alreadyHeld(uid: number | null, roleCode: string): boolean {
  if (uid == null || !roleCode) return false
  return heldByUid.value.get(uid)?.has(roleCode) ?? false
}

function openGrant(): void {
  grantForm.uid = null
  grantForm.roleCode = platformRoles.value[0]?.code ?? ''
  grantVisible.value = true
}

async function submitGrant(): Promise<void> {
  if (grantForm.uid == null) {
    ElMessage.warning('请选择被授权账号')
    return
  }
  if (!grantForm.roleCode) {
    ElMessage.warning('请选择平台角色')
    return
  }
  await runSubmit(async () => {
    try {

      await adminPlatformRoleApi.grant({ uid: grantForm.uid!, roleCode: grantForm.roleCode })
      ElMessage.success('已授予平台角色')
      grantVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

async function onRevoke(h: PlatformRoleHolderVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定回收「${h.username || h.uid}」的平台角色「${roleName(h.roleCode)}」吗？回收后该账号下一次请求即失去对应权限。`,
      '回收平台角色',
      { type: 'warning', confirmButtonText: '回收', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminPlatformRoleApi.revoke(h.id)
    ElMessage.success('已回收')
    await load()
  } catch {
    // 拦截器已提示（含后端护栏：不可回收最后一个 SUPER_ADMIN 等）
  }
}
</script>

<template>
  <div class="proles-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">平台角色</h2>
        <p class="ph-desc">
          授予/回收平台账号的平台角色（仅超级管理员可操作）。共 {{ stats.total }} 条授予，回收即时生效。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button type="primary" @click="openGrant"><el-icon><Plus /></el-icon> 授予角色</el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card card">
        <span class="sc-label">授予总数</span>
        <span class="sc-value">{{ stats.total }}</span>
      </div>
      <div v-for="(n, code) in stats.byRole" :key="code" class="stat-card card">
        <span class="sc-label">{{ roleName(code as string) }}</span>
        <span class="sc-value">{{ n }}</span>
      </div>
    </div>

    <div class="card body-card">
      <div class="filters">
        <el-input v-model="keyword" placeholder="搜索账号 / 昵称 / 邮箱 / 角色" clearable style="width: 300px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <span class="filter-count">共 {{ filteredHolders.length }} 条</span>
      </div>

      <el-table v-loading="loading" :data="filteredHolders" height="520" size="small" border stripe>
        <el-table-column prop="username" label="账号" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.username || '—' }}</span>
            <el-tag v-if="isSelfSuperAdmin(row)" size="small" type="info" effect="plain" round class="self-tag">本人</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nickname || '—' }}</template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column prop="roleCode" label="平台角色" width="170">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.roleCode)" size="small" effect="light" round>
              {{ roleName(row.roleCode) }}
            </el-tag>
            <span class="mono dim role-code">{{ row.roleCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="grantedAt" label="授予时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.grantedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!isSelfSuperAdmin(row)"
              content="不能回收自己的超级管理员角色"
              placement="top"
            >
              <span>
                <el-button
                  type="danger"
                  link
                  size="small"
                  :disabled="isSelfSuperAdmin(row)"
                  @click="onRevoke(row)"
                >
                  回收
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无平台角色授予" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- ============ 授予对话框 ============ -->
    <el-dialog v-model="grantVisible" title="授予平台角色" width="480px" :close-on-click-modal="false">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="平台账号">
          <el-select v-model="grantForm.uid" filterable placeholder="选择平台账号（tenant_id=0）" style="width: 100%">
            <el-option
              v-for="u in candidates"
              :key="u.id"
              :label="`${u.nickname || u.username}（${u.username}）`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="平台角色">
          <el-select v-model="grantForm.roleCode" placeholder="选择平台角色" style="width: 100%">
            <el-option
              v-for="r in platformRoles"
              :key="r.code"
              :label="`${r.name}（${r.code}）`"
              :value="r.code"
              :disabled="alreadyHeld(grantForm.uid, r.code)"
            />
          </el-select>
          <p v-if="grantForm.uid != null" class="form-hint dim">
            已持有的角色将被置灰；平台角色仅对平台账号生效。
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="grantVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitGrant">授予</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.proles-view {
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
.mono {
  font-family: 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 12.5px;
}
.dim {
  color: var(--text-placeholder);
}
.self-tag {
  margin-left: 8px;
}
.role-code {
  margin-left: 8px;
}
.form-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
}
</style>
