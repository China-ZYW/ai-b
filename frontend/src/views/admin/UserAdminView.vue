<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { adminTenantApi, adminUserApi } from '@/api/admin'
import type { AdminUserForm, AdminUserVO, TenantVO } from '@/types'
import { useActionGuard, useRowBusy } from '@/utils/actionGuard'
import { formatDateTime } from '@/utils/format'

/**
 * 账号管理（超管后台）：先选租户，再对该租户下账号 CRUD / 重置密码 / 启停。
 * 选择“全平台”可跨租户查看全部账号。密码只写不读（响应不含哈希）。
 */
const loading = ref(false)
const tenants = ref<TenantVO[]>([])
const users = ref<AdminUserVO[]>([])
const tenantFilter = ref<number | 'ALL'>('ALL')
const keyword = ref('')

async function loadTenants(): Promise<void> {
  try {
    tenants.value = await adminTenantApi.list()
  } catch {
    // 拦截器已提示
  }
}

async function loadUsers(): Promise<void> {
  loading.value = true
  try {
    const tid = tenantFilter.value === 'ALL' ? null : tenantFilter.value
    users.value = await adminUserApi.list(tid)
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadTenants()
  await loadUsers()
})

function tenantName(id?: number | null): string {
  if (id == null) return '—'
  if (id === 0) return '平台（超管）'
  return tenants.value.find((t) => t.id === id)?.name || `#${id}`
}

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return users.value
  return users.value.filter(
    (u) =>
      u.username.toLowerCase().includes(k) ||
      (u.nickname || '').toLowerCase().includes(k) ||
      (u.email || '').toLowerCase().includes(k)
  )
})

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
// 保存 / 重置密码 / 删除：同一时刻只应有一次（连点重入由护栏早退）
const { busy: submitting, run: runSubmit } = useActionGuard()
const { run: runRemove } = useActionGuard()
const formRef = ref<FormInstance>()
const isEdit = computed(() => editingId.value != null)

interface UserFormState {
  tenantId: number | null
  username: string
  password: string
  nickname: string
  email: string
  role: string
  status: number
}
const emptyForm = (): UserFormState => ({
  tenantId: null,
  username: '',
  password: '',
  nickname: '',
  email: '',
  role: 'USER',
  status: 1
})
const form = reactive<UserFormState>(emptyForm())

