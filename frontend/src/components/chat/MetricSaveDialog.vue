<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { metricApi } from '@/api/metric'
import { displayColumns, pruneColumnLabels } from '@/utils/format'
import type { ChartSpec, QueryResult } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 存为指标对话框：把问数结果（问题 + SQL + 图表规格 + 列展示名）保存为可复用指标。
 * 指标以已存 SQL 实时重跑取数，不消耗 LLM、不计入问数配额，可加入驾驶舱。
 *
 * <p>列展示名随指标落库（{@code metric.display_config}）：汇总/派生列在语义层没有物理列行，
 * 靠这里取业务叫法，优先级高于语义层别名；只影响表头/图例文案，不影响图表字段匹配与 SQL。
 */
const props = defineProps<{
  modelValue: boolean
  datasourceId: number | null
  question: string
  sql?: string | null
  chart?: ChartSpec | null
  /** 问数结果：据此列出各列并预填展示名。 */
  result?: QueryResult | null
  /** 问数页表头已重命名的列（物理列名 → 自定义名），打开时带入表单。 */
  columnLabels?: Record<string, string> | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'created', id: number): void
}>()

const { busy: submitting, run: runSubmit } = useActionGuard()
const formRef = ref<FormInstance>()
const form = reactive<{ name: string; description: string; alias: string; definition: string }>({
  name: '',
  description: '',
  alias: '',
  definition: ''
})

/** 默认展示名（语义层别名，无别名则列名），同时作为输入框 placeholder。 */
const defaultLabels = computed(() => displayColumns(props.result))
/** 待命名的列清单（按结果列顶序）。 */
const labelRows = computed(() =>
  (props.result?.columns ?? []).map((c, i) => ({ column: c, fallback: defaultLabels.value[i] || c }))
)
/** 列展示名草稿：物理列名 → 展示名。 */
const labelForm = ref<Record<string, string>>({})

const rules: FormRules = {
  name: [{ required: true, message: '请输入指标名称', trigger: 'blur' }]
}

// 每次打开重置表单，并用问题预填名称、用“表头重命名 > 语义层默认名”预填各列展示名
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      form.name = (props.question || '').slice(0, 40)
      form.description = ''
      form.definition = ''
      const next: Record<string, string> = {}
      labelRows.value.forEach((r) => {
        next[r.column] = props.columnLabels?.[r.column]?.trim() || r.fallback
      })
      labelForm.value = next
      formRef.value?.clearValidate()
    }
  }
)

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (props.datasourceId == null || !props.sql) {
    ElMessage.warning('缺少可保存的查询结果')
    return
  }
  await runSubmit(async () => {
    try {

      const vo = await metricApi.create({
        name: form.name.trim(),
        description: form.description.trim() || null,
        alias: form.alias.trim() || null,
        // canonical 口径：趋势 / 因子 / 归因共用的唯一度量来源；留空则该指标不能参与归因
        definition: form.definition.trim() || null,
        datasourceId: props.datasourceId!,
        question: props.question || null,
        sql: props.sql!,
        chartSpec: props.chart ?? null,
        // 只存“确实改了名”的列，与默认名相同的条目不占 display_config 列宽
        columnLabels: pruneColumnLabels(props.result, labelForm.value)
      })
      ElMessage.success('已保存为指标')
      emit('created', vo.id)
      close()
    } catch {
      // 拦截器已提示
    }
  })
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="存为指标"
    width="520px"
    align-center
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="指标名称" prop="name">
        <el-input v-model="form.name" placeholder="例如：各地区已支付订单金额" maxlength="64" show-word-limit clearable />
      </el-form-item>
      <el-form-item label="描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="可选：说明该指标的业务含义与口径"
        />
      </el-form-item>
      <el-form-item label="指标别名">
        <el-input
          v-model="form.alias"
          maxlength="64"
          show-word-limit
          clearable
          placeholder="可选：图表标题/大屏组件展示名，留空则用指标名称"
        />
      </el-form-item>
      <el-form-item label="canonical 口径">
        <el-input
          v-model="form.definition"
          placeholder="如 SUM(amount)；留空则该指标不能参与归因"
          clearable
        />
        <span class="label-tip">
          趋势 / 量价因子 / 维度归因共用的唯一度量来源。归因要求<b>可加度量</b>，
          含除法的比率类（毛利率、客单价）会被后端拒绝。
        </span>
      </el-form-item>
      <el-form-item v-if="labelRows.length" label="列展示名">
        <div class="label-list">
          <div v-for="r in labelRows" :key="r.column" class="label-row">
            <span class="label-col mono" :title="`物理列名：${r.column}`">{{ r.column }}</span>
            <el-icon class="label-arrow"><Right /></el-icon>
            <el-input
              v-model="labelForm[r.column]"
              size="small"
              maxlength="64"
              :placeholder="r.fallback"
              clearable
            />
          </div>
          <span class="label-tip">
            汇总/派生列可在此取业务叫法，随指标保存并用于驾驶舱/大屏；置空或与默认名一致则不额外存储。
          </span>
        </div>
      </el-form-item>
    </el-form>
    <p class="tip muted">
      <el-icon><InfoFilled /></el-icon>
      指标以已存 SQL 实时重跑取数，可随时加入驾驶舱，不消耗大模型额度。
    </p>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.label-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.label-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.label-col {
  width: 118px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.label-arrow {
  flex-shrink: 0;
  color: var(--ikb-lighter);
}
.label-tip {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}
.tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 2px 0 0;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.tip .el-icon {
  color: var(--ikb-lighter);
}
</style>
