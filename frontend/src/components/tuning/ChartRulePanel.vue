<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chartRuleApi } from '@/api/chartRule'
import type { ChartRule } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 图表规则面板（配置化升级 V2）：配置「关键词 → 图型」（KEYWORD）与「结构阈值」（THRESHOLD）。
 * KEYWORD 决定用户问题命中哪种图（如"条形图"→HBAR、"面积图"→AREA），priority 小者先匹配；
 * THRESHOLD 覆盖饼图最大切片数 / 单图最多度量数（pattern=阈值键，priority=阈值数值）。
 *
 * 全局内置规则（DataSeeder 播种）只读；本租户新增的行会整体覆盖全局 KEYWORD 规则。
 */
const loading = ref(false)
const rows = ref<ChartRule[]>([])

const CHART_TYPES = [
  { value: 'LINE', label: '折线图' },
  { value: 'BAR', label: '柱状图' },
  { value: 'PIE', label: '饼图' },
  { value: 'AREA', label: '面积图' },
  { value: 'HBAR', label: '条形图' },
  { value: 'TABLE', label: '明细表' }
]
const THRESHOLD_KEYS = [
  { value: 'max-pie-slices', label: '饼图最大切片数' },
  { value: 'max-measures', label: '单图最多度量数' }
]

function chartLabel(t?: string | null): string {
  return CHART_TYPES.find((o) => o.value === t)?.label || t || '—'
}
function thresholdLabel(k?: string | null): string {
  return THRESHOLD_KEYS.find((o) => o.value === k)?.label || k || '—'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await chartRuleApi.list()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())

const keywordRows = computed(() => rows.value.filter((r) => r.ruleType === 'KEYWORD'))
const thresholdRows = computed(() => rows.value.filter((r) => r.ruleType === 'THRESHOLD'))

function isGlobal(r: ChartRule): boolean {
  return r.tenantId == null
}

/* ---------------- 新建 / 编辑抽屉 ---------------- */
const drawerVisible = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const editingId = ref<number | null>(null)
const isEdit = ref(false)

interface FormState {
  ruleType: 'KEYWORD' | 'THRESHOLD'
  pattern: string
  chartType: string
  priority: number
  enabled: number
  description: string
}
const emptyForm = (): FormState => ({
  ruleType: 'KEYWORD',
  pattern: '',
  chartType: 'LINE',
  priority: 100,
  enabled: 1,
  description: ''
})
const form = reactive<FormState>(emptyForm())

function openCreate(type: 'KEYWORD' | 'THRESHOLD'): void {
  editingId.value = null
  isEdit.value = false
  Object.assign(form, emptyForm())
  form.ruleType = type
  if (type === 'THRESHOLD') {
    form.pattern = 'max-pie-slices'
    form.priority = 8
  }
  drawerVisible.value = true
}

function openEdit(r: ChartRule): void {
  editingId.value = r.id ?? null
  isEdit.value = true
  Object.assign(form, {
    ruleType: (r.ruleType as 'KEYWORD' | 'THRESHOLD') ?? 'KEYWORD',
    pattern: r.pattern ?? '',
    chartType: r.chartType ?? 'LINE',
    priority: r.priority ?? 100,
    enabled: r.enabled ?? 1,
    description: r.description ?? ''
  })
  drawerVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!form.pattern.trim()) {
    ElMessage.warning(form.ruleType === 'KEYWORD' ? '请填写关键词' : '请选择阈值键')
    return
  }
  await runSubmit(async () => {
    try {

      const payload: ChartRule = {
        ruleType: form.ruleType,
        pattern: form.pattern.trim(),
        chartType: form.ruleType === 'KEYWORD' ? form.chartType : null,
        priority: form.priority,
        enabled: form.enabled,
        description: form.description.trim() || null
      }
      if (isEdit.value && editingId.value != null) {
        await chartRuleApi.update({ ...payload, id: editingId.value })
        ElMessage.success('规则已更新')
      } else {
        await chartRuleApi.create(payload)
        ElMessage.success('规则已创建')
      }
      drawerVisible.value = false
      await load()
    } catch {
      // 拦截器已提示
    }
  })
}

