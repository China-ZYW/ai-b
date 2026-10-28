<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { shareApi } from '@/api/share'
import { copyText } from '@/utils/clipboard'
import type { ChartSpec, QueryResult } from '@/types'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 分享对话框：提交结果快照创建分享链接，支持访问密码 / 有效期 / 水印。
 * 创建成功后展示可复制的公开链接。
 */
const props = defineProps<{
  modelValue: boolean
  datasourceId: number | null
  question: string
  sql?: string | null
  result: QueryResult | null
  chart?: ChartSpec | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'created'): void
}>()

const { busy: submitting, run: runSubmit } = useActionGuard()
const createdUrl = ref<string | null>(null)
const form = reactive<{ password: string; expireHours: number; watermark: boolean }>({
  password: '',
  expireHours: 0,
  watermark: true
})

// 注意：el-select 的「空值」是 null/undefined/''，若把"永久有效"的 value 设为 null，
// 选中后 select 会回退显示 placeholder（看起来像没选上）。故用 0 作哨兵，提交时映射回 null。
const EXPIRE_PERMANENT = 0
const expireOptions = [
  { label: '永久有效', value: EXPIRE_PERMANENT },
  { label: '1 小时', value: 1 },
  { label: '24 小时', value: 24 },
  { label: '7 天', value: 168 },
  { label: '30 天', value: 720 }
]

// 每次打开重置表单与结果
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      createdUrl.value = null
      form.password = ''
      form.expireHours = EXPIRE_PERMANENT
      form.watermark = true
    }
  }
)

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  if (!props.result || props.datasourceId == null) {
    ElMessage.warning('缺少可分享的结果')
    return
  }
  await runSubmit(async () => {
    try {

      const vo = await shareApi.create({
        datasourceId: props.datasourceId!,
        question: props.question,
        sql: props.sql ?? null,
        result: props.result!,
        chart: props.chart ?? null,
        password: form.password.trim() || null,
        expireHours: form.expireHours === EXPIRE_PERMANENT ? null : form.expireHours,
        watermark: form.watermark
      })
      createdUrl.value = vo.url?.startsWith('http')
        ? vo.url
        : `${window.location.origin}${vo.url || `/s/${vo.token}`}`
      ElMessage.success('分享链接已生成')
      emit('created')
    } catch {
      // 错误提示已由拦截器处理
    }
  })
}

async function copyUrl(): Promise<void> {
  if (!createdUrl.value) return
  const ok = await copyText(createdUrl.value)
  if (ok) ElMessage.success('链接已复制')
  else ElMessage.warning('复制失败，请手动选择')
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="分享查询结果"
    width="480px"
    align-center
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <!-- 创建成功后展示链接 -->
    <div v-if="createdUrl" class="share-done">
      <el-icon class="done-icon" :size="42"><CircleCheckFilled /></el-icon>
      <p class="done-title">分享链接已就绪</p>
      <el-input :model-value="createdUrl" readonly class="url-input">
        <template #append>
          <el-button @click="copyUrl"><el-icon><CopyDocument /></el-icon> 复制</el-button>
        </template>
      </el-input>
      <p class="done-hint">任何人拿到链接即可查看该结果快照</p>
    </div>

    <!-- 表单 -->
    <el-form v-else label-width="86px" class="share-form">
      <el-form-item label="结果问题">
        <div class="q-preview truncate">{{ question || '—' }}</div>
      </el-form-item>
      <el-form-item label="访问密码">
        <el-input
          v-model="form.password"
          placeholder="留空则无需密码"
          maxlength="32"
          show-password
          clearable
        />
      </el-form-item>
      <el-form-item label="有效期">
        <el-select v-model="form.expireHours" placeholder="选择有效期" style="width: 100%">
          <el-option
            v-for="o in expireOptions"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value as number | null"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="水印">
        <el-switch v-model="form.watermark" active-text="添加分享水印" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">{{ createdUrl ? '关闭' : '取消' }}</el-button>
      <el-button v-if="!createdUrl" type="primary" :loading="submitting" @click="submit">
        生成链接
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.share-form .q-preview {
  max-width: 320px;
  color: var(--text-regular);
  font-size: 13px;
}
.share-done {
  text-align: center;
  padding: 6px 0 2px;
}
.done-icon {
  color: var(--success);
}
.done-title {
  font-size: 16px;
  font-weight: 700;
  margin: 10px 0 16px;
}
.url-input :deep(.el-input__inner) {
  font-size: 12.5px;
}
.done-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
