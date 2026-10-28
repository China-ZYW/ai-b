<script setup lang="ts">
/**
 * 轻量 SQL 编辑器（P1/P2）。
 *
 * <b>为什么不用 Monaco / CodeMirror</b>：整量引入会让首屏多出 1MB+ 依赖，而这个编辑器只服务于
 * "会写 SQL 的分析师"这一小群人——对他们来说<b>高亮 + 补全 + 只读护栏提示</b>已经够用，
 * 拖拽表结构才需要重型编辑器。因此这里用「透明 textarea 叠在高亮层之上」的成熟做法实现，
 * 字体/行高/内边距三件套与高亮层严格对齐，滚动时同步位移。
 *
 * <b>补全走后端 SPI</b>（{@link completeSql}）：表结构、语义中文别名、SQL 关键字由服务端聚合，
 * 日后加"历史片段""关联表推荐"只需后端加 Bean，本组件零改动。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { completeSql, type CompletionItem } from '@/api/query'

const props = withDefaults(
  defineProps<{
    modelValue: string
    /** 当前数据源 id（补全需要知道该扫哪张库的 schema） */
    datasourceId?: number | null
    /** 行高固定，仅纵向滚动 */
    height?: string
    readonly?: boolean
  }>(),
  { datasourceId: null, height: '320px', readonly: false }
)

const emit = defineEmits<{ 'update:modelValue': [string] }>()

const SQL_KEYWORDS = [
  'SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'OFFSET',
  'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'OUTER JOIN', 'ON', 'AS',
  'AND', 'OR', 'NOT', 'IN', 'EXISTS', 'BETWEEN', 'LIKE', 'IS NULL', 'IS NOT NULL',
  'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'UNION', 'UNION ALL', 'WITH',
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'CAST', 'NULL', 'ASC', 'DESC'
]

const TOKEN_RE = new RegExp(
  [
    '(--[^\\n]*|/\\*[\\s\\S]*?\\*/)',     // 1 注释
    "('(?:[^']|'')*'|\"(?:[^\"]|\"\")*\")", // 2 字符串（单/双引号）
    '(`[^`]*`)',                            // 3 反引号标识符（列名/表名）
    '(\\b\\d+(?:\\.\\d+)?\\b)',            // 4 数字
    `(\\b(?:${SQL_KEYWORDS.join('|').replace(/ /g, '\\s+')})\\b)` // 5 关键字
  ].join('|'),
  'gi'
)

const escapeHtml = (s: string) =>
  s.replace(/[&<>]/g, (c) => (c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'))

/**
 * 高亮：转义 → 按词法着色（注释/字符串/反引号标识符/数字/关键字五色）。
 * 正则与后端 SqlTemplateParser 同构（注释/引号优先于关键字——避免把 `count` 字面量里的 COUNT 当关键字）。
 * 所有捕获片段均先于 replace 转义，染色的 <span> 由本函数自己产出，绝不把用户输入当 HTML 注入。
 */
const highlighted = computed(() => {
  const raw = props.modelValue ?? ''
  return escapeHtml(raw).replace(
    TOKEN_RE,
    (m, _c, _s, _id, _n, kw) => {
      if (_c) return `<span class="tk-comment">${m}</span>`
      if (_s) return `<span class="tk-string">${m}</span>`
      if (_id) return `<span class="tk-ident">${m}</span>`
      if (_n) return `<span class="tk-number">${m}</span>`
      if (kw) return `<span class="tk-keyword">${m}</span>`
      return m
    }
  )
})

const taRef = ref<HTMLTextAreaElement | null>(null)
const hlRef = ref<HTMLElement | null>(null)

/**
 * 滚动同步：textarea 是唯一可滚层（高亮层 pointer-events:none 且绝对定位），
 * 用户滚动 textarea 时必须把位移同步给高亮层，否则两层错位、文字叠成乱码。
 * 软换行（pre-wrap）下没有横向位移，但纵向仍可能超出，因此两轴都同步。
 */
const onScroll = () => {
  const ta = taRef.value
  const hl = hlRef.value
  if (ta && hl) {
    hl.scrollTop = ta.scrollTop
    hl.scrollLeft = ta.scrollLeft
  }
}
const innerText = ref(props.modelValue)

watch(
  () => props.modelValue,
  (v) => {
    if (v !== innerText.value) innerText.value = v
  }
)

const onInput = () => {
  innerText.value = taRef.value?.value ?? ''
  emit('update:modelValue', innerText.value)
  scheduleCompletion()
}

const onKeydown = (e: KeyboardEvent) => {
  // Ctrl/Cmd + Space：显式唤出补全（不打断输入）
  if ((e.ctrlKey || e.metaKey) && e.code === 'Space') {
    e.preventDefault()
    void fetchCompletion()
    return
  }
  if (showList.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      active.value = (active.value + 1) % items.value.length
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      active.value = (active.value - 1 + items.value.length) % items.value.length
      return
    }
    if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault()
      applyItem(items.value[active.value])
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      showList.value = false
      return
    }
  }
  // Tab 缩进：编辑器里敲 Tab 不该把焦点跳走
  if (e.key === 'Tab' && !e.ctrlKey && !e.metaKey) {
    e.preventDefault()
    insertAtCursor('  ')
    return
  }
  scheduleCompletion()
}

