<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi } from '@/api/dashboard'
import type { ImageWidgetSpec } from '@/types'

/**
 * 图片/图标组件编辑器：上传（复用大屏素材上传接口）或填写外链 URL，选择填充方式与是否圆形裁切。
 * 编辑模式（spec 非空）预填当前配置；新建模式从空白开始。确认后把 {@link ImageWidgetSpec} 回传父组件落库。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    spec?: ImageWidgetSpec | null
  }>(),
  { modelValue: false, spec: null }
)
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'confirm', spec: ImageWidgetSpec): void
}>()

const form = ref<ImageWidgetSpec>({ url: '', fit: 'contain', round: false, alt: '' })
const uploading = ref(false)

watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      const s = props.spec
      form.value = { url: s?.url || '', fit: s?.fit || 'contain', round: !!s?.round, alt: s?.alt || '' }
    }
  }
)

function beforeUpload(file: File): boolean {
  if (!/\.(png|jpe?g|webp|gif|svg)$/i.test(file.name)) {
    ElMessage.error('仅支持图片：png/jpg/jpeg/webp/gif/svg')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB')
    return false
  }
  return true
}
function upload(req: { file: File }): void {
  uploading.value = true
  dashboardApi
    .uploadMaterial(req.file)
    .then((res) => {
      form.value.url = res.url
      ElMessage.success('图片已上传')
    })
    .catch(() => {
      // 拦截器已提示
    })
    .finally(() => {
      uploading.value = false
    })
}
function onConfirm(): void {
  if (!form.value.url || !form.value.url.trim()) {
    ElMessage.warning('请上传或填写图片地址')
    return
  }
  emit('confirm', {
    url: form.value.url.trim(),
    fit: form.value.fit || 'contain',
    round: form.value.round || null,
    alt: form.value.alt?.trim() || null
  })
  emit('update:modelValue', false)
}
function onClose(): void {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="图片 / 图标"
    width="420px"
    @update:model-value="onClose"
    @close="onClose"
  >
    <div class="img-dialog">
      <p class="id-label">图片（上传或用 URL）</p>
      <div class="id-upload-row">
        <el-upload
          class="id-upload"
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="upload"
          accept="image/*"
        >
          <el-button size="small" :loading="uploading">选择图片</el-button>
        </el-upload>
        <span class="id-or muted">或</span>
      </div>
      <el-input v-model="form.url" size="small" placeholder="填写图片 URL（如对象存储返回的地址）" />
      <p class="id-label">填充方式</p>
      <el-radio-group v-model="form.fit" size="small">
        <el-radio value="contain">完整（不裁切）</el-radio>
        <el-radio value="cover">裁满</el-radio>
        <el-radio value="fill">拉伸</el-radio>
      </el-radio-group>
      <div class="id-row">
        <span class="id-label">圆形裁切（logo/头像）</span>
        <el-switch v-model="form.round" />
      </div>
      <p class="id-label">替代文本（可选）</p>
      <el-input v-model="form.alt" size="small" placeholder="图片加载失败时的兜底文案" />
      <div v-if="form.url" class="id-preview">
        <img :src="form.url" :alt="form.alt || '预览'" :style="{ borderRadius: form.round ? '50%' : '0' }" />
      </div>
    </div>
    <template #footer>
      <el-button size="small" @click="onClose">取消</el-button>
      <el-button size="small" type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.img-dialog {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.id-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
  margin: 4px 0 0;
}
.id-upload-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.id-or {
  font-size: 12px;
}
.id-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.id-preview {
  margin-top: 4px;
  padding: 8px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  display: flex;
  justify-content: center;
  background: var(--bg-card);
}
.id-preview img {
  max-width: 100%;
  max-height: 120px;
  object-fit: contain;
}
</style>
