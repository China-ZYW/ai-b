<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminManualApi } from '@/api/manual'
import type { ManualVO } from '@/types'
import { formatDateTime } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'
import { renderDiagrams } from '@/utils/mermaid'
import { useActionGuard } from '@/utils/actionGuard'

/**
 * 用户手册管理页（超级管理员）：编辑草稿 → 预览 → 保存草稿 → 发布。
 *
 * 「保存草稿」只写草稿列，线上用户看到的仍是旧的已发布版；「发布」才把草稿拷贝为发布版、version+1。
 * 这套「先改后发布」语义由后端 user_manual 的草稿/发布双列保证，前端据 status 展示是否有未发布改动。
 */
const loading = ref(false)
const { busy: saving, run: runSave } = useActionGuard()
const { busy: publishing, run: runPublish } = useActionGuard()
const preview = ref(false)
const previewRef = ref<HTMLElement | null>(null)

/** 进入预览态后，正文经 v-html 插入，需手动触发 mermaid 流程图渲染。 */
watch(
  () => [preview.value, form.content],
  async () => {
    if (preview.value) {
      await nextTick()
      await renderDiagrams(previewRef.value)
    }
  }
)

const form = reactive<{ title: string; content: string }>({ title: '', content: '' })
const meta = ref<{ status: string; version: number; published: boolean; updatedAt?: string | null; publishedAt?: string | null }>({
  status: 'DRAFT',
  version: 0,
  published: false,
  updatedAt: null,
  publishedAt: null
})

/** 最近一次加载/保存后的草稿快照，用于判断是否有未保存改动。 */
let savedSnapshot = ''

const dirty = computed(() => `${form.title}\n${form.content}` !== savedSnapshot)
const previewHtml = computed(() => renderMarkdown(form.content))
const isDraftStatus = computed(() => meta.value.status === 'DRAFT')