async function onRemove(r: ChartRule): Promise<void> {
  if (r.id == null) return
  try {
    await ElMessageBox.confirm('确定删除该图表规则吗？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await chartRuleApi.remove(r.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div class="chart-rule-panel">
    <!-- 关键词规则 -->
    <section class="rule-section">
      <div class="sec-head">
        <div>
          <h3 class="sec-title">关键词规则（KEYWORD）</h3>
          <p class="sec-desc">问题命中关键词即选用对应图型，priority 越小越先匹配；pattern 支持逗号分隔的同义词组。</p>
        </div>
        <el-button type="primary" size="default" @click="openCreate('KEYWORD')">
          <el-icon><Plus /></el-icon> 新增关键词规则
        </el-button>
      </div>
      <el-table v-loading="loading" :data="keywordRows" style="width: 100%" row-key="id" border stripe size="small">
        <el-table-column label="关键词（pattern）" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.pattern }}</span></template>
        </el-table-column>
        <el-table-column label="目标图型" width="120" align="center">
          <template #default="{ row }">
            <el-tag type="primary" effect="light" size="small" round>{{ chartLabel(row.chartType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="84" align="center">
          <template #default="{ row }">{{ row.priority ?? 100 }}</template>
        </el-table-column>
        <el-table-column label="作用域" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="isGlobal(row) ? 'warning' : 'info'" effect="plain" size="small" round>
              {{ isGlobal(row) ? '全局' : '本租户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 0 ? 'info' : 'success'" effect="plain" size="small" round>
              {{ row.enabled === 0 ? '停用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <template v-if="!isGlobal(row)">
              <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
              <el-button size="small" text type="danger" @click="onRemove(row)"><el-icon><Delete /></el-icon></el-button>
            </template>
            <span v-else class="muted small">内置只读</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无关键词规则" :image-size="70" />
        </template>
      </el-table>
    </section>

    <!-- 阈值规则 -->
    <section class="rule-section">
      <div class="sec-head">
        <div>
          <h3 class="sec-title">结构阈值（THRESHOLD）</h3>
          <p class="sec-desc">覆盖图表推荐的结构阈值：饼图最大切片数、单图最多度量数（数值填入「阈值」栏）。</p>
        </div>
        <el-button type="primary" size="default" plain @click="openCreate('THRESHOLD')">
          <el-icon><Plus /></el-icon> 新增阈值
        </el-button>
      </div>
      <el-table v-loading="loading" :data="thresholdRows" style="width: 100%" row-key="id" border stripe size="small">
        <el-table-column label="阈值键" min-width="180">
          <template #default="{ row }">{{ thresholdLabel(row.pattern) }} <span class="mono muted small">({{ row.pattern }})</span></template>
        </el-table-column>
        <el-table-column label="阈值" width="90" align="center">
          <template #default="{ row }">{{ row.priority ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="作用域" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="isGlobal(row) ? 'warning' : 'info'" effect="plain" size="small" round>
              {{ isGlobal(row) ? '全局' : '本租户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <template v-if="!isGlobal(row)">
              <el-button size="small" text @click="openEdit(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
              <el-button size="small" text type="danger" @click="onRemove(row)"><el-icon><Delete /></el-icon></el-button>
            </template>
            <span v-else class="muted small">内置只读</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无阈值规则，回落平台默认" :image-size="70" />
        </template>
      </el-table>
    </section>

    <!-- 新建 / 编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="isEdit ? '编辑规则' : '新增规则'" size="460px">
      <el-form :model="form" label-width="88px">
        <el-form-item label="规则类型">
          <el-radio-group v-model="form.ruleType" :disabled="isEdit">
            <el-radio-button value="KEYWORD">关键词</el-radio-button>
            <el-radio-button value="THRESHOLD">阈值</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.ruleType === 'KEYWORD'">
          <el-form-item label="关键词">
            <el-input v-model="form.pattern" placeholder="多个同义词用逗号分隔，例如：条形,横向柱状" maxlength="255" clearable />
          </el-form-item>
          <el-form-item label="目标图型">
            <el-select v-model="form.chartType" style="width: 100%">
              <el-option v-for="o in CHART_TYPES" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-input-number v-model="form.priority" :min="0" :max="9999" controls-position="right" style="width: 100%" />
            <span class="field-hint muted">数值越小越先匹配</span>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="阈值键">
            <el-select v-model="form.pattern" style="width: 100%">
              <el-option v-for="o in THRESHOLD_KEYS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="阈值">
            <el-input-number v-model="form.priority" :min="1" :max="999" controls-position="right" style="width: 100%" />
          </el-form-item>
        </template>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="可选" maxlength="255" clearable />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
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
.chart-rule-panel {
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.rule-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sec-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.sec-title {
  font-size: 15px;
  font-weight: 700;
  margin: 0 0 4px;
}
.sec-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  max-width: 640px;
}
.small {
  font-size: 12px;
}
.field-hint {
  font-size: 12px;
}
</style>
