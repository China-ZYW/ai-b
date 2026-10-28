<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { adminLlmConfigApi, adminTenantApi } from '@/api/admin'
import type { LlmConfigForm, LlmConfigVO, TenantVO } from '@/types'
import { useActionGuard, useRowBusy } from '@/utils/actionGuard'

/**
 * 大模型配置（超管后台）：全局默认（tenantId 空）+ 租户覆盖的 CRUD、启停、优先级、连通性测试。
 * API Key 只写不读：列表仅展示 hasApiKey；编辑时留空表示保持原 Key。
 */
const loading = ref(false)
const tenants = ref<TenantVO[]>([])
const configs = ref<LlmConfigVO[]>([])
const scopeFilter = ref<'ALL' | 'GLOBAL' | number>('ALL')

const PROVIDER_OPTIONS = [
  { value: 'DEEPSEEK', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com' },
  { value: 'QWEN', label: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容', baseUrl: '' }
]

async function loadTenants(): Promise<void> {
  try {
    tenants.value = await adminTenantApi.list()
  } catch {
    // 拦截器已提示
  }
}
async function loadConfigs(): Promise<void> {
  loading.value = true
  try {
    configs.value = await adminLlmConfigApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  await loadTenants()
  await loadConfigs()
})

const filtered = computed(() => {
  if (scopeFilter.value === 'ALL') return configs.value
  if (scopeFilter.value === 'GLOBAL') return configs.value.filter((c) => c.tenantId == null)
  return configs.value.filter((c) => c.tenantId === scopeFilter.value)
})

function providerLabel(p?: string | null): string {
  return PROVIDER_OPTIONS.find((o) => o.value === p)?.label || p || '—'
}

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
// 保存 / 抽屉内测试：同一时刻只应有一次（连点不会产生第二次提交）
const { busy: submitting, run: runSubmit } = useActionGuard()
const { busy: testingForm, run: runTestForm } = useActionGuard()
const formRef = ref<FormInstance>()
const isEdit = computed(() => editingId.value != null)

interface LlmFormState {
  tenantId: number | 'GLOBAL'
  provider: string
  name: string
  baseUrl: string
  model: string
  apiKey: string
  timeoutSeconds: number
  priority: number
  enabled: number
}
const emptyForm = (): LlmFormState => ({
  tenantId: 'GLOBAL',
  provider: 'DEEPSEEK',
  name: '',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-chat',
  apiKey: '',
  timeoutSeconds: 60,
  priority: 100,
  enabled: 1
})
const form = reactive<LlmFormState>(emptyForm())

const rules: FormRules = {
  provider: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  name: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }],
  model: [{ required: true, message: '请输入模型名', trigger: 'blur' }]
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  if (typeof scopeFilter.value === 'number') form.tenantId = scopeFilter.value
  drawerVisible.value = true
}
function openEdit(c: LlmConfigVO): void {
  editingId.value = c.id
  Object.assign(form, {
    tenantId: c.tenantId ?? 'GLOBAL',
    provider: c.provider,
    name: c.name,
    baseUrl: c.baseUrl || '',
    model: c.model || '',
    apiKey: '',
    timeoutSeconds: c.timeoutSeconds ?? 60,
    priority: c.priority ?? 100,
    enabled: c.enabled ?? 1
  })
  drawerVisible.value = true
}
function onProviderChange(p: string): void {
  const hit = PROVIDER_OPTIONS.find((o) => o.value === p)
  if (hit && hit.baseUrl) form.baseUrl = hit.baseUrl
  if (p === 'DEEPSEEK' && !form.model) form.model = 'deepseek-chat'
}

function buildPayload(): LlmConfigForm {
  return {
    tenantId: form.tenantId === 'GLOBAL' ? null : form.tenantId,
    provider: form.provider,
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    apiKey: form.apiKey.trim() || null,
    model: form.model.trim(),
    timeoutSeconds: form.timeoutSeconds,
    priority: form.priority,
    enabled: form.enabled
  }
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  await runSubmit(async () => {
    try {
      if (isEdit.value && editingId.value != null) {
        await adminLlmConfigApi.update(editingId.value, buildPayload())
        ElMessage.success('配置已更新')
      } else {
        await adminLlmConfigApi.create(buildPayload())
        ElMessage.success('配置已创建')
      }
      drawerVisible.value = false
      await loadConfigs()
    } catch {
      // 拦截器已提示
    }
  })
}

/** 测试抽屉里正在编辑的配置（明文 Key 优先，未保存也可测）。 */
async function onTestForm(): Promise<void> {
  await runTestForm(async () => {
    try {
      await adminLlmConfigApi.test(buildPayload())
      ElMessage.success('连通性测试通过')
    } catch {
      // 拦截器已提示
    }
  })
}