// ============ 补全 ============

const items = ref<CompletionItem[]>([])
const showList = ref(false)
const active = ref(0)
const loading = ref(false)
const popRef = ref<HTMLElement | null>(null)
let debounce: number | undefined

/**
 * 键盘移动高亮项后，把当前 .on 项滚入弹框可视区。
 * 弹框（.se-pop）是 overflow:auto 的滚动容器，列表一长时 active 变化不会自动滚动，
 * 导致最后几项永远在可视区外、既看不见也选不到（用户反馈"下键无法移动滚动条选最后一个"）。
 * block:'nearest' 只在必要时滚动，已在可视区时不抖动。
 */
watch(active, () => {
  nextTick(() => {
    const el = popRef.value?.querySelector<HTMLElement>('li.on')
    el?.scrollIntoView({ block: 'nearest' })
  })
})

/** 光标前的文本（用于推断前缀与当前表）。 */
const textBeforeCaret = () => (taRef.value?.value ?? '').slice(0, taRef.value?.selectionStart ?? 0)

/** 从 FROM 子句推断当前表名：补全列时用来把候选收敛到这一张表。 */
const currentTable = () => {
  const before = textBeforeCaret()
  const m = [...before.matchAll(/\bfrom\s+([a-zA-Z_`"[\]][\w.`"\]-]*)/gi)].pop()
  return m?.[1]?.replace(/[`"[\]]/g, '') ?? null
}

const currentPrefix = () => {
  const before = textBeforeCaret()
  const m = before.match(/([a-zA-Z_][\w]*)$/)
  return m?.[1] ?? ''
}

async function fetchCompletion() {
  if (!props.datasourceId) {
    showList.value = false
    return
  }
  const prefix = currentPrefix()
  loading.value = true
  try {
    const list = await completeSql({
      datasourceId: props.datasourceId,
      prefix,
      tableName: currentTable()
    })
    items.value = list ?? []
    active.value = 0
    showList.value = items.value.length > 0
  } catch {
    showList.value = false
  } finally {
    loading.value = false
  }
}

function scheduleCompletion() {
  window.clearTimeout(debounce)
  // 连续输入时防抖；短暂停顿后自动唤出，无需用户记快捷键
  debounce = window.setTimeout(() => {
    if (currentPrefix().length >= 1) void fetchCompletion()
    else showList.value = false
  }, 320)
}

function applyItem(it: CompletionItem | undefined) {
  if (!it) return
  const ta = taRef.value
  if (!ta) return
  const before = ta.value.slice(0, ta.selectionStart)
  const after = ta.value.slice(ta.selectionEnd)
  // 截掉刚输入的那个前缀片段（它已经在候选里被展示过）
  const prefix = currentPrefix()
  const replaced = before.slice(0, before.length - prefix.length) + it.text
  ta.value = replaced + after
  const caret = replaced.length
  ta.selectionStart = caret
  ta.selectionEnd = caret
  innerText.value = ta.value
  emit('update:modelValue', ta.value)
  showList.value = false
  nextTick(() => ta.focus())
}

function insertAtCursor(text: string) {
  const ta = taRef.value
  if (!ta) return
  const before = ta.value.slice(0, ta.selectionStart)
  const after = ta.value.slice(ta.selectionEnd)
  ta.value = before + text + after
  const caret = before.length + text.length
  ta.selectionStart = caret
  ta.selectionEnd = caret
  innerText.value = ta.value
  emit('update:modelValue', ta.value)
}

onBeforeUnmount(() => window.clearTimeout(debounce))

defineExpose({ insertAtCursor })
</script>

<template>
  <div class="sql-editor">
    <div class="se-body" :style="{ height }">
      <!-- 高亮层：与 textarea 完全重叠，仅做着色，不可交互；滚动位移由 onScroll 同步 -->
      <pre ref="hlRef" class="se-hl" aria-hidden="true" v-html="highlighted" />
      <textarea
        ref="taRef"
        class="se-ta"
        :value="innerText"
        :readonly="readonly"
        spellcheck="false"
        @input="onInput"
        @keydown="onKeydown"
        @scroll="onScroll"
      />
      <div v-if="showList" ref="popRef" class="se-pop">
        <div class="se-pop-hd">
          补全<span v-if="loading">…</span>
          <em>Ctrl+Space 刷新 · ↑↓ 选择 · Enter 插入</em>
        </div>
        <ul>
          <li
            v-for="(it, i) in items"
            :key="`${it.text}-${i}`"
            :class="{ on: i === active }"
            @mouseenter="active = i"
            @click="applyItem(it)"
          >
            <span class="se-pop-label">{{ it.label }}</span>
            <span class="se-pop-detail">{{ it.detail }}</span>
            <span class="se-pop-kind">{{ it.kind }}</span>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sql-editor {
  position: relative;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 6px;
  overflow: hidden;
  background: var(--bg-card);
}
.se-body {
  position: relative;
  overflow: hidden;
}
.se-hl,
.se-ta {
  margin: 0;
  padding: 10px 12px;
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 20px;
  tab-size: 2;
  /* 软换行：两层必须用同一套换行规则（pre-wrap + break-all），否则长行处错位叠字 */
  white-space: pre-wrap;
  word-break: break-all;
  overflow-wrap: anywhere;
  overflow: auto;
  border: 0;
  box-sizing: border-box;
}
.se-hl {
  position: absolute;
  inset: 0;
  pointer-events: none;
  color: #24292f;
  z-index: 1;
}
.se-ta {
  position: relative;
  z-index: 2;
  display: block;
  width: 100%;
  height: 100%;
  resize: none;
  background: transparent;
  color: transparent;
  caret-color: #24292f;
  outline: none;
}
.se-ta::selection {
  background: rgba(47, 128, 237, 0.24);
}
/* 注意：.tk-* 高亮配色不能放 scoped 块——v-html 注入的内容不带 data-v 属性，
   scoped 编译出的 .tk-keyword[data-v-xxx] 永远匹配不上（高亮曾因此从未生效）。
   配色规则在本文件末尾的非 scoped <style> 里，用 .sql-editor 前缀兜住作用域。 */
.se-pop {
  position: absolute;
  right: 8px;
  bottom: 8px;
  z-index: 5;
  width: 320px;
  max-height: 220px;
  overflow: auto;
  background: var(--bg-card);
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 6px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
}
.se-pop-hd {
  position: sticky;
  top: 0;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  background: var(--fill-light);
  border-bottom: 1px solid var(--el-border-color, #dcdfe6);
}
.se-pop-hd em {
  float: right;
  font-style: normal;
  color: var(--el-text-color-secondary, #909399);
}
.se-pop ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
.se-pop li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  font-size: 13px;
  cursor: pointer;
}
.se-pop li.on {
  background: var(--el-color-primary-light-9, #ecf5ff);
}
.se-pop-label {
  font-family: 'JetBrains Mono', Consolas, monospace;
  color: #24292f;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.se-pop-detail {
  flex: 1;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.se-pop-kind {
  color: var(--el-text-color-placeholder, #c0c4cc);
  font-size: 11px;
}
</style>

<!-- 非 scoped：v-html 注入的高亮 span 不带 data-v 属性，scoped 规则（.tk-x[data-v-y]）永远匹配不上。
     这里用 .sql-editor 前缀兜住作用域，既让高亮生效又不污染全局。 -->
<style>
.sql-editor .tk-keyword {
  color: #0550ae;
  font-weight: 600;
}
.sql-editor .tk-number {
  color: #953800;
}
.sql-editor .tk-string {
  color: #0a7d3b;
}
.sql-editor .tk-ident {
  color: #6f42c1;
}
.sql-editor .tk-comment {
  color: #8b949e;
  font-style: italic;
}
</style>
