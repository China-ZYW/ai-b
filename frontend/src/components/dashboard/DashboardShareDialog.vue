<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi } from '@/api/dashboard'
import { copyText } from '@/utils/clipboard'
import type { DashboardShareVO } from '@/types'
import { formatDateTime } from '@/utils/format'
import { usePerm } from '@/composables/usePerm'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 大屏分享对话框（问题2）：开启 / 更新 / 关闭公开访问链接，让大屏能像指标结果一样被分享。
 * 与结果分享（快照）不同，大屏分享是「实时」的——访问者看到的是按 refreshSeconds 刷新的最新数据。
 * 支持访问密码与有效期；关闭后旧链接立即失效。
 */
const props = defineProps<{
  modelValue: boolean
  dashboardId: number | null
  dashboardName?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'changed'): void
}>()

const { can } = usePerm()

const loadingStatus = ref(false)
const { busy: submitting, run: runSubmit } = useActionGuard()
const current = ref<DashboardShareVO | null>(null)
const createdUrl = ref<string | null>(null)
const form = reactive<{ enabled: boolean; password: string; expireHours: number }>({
  enabled: true,
  password: '',
  expireHours: 0
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

/** 拼出可复制的完整公开链接（后端 url 为空时按约定路径回落）。 */
function buildFullUrl(vo: DashboardShareVO): string | null {
  if (!vo.token) return null
  if (vo.url?.startsWith('http')) return vo.url
  return `${window.location.origin}${vo.url || `/share/screen/${vo.token}`}`
}

/** 打开时拉取当前分享状态预填（密码不回填明文，仅提示已设置）。 */
async function loadStatus(): Promise<void> {
  if (props.dashboardId == null) return
  loadingStatus.value = true
  try {
    const vo = await dashboardApi.shareStatus(props.dashboardId)
    current.value = vo
    form.enabled = vo.enabled ?? false
    form.password = ''
    createdUrl.value = vo.enabled ? buildFullUrl(vo) : null
  } catch {
    // 拦截器已提示
  } finally {
    loadingStatus.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      current.value = null
      createdUrl.value = null
      form.enabled = true
      form.password = ''
      form.expireHours = EXPIRE_PERMANENT
      void loadStatus()
    }
  }
)

function close(): void {
  emit('update:modelValue', false)
}

async function submit(): Promise<void> {
  if (props.dashboardId == null) {
    ElMessage.warning('缺少大屏标识')
    return
  }
  await runSubmit(async () => {
    try {

      const vo = await dashboardApi.configShare(props.dashboardId!, {
        enabled: form.enabled,
        password: form.enabled ? form.password.trim() || null : null,
        expireHours: form.enabled ? (form.expireHours === EXPIRE_PERMANENT ? null : form.expireHours) : null
      })
      current.value = vo
      if (vo.enabled) {
        createdUrl.value = buildFullUrl(vo)
        ElMessage.success('公开访问已开启')
      } else {
        createdUrl.value = null
        ElMessage.success('已关闭公开访问，旧链接随即失效')
      }
      emit('changed')
    } catch {
      // 拦截器已提示
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
    title="分享数据大屏"
    width="500px"
    align-center
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-loading="loadingStatus">
      <el-form label-width="88px" class="share-form">
        <el-form-item label="大屏">
          <div class="q-preview truncate">{{ dashboardName || '—' }}</div>
        </el-form-item>
        <el-form-item label="公开分享">
          <el-switch v-model="form.enabled" active-text="开启公开访问链接" />
        </el-form-item>

        <template v-if="form.enabled">
          <el-form-item label="访问密码">
            <el-input
              v-model="form.password"
              :placeholder="current?.hasPassword ? '已设密码，留空则保持不变' : '留空则无需密码'"
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
        </template>
      </el-form>

      <!-- 已开启：展示可复制链接 -->
      <div v-if="form.enabled && createdUrl" class="share-done">
        <el-input :model-value="createdUrl" readonly class="url-input">
          <template #append>
            <el-button @click="copyUrl"><el-icon><CopyDocument /></el-icon> 复制</el-button>
          </template>
        </el-input>
        <p class="done-hint">
          <el-icon><InfoFilled /></el-icon>
          任何人拿到链接即可<b>实时</b>查看该大屏
          <template v-if="current?.expireAt">（有效期至 {{ formatDateTime(current.expireAt) }}）</template>
        </p>
      </div>
      <p v-else-if="!form.enabled" class="off-hint">
        <el-icon><InfoFilled /></el-icon>
        关闭后，此前分享的链接将立即失效。
      </p>
    </div>

    <template #footer>
      <el-button @click="close">关闭</el-button>
      <el-button v-if="can('dashboard:screen:publish')" type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.share-form .q-preview {
  max-width: 320px;
  color: var(--text-regular);
  font-size: 13px;
  font-weight: 600;
}
.share-done {
  margin-top: 6px;
}
.url-input :deep(.el-input__inner) {
  font-size: 12.5px;
}
.done-hint,
.off-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}
.done-hint .el-icon,
.off-hint .el-icon {
  color: var(--ikb-lighter);
  flex-shrink: 0;
}
</style>
