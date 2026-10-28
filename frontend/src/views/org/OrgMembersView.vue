<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orgMemberApi } from '@/api/orgMember'
import { orgCustomRoleApi } from '@/api/orgCustomRole'
import { usePermissionStore } from '@/stores/permission'
import { formatDateTime } from '@/utils/format'
import type { CustomRoleVO, MemberCustomRole, MemberVO } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 组织成员（§9.2 组织设置 · 成员，写侧治理）。
 *
 * 权限码 member:member:read（组织矩阵 YYNNNN → 仅 OWNER/ADMIN 可见此页）。租户范围由后端从登录上下文
 * 强制收敛，无法操作他组织成员。所有护栏（不可移除/回收自己、不可越级、最后一个 OWNER、OWNER 须先转让
 * 才能退出）在后端强制，前端仅对最显然的自锁预置禁用 + 呈现错误。
 *
 * 「退出组织」对全组织角色开放（member:self:leave，矩阵 NYYYYY），故不受本页 OWNER/ADMIN 门禁限制；
 * 但本页仅 OWNER/ADMIN 可达，普通成员的退出入口在个人中心。
 */
const perm = usePermissionStore()

/* 组织角色字典（与后端 OrgRole 对齐；OWNER 不可直接授予/回收，只能经转让产生）。 */
const ORG_ROLES = [
  { code: 'OWNER', label: '组织所有者', tag: 'danger' as const },
  { code: 'ADMIN', label: '组织管理员', tag: 'warning' as const },
  { code: 'DATA_STEWARD', label: '数据管理员', tag: 'success' as const },
  { code: 'ANALYST', label: '分析师', tag: 'primary' as const },
  { code: 'VIEWER', label: '只读成员', tag: 'info' as const },
  { code: 'BILLING', label: '计费联系人', tag: 'info' as const }
]
/** 可授予的角色（排除 OWNER）。 */
const GRANTABLE_ROLES = ORG_ROLES.filter((r) => r.code !== 'OWNER')

function roleLabel(code: string): string {
  return ORG_ROLES.find((r) => r.code === code)?.label || code
}
function roleTag(code: string): 'danger' | 'warning' | 'success' | 'primary' | 'info' {
  return ORG_ROLES.find((r) => r.code === code)?.tag || 'info'
}

const loading = ref(false)
const members = ref<MemberVO[]>([])
/** 本组织自定义角色（供「授予自定义角色」下拉；P1 自定义角色引擎）。 */
const customRoles = ref<CustomRoleVO[]>([])
const keyword = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const [ms, cs] = await Promise.all([
      orgMemberApi.list(),
      orgCustomRoleApi.list().catch(() => [] as CustomRoleVO[])
    ])
    members.value = ms ?? []
    customRoles.value = cs ?? []
  } catch {
    members.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return members.value
  return members.value.filter(
    (m) =>
      (m.username || '').toLowerCase().includes(k) ||
      (m.nickname || '').toLowerCase().includes(k) ||
      (m.email || '').toLowerCase().includes(k) ||
      (m.position || '').toLowerCase().includes(k) ||
      m.orgRoles.some((r) => r.toLowerCase().includes(k))
  )
})

const stats = computed(() => {
  const byRole: Record<string, number> = {}
  members.value.forEach((m) => m.orgRoles.forEach((r) => (byRole[r] = (byRole[r] || 0) + 1)))
  return { total: members.value.length, byRole }
})

/** 是否可对该成员执行管理操作：非本人。OWNER 行仅可经转让变更，不可移除/回收/降级。 */
function canOperate(m: MemberVO): boolean {
  return !m.self && !m.owner
}

/* ---------------- 分配角色对话框（内置角色 / 自定义角色） ---------------- */
const roleVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const roleForm = reactive<{ uid: number | null; roleCode: string; customRoleId: number | null }>({
  uid: null,
  roleCode: '',
  customRoleId: null
})
/** 授予模式：BUILTIN=内置组织角色；CUSTOM=本组织自定义角色（roleCode 取其 base 码）。 */
const grantMode = ref<'BUILTIN' | 'CUSTOM'>('BUILTIN')
const roleTarget = ref<MemberVO | null>(null)

