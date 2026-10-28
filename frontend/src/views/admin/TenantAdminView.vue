<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { adminTenantApi } from '@/api/admin'
import type { PlanType, TenantForm, TenantVO } from '@/types'
import { useActionGuard, useRowBusy } from '@/utils/actionGuard'
import { formatDateTime, formatNumber, planLabel } from '@/utils/format'

/**
 * 租户管理（超管后台）：表格 + 新建/编辑抽屉。
 * 覆盖套餐、隔离模式、使用期限、每日/累计配额、启停、删除。跨租户操作。
 */
const loading = ref(false)
const list = ref<TenantVO[]>([])
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)

/** 套餐默认（隔离模式 + 每日上限）。 */
const PLAN_DEFAULTS: Record<string, { isolation: string; daily: number }> = {
  FREE: { isolation: 'POOL', daily: 20 },
  PRO: { isolation: 'POOL', daily: 1000 },
  ENTERPRISE: { isolation: 'DATABASE', daily: -1 }
}
const PLAN_OPTIONS: { value: PlanType; label: string }[] = [
  { value: 'FREE', label: '免费版' },
  { value: 'PRO', label: '专业版' },
  { value: 'ENTERPRISE', label: '企业版' }
]
const ISOLATION_OPTIONS = [
  { value: 'POOL', label: '共享库（行级隔离）' },
  { value: 'SCHEMA', label: '独立 Schema' },
  { value: 'DATABASE', label: '独立库/实例' }
]

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await adminTenantApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return list.value
  return list.value.filter((t) => t.name.toLowerCase().includes(k) || String(t.id).includes(k))
})
const paged = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
// 保存：连点只提交一次（重入由护栏早退，不再只靠按钮 loading 兜底）
const { busy: submitting, run: runSubmit } = useActionGuard()
const formRef = ref<FormInstance>()
const isEdit = computed(() => editingId.value != null)

interface TenantFormState {
  name: string
  plan: string
  isolationMode: string
  status: number
  expireAt: string | null
  dailyLimit: number
  totalLimit: number
}
const emptyForm = (): TenantFormState => ({
  name: '',
  plan: 'FREE',
  isolationMode: 'POOL',
  status: 1,
  expireAt: null,
  dailyLimit: 20,
  totalLimit: -1
})
const form = reactive<TenantFormState>(emptyForm())

const rules: FormRules = {
  name: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  plan: [{ required: true, message: '请选择套餐', trigger: 'change' }]
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  drawerVisible.value = true
}

function openEdit(t: TenantVO): void {
  editingId.value = t.id
  Object.assign(form, {
    name: t.name,
    plan: t.plan,
    isolationMode: t.isolationMode || PLAN_DEFAULTS[t.plan]?.isolation || 'POOL',
    status: t.status ?? 1,
    expireAt: t.expireAt || null,
    dailyLimit: t.dailyLimit ?? PLAN_DEFAULTS[t.plan]?.daily ?? 20,
    totalLimit: t.totalLimit ?? -1
  })
  drawerVisible.value = true
}

/** 套餐变更时联动默认隔离模式与每日上限（仅新建时自动填充）。 */
function onPlanChange(plan: string): void {
  const d = PLAN_DEFAULTS[plan]
  if (!d) return
  form.isolationMode = d.isolation
  if (!isEdit.value) form.dailyLimit = d.daily
}

