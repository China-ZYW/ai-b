<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { metricApi } from '@/api/metric'
import type { ListWidgetSpec, MetricVO, QueryResult } from '@/types'

/**
 * 列表组件配置对话框：先选绑定指标（取数来源），再按该指标结果的列配置
 * 主/次/数值列与自动滚动。列名下拉来自指标的实时结果 columns（选指标后自动拉一次样例）。
 * 确认时回传 { metricId, title, spec }，由编辑器写库。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    metrics: MetricVO[]
    initialMetricId?: number | null
    spec?: ListWidgetSpec | null
  }>(),
  { initialMetricId: null, spec: null }
)
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm', payload: { metricId: number; title: string; spec: ListWidgetSpec }): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const metricId = ref<number | null>(null)
const title = ref('')
const primaryField = ref<string | null>(null)
const secondaryField = ref<string | null>(null)
const valueField = ref<string | null>(null)
const autoScroll = ref(false)
const scrollSpeed = ref(30)
const sampleColumns = ref<string[]>([])
const loadingCols = ref(false)

const isEdit = computed(() => props.initialMetricId != null)

/** 打开对话框时回填初始值，并按已选指标拉取样例列。 */
watch(
  visible,
  (v) => {
    if (!v) return
    metricId.value = props.initialMetricId ?? null
    title.value = ''
    primaryField.value = props.spec?.primaryField ?? null
    secondaryField.value = props.spec?.secondaryField ?? null
    valueField.value = props.spec?.valueField ?? null
    autoScroll.value = !!props.spec?.autoScroll
    scrollSpeed.value = props.spec?.scrollSpeed ?? 30
    sampleColumns.value = []
    if (metricId.value != null) void loadColumns(metricId.value)
  },
  { immediate: true }
)

async function onMetricChange(mid: number | null): Promise<void> {
  metricId.value = mid
  sampleColumns.value = []
  if (mid != null) {
    title.value = props.metrics.find((m) => m.id === mid)?.name || title.value
    await loadColumns(mid)
  }
}

/** 拉取指标样例结果，提取列名作为列配置下拉项。 */
async function loadColumns(mid: number): Promise<void> {
  loadingCols.value = true
  try {
    const data = await metricApi.refresh(mid)
    const r: QueryResult | null = data.result
    sampleColumns.value = r?.columns ?? []
  } catch {
    sampleColumns.value = []
  } finally {
    loadingCols.value = false
  }
}

function onConfirm(): void {
  if (metricId.value == null) {
    ElMessage.warning('请先选择绑定的指标')
    return
  }
  emit('confirm', {
    metricId: metricId.value,
    title: title.value.trim(),
    spec: {
      primaryField: primaryField.value || null,
      secondaryField: secondaryField.value || null,
      valueField: valueField.value || null,
      autoScroll: autoScroll.value,
      scrollSpeed: autoScroll.value ? Math.max(8, Number(scrollSpeed.value) || 30) : null
    }
  })
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑列表' : '添加列表'"
    width="460px"
    append-to-body
    destroy-on-close
  >
    <div class="lw-dialog">
      <div class="lw-field">
        <label class="lw-label">绑定指标</label>
        <el-select
          :model-value="metricId"
          size="default"
          placeholder="选择取数指标"
          filterable
          style="width: 100%"
          @update:model-value="onMetricChange"
        >
          <el-option v-for="m in metrics" :key="m.id" :label="m.name" :value="m.id" />
        </el-select>
      </div>

      <div v-if="metricId != null" class="lw-field">
        <label class="lw-label">列表标题（空=指标名）</label>
        <el-input v-model="title" size="default" maxlength="64" clearable placeholder="空=指标名称" />
      </div>

      <template v-if="metricId != null">
        <div class="lw-field">
          <label class="lw-label">主展示列（榜单主文案）</label>
          <el-select
            v-model="primaryField"
            size="default"
            clearable
            placeholder="留空取结果第一列"
            style="width: 100%"
            :loading="loadingCols"
          >
            <el-option v-for="c in sampleColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </div>
        <div class="lw-field">
          <label class="lw-label">次展示列（辅助文案）</label>
          <el-select
            v-model="secondaryField"
            size="default"
            clearable
            placeholder="不设置"
            style="width: 100%"
            :loading="loadingCols"
          >
            <el-option v-for="c in sampleColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </div>
        <div class="lw-field">
          <label class="lw-label">数值列（右侧高亮数值）</label>
          <el-select
            v-model="valueField"
            size="default"
            clearable
            placeholder="不设置"
            style="width: 100%"
            :loading="loadingCols"
          >
            <el-option v-for="c in sampleColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </div>

        <div class="lw-field lw-switch-row">
          <span class="lw-label">自动滚动（跑马灯）</span>
          <el-switch v-model="autoScroll" />
        </div>
        <div v-if="autoScroll" class="lw-field">
          <label class="lw-label">滚动一圈耗时（秒，越大越慢）</label>
          <el-input-number v-model="scrollSpeed" :min="8" :max="120" :step="5" size="default" />
        </div>
      </template>

      <p v-else class="lw-tip muted">选择指标后即可配置主/次/数值列与滚动方式。</p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.lw-dialog {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.lw-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lw-label {
  font-size: 12.5px;
  color: var(--el-text-color-regular);
  font-weight: 600;
}
.lw-switch-row {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}
.lw-tip {
  margin: 0;
  font-size: 12.5px;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