/** 该成员尚未持有的可授予内置角色。 */
const availableBuiltinRoles = computed(() =>
  GRANTABLE_ROLES.filter((r) => !(roleTarget.value?.orgRoles ?? []).includes(r.code))
)
/** 该成员尚未持有的自定义角色。 */
const availableCustomRoles = computed(() => {
  const held = new Set((roleTarget.value?.customRoles ?? []).map((c) => c.id))
  return customRoles.value.filter((r) => !held.has(r.id))
})

function openAssign(m: MemberVO): void {
  roleTarget.value = m
  roleForm.uid = m.uid
  grantMode.value = 'BUILTIN'
  roleForm.roleCode = availableBuiltinRoles.value[0]?.code ?? GRANTABLE_ROLES[0].code
  roleForm.customRoleId = availableCustomRoles.value[0]?.id ?? null
  roleVisible.value = true
}

/** 切换授予模式时重置选择，避免把上一模式的残留值提交出去。 */
function onGrantModeChange(): void {
  if (grantMode.value === 'BUILTIN') {
    roleForm.roleCode = availableBuiltinRoles.value[0]?.code ?? GRANTABLE_ROLES[0].code
    roleForm.customRoleId = null
  } else {
    roleForm.customRoleId = availableCustomRoles.value[0]?.id ?? null
  }
}

async function submitAssign(): Promise<void> {
  if (roleForm.uid == null) {
    ElMessage.warning('请选择成员')
    return
  }
  let payload: { uid: number; roleCode: string; customRoleId?: number | null }
  if (grantMode.value === 'CUSTOM') {
    const cr = customRoles.value.find((r) => r.id === roleForm.customRoleId)
    if (!cr) {
      ElMessage.warning('请选择自定义角色')
      return
    }
    payload = { uid: roleForm.uid, roleCode: cr.baseRoleCode, customRoleId: cr.id }
  } else {
    if (!roleForm.roleCode) {
      ElMessage.warning('请选择角色')
      return
    }
    payload = { uid: roleForm.uid, roleCode: roleForm.roleCode, customRoleId: null }
  }
  await runSubmit(async () => {
    try {
      await orgMemberApi.assignRole(payload)
      ElMessage.success('已授予角色')
      roleVisible.value = false
      await load()
    } catch {
      // 拦截器已提示（含后端越级/重复护栏）
    }
  })
}

