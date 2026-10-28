<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { adminTenantApi } from '@/api/admin'
import type { PlanType, SeatQuotaForm, TenantVO } from '@/types'
import { formatNumber, planLabel } from '@/utils/format'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 席位与配额（平台后台 · 运营组，§9.2）。
 *
 * 逐租户调整席位上限与每日/累计问数次数，或按套餐批量重置。权限码 admin:tenant:write
 * （矩阵 YYNN → SUPER_ADMIN / OPERATOR）。已用席位（seatUsed）由邀请接受/成员移除事务维护，
 * 本页只读展示、不可改写；批量重置只覆盖 seat_limit 与 daily_limit，不动 total_limit 与 seat_used。
 */
const loading = ref(false)
const list = ref<TenantVO[]>([])
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)

const PLAN_OPTIONS: { value: PlanType | ''; label: string }[] = [
  { value: '', label: '全部套餐' },
  { value: 'FREE', label: '免费版' },
  { value: 'PRO', label: '专业版' },
  { value: 'ENTERPRISE', label: '企业版' }
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

/** 顶部统计：租户总数、席位占用合计、不限席位数、已满席位数。 */
const stats = computed(() => {
  let seatUsed = 0
  let unlimited = 0
  let full = 0
  for (const t of list.value) {
    seatUsed += t.seatUsed ?? 0
    const limit = t.seatLimit ?? -1
    if (limit < 0) unlimited++
    else if ((t.seatUsed ?? 0) >= limit) full++
  }
  return { tenantCount: list.value.length, seatUsed, unlimited, full }
})

/* ---------------- 逐租户调整对话框 ---------------- */
const dialogVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const formRef = ref<FormInstance>()
const editing = ref<TenantVO | null>(null)

interface SeatFormState {
  seatLimit: number
  dailyLimit: number
  totalLimit: number
}
const form = reactive<SeatFormState>({ seatLimit: -1, dailyLimit: -1, totalLimit: -1 })

function openEdit(t: TenantVO): void {
  editing.value = t
  form.seatLimit = t.seatLimit ?? -1
  form.dailyLimit = t.dailyLimit ?? -1
  form.totalLimit = t.totalLimit ?? -1
  dialogVisible.value = true
}

/** 席位上限低于已用席位时提示（仍允许保存：仅阻断后续新邀请，不强制移除现有成员）。 */
const seatBelowUsed = computed(() => {
  const used = editing.value?.seatUsed ?? 0
  return form.seatLimit >= 0 && form.seatLimit < used
})

async function onSubmit(): Promise<void> {
  if (!editing.value) return
  const target = editing.value
  await runSubmit(async () => {
    try {

      const payload: SeatQuotaForm = {
        seatLimit: form.seatLimit,
        dailyLimit: form.dailyLimit,
        totalLimit: form.totalLimit
      }
      await adminTenantApi.updateSeats(target.id, payload)
      ElMessage.success('席位与配额已更新')
      dialogVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 批量按套餐重置 ---------------- */
const resetVisible = ref(false)
const resetting = ref(false)
const resetPlan = ref<PlanType | ''>('')

async function onReset(): Promise<void> {
  const scope = resetPlan.value ? planLabel(resetPlan.value) : '全部套餐'
  try {
    await ElMessageBox.confirm(
      `将把「${scope}」下所有租户的席位上限与每日上限重置为套餐默认值（累计上限与已用席位不受影响）。此操作会覆盖这些租户的既有自定义值，确定继续？`,
      '批量重置确认',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    const affected = await adminTenantApi.resetSeatsByPlan(resetPlan.value || undefined)
    ElMessage.success(`已重置 ${affected} 个租户`)
    resetVisible.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    resetting.value = false
  }
}

/* ---------------- 展示辅助 ---------------- */
function limitText(v?: number | null): string {
  if (v == null) return '—'
  return v < 0 ? '不限' : formatNumber(v)
}
function seatText(t: TenantVO): string {
  const limit = t.seatLimit ?? -1
  const used = t.seatUsed ?? 0
  return limit < 0 ? `${formatNumber(used)} / 不限` : `${formatNumber(used)} / ${formatNumber(limit)}`
}
function isSeatFull(t: TenantVO): boolean {
  const limit = t.seatLimit ?? -1
  return limit >= 0 && (t.seatUsed ?? 0) >= limit
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
</script>

<template>
  <div class="seat-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">席位与配额</h2>
        <p class="ph-desc">逐租户调整席位上限与每日/累计问数次数，或按套餐批量重置为默认值。</p>
      </div>
      <div class="row gap-12">
        <el-input v-model="keyword" placeholder="搜索名称/ID" clearable :prefix-icon="'Search'" style="width: 200px" />
        <el-tooltip content="按套餐批量重置席位上限与每日上限，会覆盖这些租户的既有自定义值" placement="top">
          <el-button plain @click="resetVisible = true">
            <el-icon><RefreshLeft /></el-icon> 批量按套餐重置
          </el-button>
        </el-tooltip>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <span class="stat-num">{{ formatNumber(stats.tenantCount) }}</span>
        <span class="stat-label">租户总数</span>
      </div>
      <div class="stat-card">
        <span class="stat-num">{{ formatNumber(stats.seatUsed) }}</span>
        <span class="stat-label">已用席位合计</span>
      </div>
      <div class="stat-card">
        <span class="stat-num">{{ formatNumber(stats.unlimited) }}</span>
        <span class="stat-label">不限席位租户</span>
      </div>
      <div class="stat-card" :class="{ 'is-warn': stats.full > 0 }">
        <span class="stat-num">{{ formatNumber(stats.full) }}</span>
        <span class="stat-label">已满席位租户</span>
      </div>
    </div>

    <div class="card table-card">
      <el-table v-loading="loading" :data="paged" style="width: 100%" row-key="id">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="租户名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="套餐" width="100">
          <template #default="{ row }">
            <el-tag :type="planTagType(row.plan)" effect="light" round size="small">
              {{ planLabel(row.plan) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="席位（已用/上限）" width="150" align="center">
          <template #default="{ row }">
            <span>{{ seatText(row) }}</span>
            <el-tag v-if="isSeatFull(row)" type="danger" size="small" effect="dark" round class="ml-6">满</el-tag>
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
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEdit(row)">
              <el-icon><Edit /></el-icon> 调整
            </el-button>
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

    <!-- 逐租户席位/配额调整 -->
    <el-dialog v-model="dialogVisible" title="调整席位与配额" width="460px">
      <div v-if="editing" class="edit-head">
        <span class="eh-name">{{ editing.name }}</span>
        <el-tag :type="planTagType(editing.plan)" effect="light" round size="small">
          {{ planLabel(editing.plan) }}
        </el-tag>
        <span class="muted small">当前已用席位 {{ formatNumber(editing.seatUsed ?? 0) }}</span>
      </div>
      <el-form ref="formRef" :model="form" label-width="96px">
        <el-form-item label="席位上限">
          <el-input-number v-model="form.seatLimit" :min="-1" :step="1" controls-position="right" style="width: 100%" />
          <span class="field-hint muted">-1 表示不限席位</span>
          <el-alert
            v-if="seatBelowUsed"
            type="warning"
            :closable="false"
            show-icon
            class="mt-8"
            title="席位上限低于已用席位，保存后将无法再接受新邀请（现有成员不受影响）。"
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
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量按套餐重置 -->
    <el-dialog v-model="resetVisible" title="批量按套餐重置" width="420px">
      <el-form label-width="80px">
        <el-form-item label="套餐范围">
          <el-select v-model="resetPlan" style="width: 100%">
            <el-option v-for="p in PLAN_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="muted small reset-tip">
        仅重置席位上限与每日上限为套餐默认值（免费 3 席 / 专业 20 席 / 企业不限）；累计上限与已用席位不受影响。
      </p>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="onReset">重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.seat-view {
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
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
}
.stat-card.is-warn {
  border-color: var(--el-color-danger-light-5);
}
.stat-num {
  font-size: 24px;
  font-weight: 800;
  line-height: 1.1;
}
.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.table-card {
  padding: 8px 12px 12px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 4px;
}
.edit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.eh-name {
  font-weight: 700;
}
.reset-tip {
  margin: 0;
  line-height: 1.6;
}
.small {
  font-size: 12px;
}
.ml-6 {
  margin-left: 6px;
}
.mt-8 {
  margin-top: 8px;
}
.field-hint {
  font-size: 12px;
  margin-left: 2px;
}
</style>
