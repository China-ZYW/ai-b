<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { manualApi } from '@/api/manual'
import type { PublishedManualVO } from '@/types'
import { formatDateTime } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'
import { renderDiagrams } from '@/utils/mermaid'

/**
 * 用户手册阅读页（全体用户）：展示超管发布的已发布版手册。
 * 正文按 Markdown 渲染，并自动抽取 h2/h3 生成左侧目录(TOC)，支持：
 *  - 点击目录项平滑滚动定位到对应章节；
 *  - 右侧正文滚动时高亮当前所在章节（scroll-spy）；
 *  - URL `#hash` 深链（分享带 #章节 的链接打开即定位）。
 */
interface TocItem {
  id: string
  text: string
  level: number
}

const loading = ref(false)
const manual = ref<PublishedManualVO | null>(null)
const articleRef = ref<HTMLElement | null>(null)
const contentRef = ref<HTMLElement | null>(null)
const toc = ref<TocItem[]>([])
const activeId = ref('')
const keyword = ref('')

const html = computed(() => renderMarkdown(manual.value?.content))

/** 目录过滤（顶部搜索框按章节名模糊定位）。 */
const filteredToc = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return toc.value
  return toc.value.filter((t) => t.text.toLowerCase().includes(k))
})

/** 渲染完成后从正文抽取 h2/h3 标题与锚点 id，构建目录。 */
function buildToc(): void {
  const root = articleRef.value
  if (!root) {
    toc.value = []
    return
  }
  const heads = Array.from(root.querySelectorAll('h2, h3')) as HTMLElement[]
  toc.value = heads
    .filter((h) => h.id)
    .map((h) => ({
      id: h.id,
      text: (h.textContent || '').replace(/\s+/g, ' ').trim(),
      level: Number(h.tagName.slice(1))
    }))
}

/** 滚动高亮：取最后一个已滚过顶部阈值(120px)的章节为当前激活项。 */
function syncActive(): void {
  let cur = ''
  for (const it of toc.value) {
    const el = document.getElementById(it.id)
    if (el && el.getBoundingClientRect().top <= 120) cur = it.id
  }
  activeId.value = cur || toc.value[0]?.id || ''
}

/** 点击目录 / 深链定位：平滑滚动到目标章节并同步 hash（不触发整页路由刷新）。 */
function go(id: string): void {
  const el = document.getElementById(id)
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  history.replaceState(null, '', '#' + id)
  activeId.value = id
}

async function load(): Promise<void> {
  loading.value = true
  try {
    manual.value = await manualApi.published()
    await nextTick()
    buildToc()
    syncActive()
    // 流程图渲染：mermaid 需在 DOM 插入后由 JS 触发（v-html 不自动执行）
    await renderDiagrams(articleRef.value)
    // hash 深链：带 #章节 打开时直接定位
    const hash = decodeURIComponent(location.hash.replace(/^#/, ''))
    if (hash && document.getElementById(hash)) {
      await nextTick()
      go(hash)
    }
  } catch {
    manual.value = null
  } finally {
    loading.value = false
  }
}

function onScroll(): void {
  syncActive()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="manual-view">
    <!-- 左侧目录（锚定定位） -->
    <aside v-if="toc.length" class="toc">
      <el-input
        v-model="keyword"
        class="toc-search"
        size="small"
        clearable
        placeholder="定位章节…"
        :prefix-icon="'Search'"
      />
      <p class="toc-title">本手册目录</p>
      <ul class="toc-list">
        <li v-for="t in filteredToc" :key="t.id">
          <button
            type="button"
            class="toc-item"
            :class="[`lv${t.level}`, { active: t.id === activeId }]"
            :title="t.text"
            @click="go(t.id)"
          >
            {{ t.text }}
          </button>
        </li>
        <li v-if="!filteredToc.length" class="toc-empty">无匹配章节</li>
      </ul>
    </aside>

    <!-- 右侧正文 -->
    <section ref="contentRef" class="content" @scroll="onScroll">
      <div class="page-head">
        <div>
          <h2 class="ph-title">{{ manual?.title || '用户手册' }}</h2>
          <p class="ph-desc">
            <template v-if="manual">
              版本 v{{ manual.version ?? 1 }}
              <span v-if="manual.publishedAt"> · 发布于 {{ formatDateTime(manual.publishedAt) }}</span>
            </template>
            <template v-else>平台使用指南</template>
          </p>
        </div>
        <el-button text @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>

      <div v-loading="loading" class="manual-card card">
        <article v-if="manual && html" ref="articleRef" class="md-body" v-html="html" />
        <el-empty v-else-if="!loading" description="用户手册尚未发布，请联系平台管理员" :image-size="120" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.manual-view {
  height: 100%;
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
}

/* ---------- 左侧目录 ---------- */
.toc {
  height: 100%;
  overflow-y: auto;
  border-right: 1px solid var(--border-light, #eef0f6);
  padding: 18px 14px 40px;
  background: var(--bg-soft, #fafbff);
}
.toc-search {
  margin-bottom: 12px;
}
.toc-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
  margin: 2px 0 10px;
  letter-spacing: 0.04em;
}
.toc-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.toc-item {
  display: block;
  width: 100%;
  text-align: left;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text-regular);
  padding: 5px 8px;
  border-radius: 6px;
  border-left: 2px solid transparent;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.toc-item:hover {
  background: var(--el-fill-color, #f2f3f7);
  color: var(--ikb, #002fa7);
}
.toc-item.active {
  color: var(--ikb, #002fa7);
  font-weight: 700;
  background: var(--ikb-ghost, #eef3ff);
  border-left-color: var(--ikb, #002fa7);
}
.toc-item.lv2 {
  padding-left: 8px;
}
.toc-item.lv3 {
  padding-left: 22px;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.toc-empty {
  font-size: 12px;
  color: var(--text-placeholder);
  padding: 8px;
}

/* ---------- 右侧正文 ---------- */
.content {
  height: 100%;
  overflow-y: auto;
  padding: 22px 26px 40px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
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
.manual-card {
  padding: 28px 34px;
  max-width: 920px;
}

/* ---------- Markdown 正文排版 ---------- */
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
  scroll-margin-top: 16px;
}
.md-body :deep(h3) {
  font-size: 16px;
  font-weight: 700;
  margin: 20px 0 8px;
  scroll-margin-top: 16px;
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
/* 围栏代码块（```sql / ``` 等）：等宽、浅底、可横向滚动 */
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
/* mermaid 流程图容器：居中、留白、防溢出 */
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
  color: var(--text-primary);
}
/* GFM 表格：细边框 + 表头浅底 + 斑马纹，窄屏横向滚动不撑破布局 */
.md-body :deep(table) {
  width: 100%;
  margin: 16px 0;
  border-collapse: collapse;
  display: block;
  overflow-x: auto;
  font-size: 14px;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--border-light, #e6e9f0);
  padding: 8px 12px;
  text-align: left;
  line-height: 1.6;
  white-space: nowrap;
}
.md-body :deep(th) {
  background: var(--ikb-ghost, #f2f6ff);
  font-weight: 600;
  color: var(--text-primary);
}
.md-body :deep(tbody tr:nth-child(even)) {
  background: rgba(0, 47, 167, 0.03);
}
</style>