function applyVO(vo: ManualVO): void {
  form.title = vo.title ?? ''
  form.content = vo.content ?? ''
  meta.value = {
    status: vo.status ?? 'DRAFT',
    version: vo.version ?? 0,
    published: !!vo.published,
    updatedAt: vo.updatedAt ?? null,
    publishedAt: vo.publishedAt ?? null
  }
  savedSnapshot = `${form.title}\n${form.content}`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    applyVO(await adminManualApi.get())
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function onSaveDraft(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  await runSave(async () => {
    try {

      applyVO(await adminManualApi.saveDraft({ title: form.title.trim(), content: form.content }))
      ElMessage.success('草稿已保存（线上发布版未变）')
    } catch {
      // 拦截器已提示
    }
  })
}

async function onPublish(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '发布后全体用户将立即看到当前草稿内容。若草稿有未保存改动，会先自动保存再发布。确定发布吗？',
      '发布确认',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await runPublish(async () => {
    try {

      // 发布前先落草稿，避免"编辑了但没保存就发布"导致发布的是旧草稿
      if (dirty.value) {
        applyVO(await adminManualApi.saveDraft({ title: form.title.trim(), content: form.content }))
      }
      applyVO(await adminManualApi.publish())
      ElMessage.success(`已发布 v${meta.value.version}`)
    } catch {
      // 拦截器已提示
    }
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="manual-admin">
    <div class="page-head">
      <div>
        <h2 class="ph-title">用户手册</h2>
        <p class="ph-desc">编辑草稿并发布，发布后全体用户可在「用户手册」页阅读最新版本。</p>
      </div>
      <div class="head-actions">
        <el-tag :type="isDraftStatus ? 'warning' : 'success'" effect="light" round size="small">
          {{ isDraftStatus ? '有未发布改动' : '已发布' }}
        </el-tag>
        <el-tag type="info" effect="plain" round size="small">v{{ meta.version }}</el-tag>
        <el-button :icon="preview ? 'EditPen' : 'View'" text @click="preview = !preview">
          {{ preview ? '返回编辑' : '预览' }}
        </el-button>
        <el-button :loading="saving" :disabled="publishing" @click="onSaveDraft">保存草稿</el-button>
        <el-button type="primary" :loading="publishing" :disabled="saving" @click="onPublish">发布</el-button>
      </div>
    </div>

    <div v-loading="loading" class="body-card card">
      <div class="meta-row">
        <span v-if="meta.published && meta.publishedAt" class="meta-item">
          当前线上：v{{ meta.version }} · 发布于 {{ formatDateTime(meta.publishedAt) }}
        </span>
        <span v-else class="meta-item">尚未发布过，用户端暂时读不到手册</span>
        <span v-if="meta.updatedAt" class="meta-item">草稿更新于 {{ formatDateTime(meta.updatedAt) }}</span>
        <span v-if="dirty" class="meta-item warn">· 有未保存改动</span>
      </div>

      <!-- 编辑态 -->
      <template v-if="!preview">
        <el-form label-position="top">
          <el-form-item label="手册标题">
            <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="如：AI 数据助理用户手册" />
          </el-form-item>
          <el-form-item label="正文（支持 Markdown：# 标题、- 列表、**加粗**、> 引用、--- 分隔线）">
            <el-input
              v-model="form.content"
              type="textarea"
              :autosize="{ minRows: 22, maxRows: 40 }"
              placeholder="在此编写手册正文…"
              class="md-editor"
            />
          </el-form-item>
        </el-form>
      </template>

      <!-- 预览态 -->
      <article v-else ref="previewRef" class="md-body" v-html="previewHtml" />
    </div>
  </div>
</template>

<style scoped>
.manual-admin {
  height: 100%;
  overflow-y: auto;
  padding: 4px 4px 40px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
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
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.body-card {
  padding: 18px 22px 26px;
}
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  padding-bottom: 14px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--border-light, #eef0f6);
  font-size: 12.5px;
  color: var(--text-secondary);
}
.meta-item.warn {
  color: var(--el-color-warning, #e6a23c);
  font-weight: 600;
}
.md-editor :deep(textarea) {
  font-family: 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 13.5px;
  line-height: 1.7;
}

/* ---------- 预览排版（与用户端一致） ---------- */
.md-body {
  font-size: 14.5px;
  line-height: 1.85;
  color: var(--text-primary);
}
.md-body :deep(h1) {
  font-size: 24px;
  font-weight: 800;
  margin: 0 0 16px;
  padding-bottom: 12px;
  border-bottom: 2px solid var(--ikb-ghost, #e6ecff);
}
.md-body :deep(h2) {
  font-size: 19px;
  font-weight: 700;
  margin: 30px 0 12px;
  color: var(--ikb, #002fa7);
}
.md-body :deep(h3) {
  font-size: 16px;
  font-weight: 700;
  margin: 20px 0 8px;
}
.md-body :deep(p) {
  margin: 10px 0;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 10px 0;
  padding-left: 24px;
}
.md-body :deep(li) {
  margin: 5px 0;
}
.md-body :deep(blockquote) {
  margin: 14px 0;
  padding: 10px 16px;
  background: var(--ikb-ghost, #f2f6ff);
  border-left: 4px solid var(--ikb, #002fa7);
  border-radius: 0 var(--radius-md, 8px) var(--radius-md, 8px) 0;
  color: var(--text-regular);
}
.md-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--border-light, #eef0f6);
  margin: 24px 0;
}
.md-body :deep(code) {
  padding: 2px 6px;
  background: var(--el-fill-color, #f2f3f7);
  border-radius: 5px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
  color: #c7254e;
}
.md-body :deep(pre.md-pre) {
  margin: 14px 0;
  padding: 14px 16px;
  background: #0f1729;
  border: 1px solid var(--border-light, #e6e9f0);
  border-radius: var(--radius-md, 8px);
  overflow-x: auto;
  line-height: 1.6;
}
.md-body :deep(pre.md-pre code) {
  padding: 0;
  background: none;
  color: #e6edf6;
  font-size: 13px;
  white-space: pre;
}
.md-body :deep(.mermaid) {
  margin: 16px 0;
  padding: 12px;
  background: var(--bg-soft, #fafbff);
  border: 1px solid var(--border-light, #eef0f6);
  border-radius: var(--radius-md, 8px);
  overflow-x: auto;
  text-align: center;
}
.md-body :deep(.mermaid svg) {
  max-width: 100%;
  height: auto;
}
.md-body :deep(strong) {
  font-weight: 700;
}
</style>
