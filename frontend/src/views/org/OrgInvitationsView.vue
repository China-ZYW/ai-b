<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orgInvitationApi } from '@/api/orgInvitation'
import { usePermissionStore } from '@/stores/permission'
import { formatDateTime } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import type { InvitationVO } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 组织邀请（§9.2 组织设置 · 邀请，管理侧 create/list/revoke）。
 *
 * 权限码 member:invite:*（组织矩阵 YYNNNN → 仅 OWNER/ADMIN 可见此页）。令牌明文仅创建后返回一次，
 * 服务端只存其 SHA-256 哈希；列表/详情绝不含令牌。撤销即时失效。接受链路（凭链接注册入组）本轮未开放。
 * 护栏（OWNER 不可经邀请授予、不可邀请越级角色、seats 上限）在后端强制，前端仅呈现错误。
 */
const perm = usePermissionStore()

/* 可邀请的组织角色（排除 OWNER，其只能经转让产生）。 */
const GRANTABLE_ROLES = [
  { code: 'ADMIN', label: '组织管理员' },
  { code: 'DATA_STEWARD', label: '数据管理员' },
  { code: 'ANALYST', label: '分析师' },
  { code: 'VIEWER', label: '只读成员' },
  { code: 'BILLING', label: '计费联系人' }
]
const CHANNELS = [
  { value: 'LINK', label: '链接邀请' },
  { value: 'EMAIL', label: '定向邮箱' }
]

function roleLabel(code?: string | null): string {
  if (!code) return '—'
  return GRANTABLE_ROLES.find((r) => r.code === code)?.label || (code === 'OWNER' ? '组织所有者' : code)
}
function channelLabel(c?: string | null): string {
  return CHANNELS.find((o) => o.value === c)?.label || c || '—'
}
function statusLabel(s?: string | null): string {
  switch (s) {
    case 'SENT':
      return '已发出'
    case 'PENDING':
      return '待审批'
    case 'ACCEPTED':
      return '已接受'
    case 'EXPIRED':
      return '已过期'
    case 'REVOKED':
      return '已撤销'
    default:
      return s || '—'
  }
}
function statusTag(s?: string | null): 'success' | 'warning' | 'info' | 'danger' {
  switch (s) {
    case 'SENT':
      return 'success'
    case 'PENDING':
      return 'warning'
    case 'ACCEPTED':
      return 'info'
    case 'REVOKED':
    case 'EXPIRED':
      return 'danger'
    default:
      return 'info'
  }
}
/** 仅已发出/待审批的邀请可撤销（与后端护栏一致）。 */
function canRevoke(inv: InvitationVO): boolean {
  return inv.status === 'SENT' || inv.status === 'PENDING'
}

const loading = ref(false)
const rows = ref<InvitationVO[]>([])
const keyword = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await orgInvitationApi.list()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter(
    (r) =>
      (r.roleCode || '').toLowerCase().includes(k) ||
      (r.targetEmail || '').toLowerCase().includes(k) ||
      (r.allowDomain || '').toLowerCase().includes(k) ||
      (r.status || '').toLowerCase().includes(k)
  )
})

const stats = computed(() => {
  let active = 0
  let revoked = 0
  rows.value.forEach((r) => {
    if (canRevoke(r)) active++
    else if (r.status === 'REVOKED') revoked++
  })
  return { total: rows.value.length, active, revoked }
})

/* ---------------- 创建邀请对话框 ---------------- */
const createVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const form = reactive<{
  channel: string
  roleCode: string
  targetEmail: string
  allowDomain: string
  seats: number | null
  expireDays: number | null
  requireApproval: number
  message: string
}>({
  channel: 'LINK',
  roleCode: 'VIEWER',
  targetEmail: '',
  allowDomain: '',
  seats: 1,
  expireDays: 7,
  requireApproval: 0,
  message: ''
})