const rules = computed<FormRules>(() => ({
  tenantId: [{ required: true, message: '请选择所属租户', trigger: 'change' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: isEdit.value
    ? []
    : [
        { required: true, message: '请输入初始密码', trigger: 'blur' },
        { min: 6, max: 64, message: '密码长度需在 6-64 位之间', trigger: 'blur' }
      ]
}))

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  // 默认落到当前筛选的租户
  if (tenantFilter.value !== 'ALL') form.tenantId = tenantFilter.value
  drawerVisible.value = true
}

function openEdit(u: AdminUserVO): void {
  editingId.value = u.id
  Object.assign(form, {
    tenantId: u.tenantId ?? null,
    username: u.username,
    password: '',
    nickname: u.nickname || '',
    email: u.email || '',
    role: u.role || 'USER',
    status: u.status ?? 1
  })
  drawerVisible.value = true
}

function buildPayload(): AdminUserForm {
  return {
    tenantId: form.tenantId,
    username: form.username.trim(),
    password: form.password.trim() || null,
    nickname: form.nickname.trim() || null,
    email: form.email.trim() || null,
    role: form.role,
    status: form.status
  }
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  await runSubmit(async () => {
    try {
      if (isEdit.value && editingId.value != null) {
        await adminUserApi.update(editingId.value, buildPayload())
        ElMessage.success('账号已更新')
      } else {
        await adminUserApi.create(buildPayload())
        ElMessage.success('账号已创建')
      }
      drawerVisible.value = false
      await loadUsers()
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 重置密码 ---------------- */
const pwdVisible = ref(false)
const pwdTarget = ref<AdminUserVO | null>(null)
const { busy: pwdSubmitting, run: runPwdSubmit } = useActionGuard()
const pwdForm = reactive({ password: '', confirm: '' })
const pwdFormRef = ref<FormInstance>()
const pwdRules: FormRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度需在 6-64 位之间', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_r, value: string, cb) => (value === pwdForm.password ? cb() : cb(new Error('两次输入的密码不一致'))),
      trigger: 'blur'
    }
  ]
}
function openResetPwd(u: AdminUserVO): void {
  pwdTarget.value = u
  pwdForm.password = ''
  pwdForm.confirm = ''
  pwdVisible.value = true
}
async function submitResetPwd(): Promise<void> {
  if (!pwdFormRef.value || !pwdTarget.value) return
  const valid = await pwdFormRef.value.validate().catch(() => false)
  if (!valid) return
  const target = pwdTarget.value
  await runPwdSubmit(async () => {
    try {
      await adminUserApi.resetPassword(target.id, { password: pwdForm.password })
      ElMessage.success('密码已重置')
      pwdVisible.value = false
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 行操作 ---------------- */
// 按行分桶：并发切换两行状态互不干扰；同一行在途时开关禁用、不可重复翻转
const statusBusy = useRowBusy<number>()

async function toggleStatus(u: AdminUserVO, status: number): Promise<void> {
  await statusBusy.run(u.id, async () => {
    try {
      await adminUserApi.setStatus(u.id, status)
      ElMessage.success(status === 1 ? '已启用' : '已禁用')
    } catch {
      // 拦截器已提示
    } finally {
      // 无论成败都回读，避免失败时开关停留在与后端不一致的位置
      await loadUsers()
    }
  })
}

async function removeUser(u: AdminUserVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除账号「${u.username}」吗？此操作不可撤销。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await runRemove(async () => {
    try {
      await adminUserApi.remove(u.id)
      ElMessage.success('已删除')
      await loadUsers()
    } catch {
      // 拦截器已提示
    }
  })
}
</script>

<template>
  <div class="user-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">账号管理</h2>
        <p class="ph-desc">管理各租户下的登录账号：创建、改资料、重置密码、启停与删除。</p>
      </div>
      <el-button type="primary" class="add-btn" @click="openCreate">
        <el-icon><Plus /></el-icon> 新建账号
      </el-button>
    </div>

    <div class="toolbar card">
      <span class="tb-label">租户</span>
      <el-select v-model="tenantFilter" style="width: 240px" @change="loadUsers">
        <el-option label="全平台" value="ALL" />
        <el-option v-for="t in tenants" :key="t.id" :label="`${t.name}（#${t.id}）`" :value="t.id" />
      </el-select>
      <el-input v-model="keyword" placeholder="搜索用户名/昵称/邮箱" clearable :prefix-icon="'Search'" style="width: 240px" />
    </div>

    <div class="card table-card">
      <el-table v-loading="loading" :data="filtered" style="width: 100%" row-key="id">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" min-width="130" show-overflow-tooltip />
        <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nickname || '—' }}</template>
        </el-table-column>
        <el-table-column label="所属租户" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ tenantName(row.tenantId) }}</template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="row.role === 'SUPER_ADMIN' ? 'danger' : 'info'" effect="light" round size="small">
              {{ row.role === 'SUPER_ADMIN' ? '超级管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status"
              :active-value="1"
              :inactive-value="0"
              :loading="statusBusy.isBusy(row.id)"
              :disabled="statusBusy.isBusy(row.id)"
              @change="(v: number) => toggleStatus(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
            <el-button size="small" text @click="openResetPwd(row)"><el-icon><Key /></el-icon> 重置密码</el-button>
            <el-tooltip content="删除账号" placement="top" class="ops-danger">
              <el-button size="small" text type="danger" @click="removeUser(row)"><el-icon><Delete /></el-icon></el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无账号" />
        </template>
      </el-table>
    </div>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑账号' : '新建账号'" size="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="所属租户" prop="tenantId">
          <el-select v-model="form.tenantId" :disabled="isEdit" placeholder="选择租户" style="width: 100%">
            <el-option v-for="t in tenants" :key="t.id" :label="`${t.name}（#${t.id}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="登录用户名" maxlength="64" clearable />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="6-64 位" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="可选" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="可选" maxlength="128" clearable />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="普通用户" value="USER" />
            <el-option label="超级管理员" value="SUPER_ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-alert v-if="isEdit" type="info" :closable="false" show-icon title="如需修改密码，请使用列表中的“重置密码”。" />
      </el-form>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-drawer>

    <!-- 重置密码对话框 -->
    <el-dialog v-model="pwdVisible" :title="`重置密码 · ${pwdTarget?.username || ''}`" width="420px" align-center>
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="90px">
        <el-form-item label="新密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" show-password placeholder="6-64 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="pwdForm.confirm" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSubmitting" @click="submitResetPwd">确定重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.user-view {
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
.add-btn {
  background: var(--grad-btn);
  border: none;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
}
.tb-label {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 600;
}
.table-card {
  padding: 8px 12px 12px;
}
</style>