function buildPayload(): TenantForm {
  return {
    name: form.name.trim(),
    plan: form.plan,
    isolationMode: form.isolationMode,
    status: form.status,
    expireAt: form.expireAt || null,
    dailyLimit: form.dailyLimit,
    totalLimit: form.totalLimit
  }
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  await runSubmit(async () => {
    try {
      if (isEdit.value && editingId.value != null) {
        await adminTenantApi.update(editingId.value, buildPayload())
        ElMessage.success('租户已更新')
      } else {
        await adminTenantApi.create(buildPayload())
        ElMessage.success('租户已创建')
      }
      drawerVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 行操作 ---------------- */
// 按行分桶：并发切换两行租户状态互不干扰；同一行在途时开关禁用、不可重复翻转
const statusBusy = useRowBusy<number>()

async function toggleStatus(t: TenantVO, status: number): Promise<void> {
  await statusBusy.run(t.id, async () => {
    try {
      await adminTenantApi.setStatus(t.id, status)
      ElMessage.success(status === 1 ? '已启用' : '已停用')
    } catch {
      // 拦截器已提示
    } finally {
      // 无论成败都回读，避免失败时开关停留在与后端不一致的位置
      await load()
    }
  })
}

async function removeTenant(t: TenantVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除租户「${t.name}」吗？该租户下所有账号将一并删除，此操作不可撤销。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await adminTenantApi.remove(t.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}

/* ---------------- 展示辅助 ---------------- */
function limitText(v?: number | null): string {
  if (v == null) return '—'
  return v < 0 ? '不限' : formatNumber(v)
}
function planTagType(plan?: string | null): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  switch (plan) {
    case 'PRO':
      return 'primary'
    case 'ENTERPRISE':
      return 'warning'
    default:
      return 'info'
  }
}
function isExpired(t: TenantVO): boolean {
  return !!t.expireAt && new Date(t.expireAt).getTime() < Date.now()
}
</script>

<template>
  <div class="tenant-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">租户管理</h2>
        <p class="ph-desc">创建与管理租户，配置套餐、使用期限、每日/累计配额与启停状态。</p>
      </div>
      <div class="row gap-12">
        <el-input v-model="keyword" placeholder="搜索名称/ID" clearable :prefix-icon="'Search'" style="width: 200px" />
        <el-button type="primary" class="add-btn" @click="openCreate">
          <el-icon><Plus /></el-icon> 新建租户
        </el-button>
      </div>
    </div>

    <div class="card table-card">
      <el-table v-loading="loading" :data="paged" style="width: 100%" row-key="id">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="租户名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="套餐" width="100">
          <template #default="{ row }">
            <el-tag :type="planTagType(row.plan)" effect="light" round size="small">
              {{ planLabel(row.plan) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="隔离模式" width="120">
          <template #default="{ row }">
            <span class="mono small">{{ row.isolationMode || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="每日上限" width="100" align="right">
          <template #default="{ row }">{{ limitText(row.dailyLimit) }}</template>
        </el-table-column>
        <el-table-column label="累计上限" width="100" align="right">
          <template #default="{ row }">{{ limitText(row.totalLimit) }}</template>
        </el-table-column>
        <el-table-column label="累计已用" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.totalUsed) }}</template>
        </el-table-column>
        <el-table-column label="使用期限" min-width="180">
          <template #default="{ row }">
            <span v-if="!row.expireAt" class="muted">永久有效</span>
            <span v-else>
              {{ formatDateTime(row.expireAt) }}
              <el-tag v-if="isExpired(row)" type="danger" size="small" effect="dark" round>已过期</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="账号" width="80" align="center">
          <template #default="{ row }">{{ formatNumber(row.userCount) }}</template>
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
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
            <el-tooltip content="删除租户" placement="top" class="ops-danger">
              <el-button size="small" text type="danger" @click="removeTenant(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无租户" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="filtered.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </div>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑租户' : '新建租户'" size="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="租户名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：某某科技有限公司" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="套餐" prop="plan">
          <el-select v-model="form.plan" style="width: 100%" @change="onPlanChange">
            <el-option v-for="p in PLAN_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="隔离模式">
          <el-select v-model="form.isolationMode" style="width: 100%">
            <el-option v-for="o in ISOLATION_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="使用期限">
          <el-date-picker
            v-model="form.expireAt"
            type="datetime"
            placeholder="留空表示永久有效"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
            clearable
          />
        </el-form-item>
        <el-form-item label="每日上限">
          <el-input-number v-model="form.dailyLimit" :min="-1" :step="10" controls-position="right" style="width: 100%" />
          <span class="field-hint muted">-1 表示不限制每日生成次数</span>
        </el-form-item>
        <el-form-item label="累计上限">
          <el-input-number v-model="form.totalLimit" :min="-1" :step="100" controls-position="right" style="width: 100%" />
          <span class="field-hint muted">-1 表示不限制累计总次数</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
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
.tenant-view {
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
.table-card {
  padding: 8px 12px 12px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 4px;
}
.small {
  font-size: 12px;
}
.field-hint {
  font-size: 12px;
  margin-left: 2px;
}
</style>