/** 回收自定义角色授予（按 customRoleId 精确回收，不动同 base 码的内置授予）。 */
async function onRevokeCustomRole(m: MemberVO, cr: MemberCustomRole): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定回收「${m.nickname || m.username || m.uid}」的自定义角色「${cr.name}」吗？回收后其下一次请求即失去对应权限。`,
      '回收自定义角色',
      { type: 'warning', confirmButtonText: '回收', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await runSubmit(async () => {
    try {
      await orgMemberApi.revokeRole(m.uid, cr.baseRoleCode, cr.id)
      ElMessage.success('已回收自定义角色')
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

async function onRevokeRole(m: MemberVO, roleCode: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定回收「${m.nickname || m.username || m.uid}」的角色「${roleLabel(roleCode)}」吗？回收后其下一次请求即失去对应权限。`,
      '回收角色',
      { type: 'warning', confirmButtonText: '回收', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await orgMemberApi.revokeRole(m.uid, roleCode)
    ElMessage.success('已回收')
    await load()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 移除成员 ---------------- */
async function onRemove(m: MemberVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定将「${m.nickname || m.username || m.uid}」移出组织吗？其账号将被停用、全部会话立即下线并释放一个席位。`,
      '移除成员',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await orgMemberApi.remove(m.uid)
    ElMessage.success('已移除')
    await load()
  } catch {
    // 拦截器已提示（含后端最后一个 OWNER 护栏）
  }
}

/* ---------------- 重置口令对话框 ---------------- */
const pwdVisible = ref(false)
const pwdForm = reactive<{ uid: number | null; newPassword: string }>({ uid: null, newPassword: '' })
const pwdTarget = ref<MemberVO | null>(null)

function openResetPwd(m: MemberVO): void {
  pwdTarget.value = m
  pwdForm.uid = m.uid
  pwdForm.newPassword = ''
  pwdVisible.value = true
}

async function submitResetPwd(): Promise<void> {
  if (pwdForm.uid == null) return
  if (pwdForm.newPassword.length < 6 || pwdForm.newPassword.length > 64) {
    ElMessage.warning('口令长度需在 6-64 位之间')
    return
  }
  await runSubmit(async () => {
    try {

      await orgMemberApi.resetPassword(pwdForm.uid!, { newPassword: pwdForm.newPassword })
      ElMessage.success('口令已重置，该成员须重新登录')
      pwdVisible.value = false
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 转让所有权对话框（仅 OWNER） ---------------- */
const transferVisible = ref(false)
const transferUid = ref<number | null>(null)

function openTransfer(): void {
  transferUid.value = null
  transferVisible.value = true
}

async function submitTransfer(): Promise<void> {
  if (transferUid.value == null) {
    ElMessage.warning('请选择接任所有者')
    return
  }
  try {
    await ElMessageBox.confirm(
      '转让后你将降为组织管理员，目标成员升为所有者。全组织成员的登录态将失效并需重新登录。确定继续？',
      '转让组织所有权',
      { type: 'warning', confirmButtonText: '确认转让', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await runSubmit(async () => {
    try {

      await orgMemberApi.transfer({ targetUid: transferUid.value! })
      ElMessage.success('所有权已转让')
      transferVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 退出组织（本人） ---------------- */
async function onLeave(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '退出后你的账号将在本组织内被停用、全部会话立即下线。若你是所有者须先转让所有权。确定退出？',
      '退出组织',
      { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await orgMemberApi.leave()
    ElMessage.success('已退出组织')
    window.location.href = '/login?reason=left'
  } catch {
    // 拦截器已提示（含 OWNER_TRANSFER_REQUIRED）
  }
}
</script>

<template>
  <div class="members-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">组织成员</h2>
        <p class="ph-desc">
          管理本组织在册成员：分配/回收角色、重置口令、移除成员、转让所有权。共 {{ stats.total }} 名成员，敏感变更即时生效。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button v-if="perm.has('org:org:transfer')" type="warning" @click="openTransfer">
          <el-icon><Switch /></el-icon> 转让所有权
        </el-button>
        <el-button v-if="perm.has('member:self:leave')" type="danger" plain @click="onLeave">
          <el-icon><Back /></el-icon> 退出组织
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card card">
        <span class="sc-label">成员总数</span>
        <span class="sc-value">{{ stats.total }}</span>
      </div>
      <div v-for="(n, code) in stats.byRole" :key="code" class="stat-card card">
        <span class="sc-label">{{ roleLabel(code as string) }}</span>
        <span class="sc-value">{{ n }}</span>
      </div>
    </div>

    <div class="card body-card">
      <div class="filters">
        <el-input v-model="keyword" placeholder="搜索账号 / 昵称 / 邮箱 / 职位 / 角色" clearable style="width: 320px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <span class="filter-count">共 {{ filtered.length }} 名</span>
      </div>

      <el-table v-loading="loading" :data="filtered" height="520" size="small" border stripe>
        <el-table-column prop="username" label="账号" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.username || '—' }}</span>
            <el-tag v-if="row.self" size="small" type="info" effect="plain" round class="self-tag">本人</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nickname || '—' }}</template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column prop="position" label="职位" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.position || '—' }}</template>
        </el-table-column>
        <el-table-column label="组织角色" min-width="240">
          <template #default="{ row }">
            <el-tag
              v-for="r in row.orgRoles"
              :key="'b-' + r"
              :type="roleTag(r)"
              size="small"
              effect="light"
              round
              class="role-tag"
              :closable="canOperate(row) && r !== 'OWNER' && perm.has('member:role:assign')"
              @close="onRevokeRole(row, r)"
            >
              {{ roleLabel(r) }}
            </el-tag>
            <el-tag
              v-for="cr in (row.customRoles || [])"
              :key="'c-' + cr.id"
              type="warning"
              size="small"
              effect="plain"
              round
              class="role-tag custom-role-tag"
              :closable="canOperate(row) && perm.has('member:role:assign')"
              @close="onRevokeCustomRole(row, cr)"
            >
              自定义 · {{ cr.name }}
            </el-tag>
            <span v-if="!row.orgRoles.length && !(row.customRoles && row.customRoles.length)" class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最近登录" width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="perm.has('member:role:assign')"
              type="primary"
              link
              size="small"
              :disabled="!canOperate(row)"
              @click="openAssign(row)"
            >
              分配角色
            </el-button>
            <el-button
              v-if="perm.has('member:password:reset')"
              type="warning"
              link
              size="small"
              @click="openResetPwd(row)"
            >
              重置口令
            </el-button>
            <el-tooltip :disabled="canOperate(row)" content="不能移除自己或组织所有者" placement="top">
              <span>
                <el-button
                  v-if="perm.has('member:member:delete')"
                  type="danger"
                  link
                  size="small"
                  :disabled="!canOperate(row)"
                  @click="onRemove(row)"
                >
                  移除
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无成员" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- ============ 分配角色对话框（内置角色 / 自定义角色） ============ -->
    <el-dialog v-model="roleVisible" title="分配组织角色" width="480px" :close-on-click-modal="false">
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="成员">
          <span>{{ roleTarget?.nickname || roleTarget?.username }}（#{{ roleTarget?.uid }}）</span>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="grantMode" @change="onGrantModeChange">
            <el-radio-button value="BUILTIN">内置组织角色</el-radio-button>
            <el-radio-button value="CUSTOM">本组织自定义角色</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="grantMode === 'BUILTIN'" label="角色">
          <el-select v-model="roleForm.roleCode" placeholder="选择角色" style="width: 100%">
            <el-option
              v-for="r in GRANTABLE_ROLES"
              :key="r.code"
              :label="`${r.label}（${r.code}）`"
              :value="r.code"
              :disabled="roleTarget?.orgRoles.includes(r.code)"
            />
          </el-select>
          <p class="form-hint dim">已持有的角色置灰；OWNER 只能经「转让所有权」产生，不可直接授予。</p>
        </el-form-item>
        <el-form-item v-else label="自定义角色">
          <el-select v-model="roleForm.customRoleId" placeholder="选择自定义角色" style="width: 100%">
            <el-option
              v-for="cr in availableCustomRoles"
              :key="cr.id"
              :label="`${cr.name}（基于 ${roleLabel(cr.baseRoleCode)}）`"
              :value="cr.id"
            />
          </el-select>
          <p class="form-hint dim">
            自定义角色仅在「基于角色」权限范围内进一步收窄；授予后成员即时获得对应权限，回收按角色精确执行。
            <template v-if="!availableCustomRoles.length">（本组织暂无可用自定义角色，或该成员已全部持有）</template>
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAssign">授予</el-button>
      </template>
    </el-dialog>

    <!-- ============ 重置口令对话框 ============ -->
    <el-dialog v-model="pwdVisible" title="重置成员口令" width="440px" :close-on-click-modal="false">
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="成员">
          <span>{{ pwdTarget?.nickname || pwdTarget?.username }}（#{{ pwdTarget?.uid }}）</span>
        </el-form-item>
        <el-form-item label="新口令">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="6-64 位" />
          <p class="form-hint dim">重置后该成员全部会话立即下线，须用新口令重新登录。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitResetPwd">重置</el-button>
      </template>
    </el-dialog>

    <!-- ============ 转让所有权对话框 ============ -->
    <el-dialog v-model="transferVisible" title="转让组织所有权" width="460px" :close-on-click-modal="false">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="接任所有者">
          <el-select v-model="transferUid" filterable placeholder="选择在册成员" style="width: 100%">
            <el-option
              v-for="m in members.filter((x) => !x.owner)"
              :key="m.uid"
              :label="`${m.nickname || m.username}（${m.username}）`"
              :value="m.uid"
            />
          </el-select>
          <p class="form-hint dim">转让后你降为组织管理员，全组织成员须重新登录。此操作不可自助撤销。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="warning" :loading="submitting" @click="submitTransfer">确认转让</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.members-view {
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
.role-tag {
  margin: 2px 6px 2px 0;
}
.custom-role-tag {
  border-style: dashed;
}
.form-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
}
</style>