function openCreate(): void {
  form.channel = 'LINK'
  form.roleCode = 'VIEWER'
  form.targetEmail = ''
  form.allowDomain = ''
  form.seats = 1
  form.expireDays = 7
  form.requireApproval = 0
  form.message = ''
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!form.roleCode) {
    ElMessage.warning('请选择角色')
    return
  }
  await runSubmit(async () => {
    try {

      const result = await orgInvitationApi.create({
        channel: form.channel,
        roleCode: form.roleCode,
        targetEmail: form.targetEmail || undefined,
        allowDomain: form.allowDomain || undefined,
        seats: form.seats ?? undefined,
        expireDays: form.expireDays ?? undefined,
        requireApproval: form.requireApproval,
        message: form.message || undefined
      })
      createVisible.value = false
      await load()
      showLink(result.inviteUrl, result.token)
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 邀请链接结果（明文令牌仅此一次） ---------------- */
const linkVisible = ref(false)
const linkUrl = ref('')

function showLink(inviteUrl: string, _token: string): void {
  linkUrl.value = `${window.location.origin}${inviteUrl}`
  linkVisible.value = true
}

async function copyLink(): Promise<void> {
  const ok = await copyText(linkUrl.value)
  if (ok) ElMessage.success('邀请链接已复制')
  else ElMessage.warning('复制失败，请手动选择文本复制')
}

/* ---------------- 撤销 ---------------- */
async function onRevoke(inv: InvitationVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定撤销这条「${roleLabel(inv.roleCode)}」邀请吗？撤销后其令牌立即失效，无法再被接受。`,
      '撤销邀请',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await orgInvitationApi.revoke(inv.id)
    ElMessage.success('已撤销')
    await load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="invites-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">组织邀请</h2>
        <p class="ph-desc">
          发起邀请链接吸纳新成员，可设定角色、席位数与有效期。令牌只存哈希、明文仅生成时展示一次，可随时撤销。
        </p>
      </div>
      <div class="ph-actions">
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon> 刷新</el-button>
        <el-button v-if="perm.has('member:invite:create')" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon> 发起邀请
        </el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card card">
        <span class="sc-label">邀请总数</span>
        <span class="sc-value">{{ stats.total }}</span>
      </div>
      <div class="stat-card card">
        <span class="sc-label">生效中</span>
        <span class="sc-value">{{ stats.active }}</span>
      </div>
      <div class="stat-card card">
        <span class="sc-label">已撤销</span>
        <span class="sc-value">{{ stats.revoked }}</span>
      </div>
    </div>

    <div class="card body-card">
      <div class="filters">
        <el-input v-model="keyword" placeholder="搜索角色 / 邮箱 / 域名 / 状态" clearable style="width: 300px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <span class="filter-count">共 {{ filtered.length }} 条</span>
      </div>

      <el-table v-loading="loading" :data="filtered" height="520" size="small" border stripe>
        <el-table-column label="角色" min-width="130">
          <template #default="{ row }">
            <span>{{ roleLabel(row.roleCode) }}</span>
            <span class="mono dim role-code">{{ row.roleCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="110">
          <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
        </el-table-column>
        <el-table-column label="限定" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.targetEmail" class="mono">{{ row.targetEmail }}</span>
            <span v-else-if="row.allowDomain" class="mono">@{{ row.allowDomain }}</span>
            <span v-else class="dim">不限</span>
          </template>
        </el-table-column>
        <el-table-column label="席位" width="90" align="center">
          <template #default="{ row }">
            {{ row.usedCount ?? 0 }} / {{ row.seats != null && row.seats < 0 ? '∞' : row.seats }}
          </template>
        </el-table-column>
        <el-table-column label="审批" width="80" align="center">
          <template #default="{ row }">{{ row.requireApproval === 1 ? '需要' : '免审' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small" effect="light" round>
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.expireAt) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-tooltip :disabled="canRevoke(row)" content="仅生效中的邀请可撤销" placement="top">
              <span>
                <el-button
                  v-if="perm.has('member:invite:revoke')"
                  type="danger"
                  link
                  size="small"
                  :disabled="!canRevoke(row)"
                  @click="onRevoke(row)"
                >
                  撤销
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无邀请" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- ============ 发起邀请对话框 ============ -->
    <el-dialog v-model="createVisible" title="发起邀请" width="520px" :close-on-click-modal="false">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="授予角色">
          <el-select v-model="form.roleCode" placeholder="选择角色" style="width: 100%">
            <el-option v-for="r in GRANTABLE_ROLES" :key="r.code" :label="`${r.label}（${r.code}）`" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道">
          <el-radio-group v-model="form.channel">
            <el-radio v-for="c in CHANNELS" :key="c.value" :value="c.value">{{ c.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.channel === 'EMAIL'" label="定向邮箱">
          <el-input v-model="form.targetEmail" placeholder="仅该邮箱可接受" />
        </el-form-item>
        <el-form-item v-else label="限定邮箱域">
          <el-input v-model="form.allowDomain" placeholder="如 example.com（留空=不限）" />
        </el-form-item>
        <el-form-item label="席位数">
          <el-input-number v-model="form.seats" :min="1" :max="50" />
          <span class="form-hint dim inline">可接受次数上限（1-50）。</span>
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="form.expireDays" :min="1" :max="365" />
        </el-form-item>
        <el-form-item label="需审批">
          <el-switch v-model="form.requireApproval" :active-value="1" :inactive-value="0" />
          <span class="form-hint dim inline">开启后成员接受邀请需管理员审批方可入组。</span>
        </el-form-item>
        <el-form-item label="邀请附言">
          <el-input v-model="form.message" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">生成邀请</el-button>
      </template>
    </el-dialog>

    <!-- ============ 邀请链接结果（明文令牌仅此一次） ============ -->
    <el-dialog v-model="linkVisible" title="邀请已生成" width="560px" :close-on-click-modal="false">
      <p class="form-hint">复制下方链接分发给受邀人。令牌明文仅此一次展示，关闭后无法再查看，请妥善保存。</p>
      <el-input v-model="linkUrl" readonly type="textarea" :rows="3" class="link-box mono" />
      <template #footer>
        <el-button @click="linkVisible = false">关闭</el-button>
        <el-button type="primary" @click="copyLink"><el-icon><DocumentCopy /></el-icon> 复制链接</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.invites-view {
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
.role-code {
  margin-left: 8px;
}
.form-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
}
.form-hint.inline {
  margin: 0 0 0 10px;
}
.link-box {
  margin-top: 10px;
}
</style>