/* ---------------- 行操作 ---------------- */
// 按行分桶：并发点两行的「测试」不会互相顶掉 loading；同一行在途时开关禁用、不可重复翻转
const testBusy = useRowBusy<number>()
const toggleBusy = useRowBusy<number>()

async function testSaved(c: LlmConfigVO): Promise<void> {
  await testBusy.run(c.id, async () => {
    try {
      await adminLlmConfigApi.testById(c.id)
      ElMessage.success(`「${c.name}」连通性测试通过`)
    } catch {
      // 拦截器已提示
    }
  })
}

async function toggleEnabled(c: LlmConfigVO, enabled: number): Promise<void> {
  await toggleBusy.run(c.id, async () => {
    try {
      await adminLlmConfigApi.setEnabled(c.id, enabled)
      ElMessage.success(enabled === 1 ? '已启用' : '已停用')
    } catch {
      // 拦截器已提示
    } finally {
      // 无论成败都回读：失败时不刷新会让开关停在与后端不一致的位置（乐观 UI 的假象）
      await loadConfigs()
    }
  })
}

async function removeConfig(c: LlmConfigVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除大模型配置「${c.name}」吗？此操作不可撤销。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await adminLlmConfigApi.remove(c.id)
    ElMessage.success('已删除')
    await loadConfigs()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="llm-view">
    <div class="page-head">
      <div>
        <h2 class="ph-title">大模型配置</h2>
        <p class="ph-desc">全局默认 + 租户覆盖；priority 越小越优先，主力失败自动降级备选。API Key 加密存储、只写不读。</p>
      </div>
      <el-button type="primary" class="add-btn" @click="openCreate">
        <el-icon><Plus /></el-icon> 新建配置
      </el-button>
    </div>

    <div class="toolbar card">
      <span class="tb-label">作用域</span>
      <el-select v-model="scopeFilter" style="width: 260px">
        <el-option label="全部" value="ALL" />
        <el-option label="全局默认" value="GLOBAL" />
        <el-option v-for="t in tenants" :key="t.id" :label="`${t.name}（#${t.id}）`" :value="t.id" />
      </el-select>
    </div>

    <div class="card table-card">
      <el-table v-loading="loading" :data="filtered" style="width: 100%" row-key="id">
        <el-table-column label="作用域" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.tenantId == null" type="warning" effect="plain" round size="small">全局默认</el-tag>
            <el-tag v-else type="info" effect="plain" round size="small">
              {{ tenants.find((t) => t.id === row.tenantId)?.name || `租户#${row.tenantId}` }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="供应商" width="120">
          <template #default="{ row }">{{ providerLabel(row.provider) }}</template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="model" label="模型" min-width="140" show-overflow-tooltip />
        <el-table-column prop="baseUrl" label="Base URL" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono small">{{ row.baseUrl || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="优先级" width="90" align="center">
          <template #default="{ row }">{{ row.priority ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="API Key" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.hasApiKey ? 'success' : 'danger'" effect="light" round size="small">
              {{ row.hasApiKey ? '已设置' : '未设置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :active-value="1"
              :inactive-value="0"
              :loading="toggleBusy.isBusy(row.id)"
              :disabled="toggleBusy.isBusy(row.id)"
              @change="(v: number) => toggleEnabled(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text :loading="testBusy.isBusy(row.id)" @click="testSaved(row)">
              <el-icon><Connection /></el-icon> 测试
            </el-button>
            <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
            <el-tooltip content="删除配置" placement="top" class="ops-danger">
              <el-button size="small" text type="danger" @click="removeConfig(row)"><el-icon><Delete /></el-icon></el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无大模型配置" />
        </template>
      </el-table>
    </div>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑大模型配置' : '新建大模型配置'" size="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="作用域">
          <el-select v-model="form.tenantId" style="width: 100%">
            <el-option label="全局默认（所有租户兜底）" value="GLOBAL" />
            <el-option v-for="t in tenants" :key="t.id" :label="`${t.name}（#${t.id}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" prop="provider">
          <el-select v-model="form.provider" style="width: 100%" @change="onProviderChange">
            <el-option v-for="o in PROVIDER_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：DeepSeek 主力" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" clearable />
        </el-form-item>
        <el-form-item label="模型" prop="model">
          <el-input v-model="form.model" placeholder="deepseek-chat" clearable />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示保持原 Key 不变' : '请输入 API Key'"
          />
        </el-form-item>
        <el-form-item label="超时(秒)">
          <el-input-number v-model="form.timeoutSeconds" :min="1" :max="600" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="9999" controls-position="right" style="width: 100%" />
          <span class="field-hint muted">数值越小越优先</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button plain :loading="testingForm" @click="onTestForm">
          <el-icon><Connection /></el-icon> 测试连接
        </el-button>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.llm-view {
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
  max-width: 720px;
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
.small {
  font-size: 12px;
}
.field-hint {
  font-size: 12px;
  margin-left: 2px;
}
</style>
