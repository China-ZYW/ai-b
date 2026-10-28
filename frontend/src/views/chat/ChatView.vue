<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useChatStore, type ChatMessage } from '@/stores/chat'
import { useConfigStore } from '@/stores/config'
import { useDatasourceStore } from '@/stores/datasource'
import { useUserStore } from '@/stores/user'
import { favoriteApi } from '@/api/favorite'
import { historyApi, type FeedbackCorrectionPayload } from '@/api/history'
import { chatApi } from '@/api/chat'
import ChatInput from '@/components/chat/ChatInput.vue'
import ResultPanel from '@/components/chat/ResultPanel.vue'
import ShareDialog from '@/components/chat/ShareDialog.vue'
import MetricSaveDialog from '@/components/chat/MetricSaveDialog.vue'
import { fromNow, displayColumns, pruneColumnLabels } from '@/utils/format'
import { pruneChartStyle } from '@/utils/chartStyle'
import type { ChartSpec, ChartStyle, ClarifyOption, QueryResult } from '@/types'

const chatStore = useChatStore()
const configStore = useConfigStore()
const dsStore = useDatasourceStore()
const userStore = useUserStore()
const route = useRoute()

const question = ref('')
const scrollEl = ref<HTMLElement | null>(null)

/** 已收藏问题的键集合（`${datasourceId}:${question}`），用于收藏态展示与去重提示（问题1）。 */
const favoritedKeys = ref<Set<string>>(new Set())

/** 推荐问法兜底（无数据源或生成失败时展示）。 */
const FALLBACK_SUGGESTIONS = [
  '每个地区的已支付订单总金额是多少',
  '最近 30 天每日新增用户趋势',
  '销量排名前十的商品有哪些',
  '各产品类目的库存占比'
]
/** 推荐问法（问题4）：进入页面 / 切换数据源时按数据源 schema 由后端 LLM 动态生成。 */
const suggestions = ref<string[]>([...FALLBACK_SUGGESTIONS])
const suggestionsLoading = ref(false)

/** 按当前数据源拉取推荐问题；失败或为空时回落到兜底问法（永不阻断）。 */
async function loadSuggestions(): Promise<void> {
  const dsId = dsStore.currentId
  if (dsId == null) {
    suggestions.value = [...FALLBACK_SUGGESTIONS]
    return
  }
  suggestionsLoading.value = true
  try {
    const list = await chatApi.suggestions(dsId)
    suggestions.value = list && list.length > 0 ? list : [...FALLBACK_SUGGESTIONS]
  } catch {
    suggestions.value = [...FALLBACK_SUGGESTIONS]
  } finally {
    suggestionsLoading.value = false
  }
}

// 数据源就绪 / 切换时刷新推荐问题
watch(
  () => dsStore.currentId,
  (id) => {
    if (id == null) suggestions.value = [...FALLBACK_SUGGESTIONS]
    else void loadSuggestions()
  },
  { immediate: true }
)

/** 上下文追问（按 historyId 缓存）：答案出完后由后端依本轮问答生成"接着往下钻"的问题。 */
const followupsByHistory = ref<Record<number, string[]>>({})

/** 拉取某轮问数的上下文追问；失败/空静默（展示层回落静态推荐）。 */
async function loadFollowups(historyId: number): Promise<void> {
  if (followupsByHistory.value[historyId]) return
  try {
    const list = await chatApi.followups(historyId)
    if (list && list.length > 0) followupsByHistory.value[historyId] = list
  } catch {
    // 追问是 UI 辅助，失败回落静态推荐即可
  }
}

/** 答案下方展示的推荐：优先本轮上下文追问，尚无/失败时回落数据源级静态推荐。 */
function suggestListOf(msg: ChatMessage): string[] {
  const hid = msg.response?.historyId
  const fu = hid != null ? followupsByHistory.value[hid] : undefined
  return fu && fu.length > 0 ? fu : suggestions.value
}

/** 分步进度中文标签。 */
const stageLabels: Record<string, string> = {
  start: '开始处理',
  route: '选择答问方式',
  understand: '理解追问上下文',
  guard: '识别提问类型',
  schema: '读取表结构',
  intent: '理解查询意图',
  generate: '生成 SQL',
  consistency: '多路候选择优',
  sql: 'SQL 就绪',
  execute: '执行查询',
  repair: '修复并重试',
  'chart-ai': 'AI 图表决策',
  chart: '生成可视化',
  insight: '生成结果解读',
  verify: '质量校验',
  done: '完成'
}
function stageLabel(s: string): string {
  return stageLabels[s] || s
}

// 分享对话框状态
const shareVisible = ref(false)
const shareTarget = ref<{
  question: string
  sql: string | null
  result: QueryResult | null
  chart: ChartSpec | null
} | null>(null)

// 存为指标对话框状态
const metricVisible = ref(false)
const metricTarget = ref<{
  question: string
  sql: string | null
  chart: ChartSpec | null
  result: QueryResult | null
  columnLabels: Record<string, string> | null
} | null>(null)

/**
 * 会话级列展示名自定义：消息 id → （物理列名 → 自定义名）。
 *
 * 汇总/派生列（{@code SUM(amount) AS total_amount}）在语义层没有物理列行，拿不到别名，
 * 用户可在结果表头就地改名；只存内存，“存为指标”时随 {@code metric.display_config} 持久化，
 * “分享”时并入结果快照，使公开页与本地看到同一套名字。
 */
const labelOverrides = ref<Record<string, Record<string, string>>>({})

function overridesOf(msg: ChatMessage): Record<string, string> | null {
  const ov = labelOverrides.value[msg.id]
  return ov && Object.keys(ov).length > 0 ? ov : null
}

/** 表头重命名（label 为空串表示清除该列自定义，回落语义层别名/列名）。 */
function onRenameColumn(msg: ChatMessage, column: string, label: string): void {
  const cur = { ...(labelOverrides.value[msg.id] ?? {}) }
  if (label.trim()) {
    cur[column] = label.trim()
  } else {
    delete cur[column]
  }
  labelOverrides.value = { ...labelOverrides.value, [msg.id]: cur }
}

/** 带上会话级重命名的结果（仅改写 columnLabels，columns/rows 不动，字段身份保持不变）。 */
function displayResult(msg: ChatMessage): QueryResult | null {
  const r = msg.response?.result ?? null
  const ov = overridesOf(msg)
  return r && ov ? { ...r, columnLabels: displayColumns(r, ov) } : r
}

/**
 * 会话级图表标注自定义：消息 id → 图表样式覆盖（轴名/单位/数值格式/图例/标签旋转）。
 *
 * 与 {@link labelOverrides} 同一套生命周期：只存内存，用户在结果面板的“图表设置”里改动即生效；
 * “存为指标”时并入 {@code metric.chart_spec.style} 持久化（指标预览与大屏 widget 据此渲染），
 * “分享”时并入图表快照，使公开页与本地看到同一套标注。
 */
const styleOverrides = ref<Record<string, ChartStyle | null>>({})

function styleOf(msg: ChatMessage): ChartStyle | null {
  return styleOverrides.value[msg.id] ?? null
}

/** 图表标注上抛（style 为 null 表示清除覆盖，回落 chart_spec 自带样式与租户级默认）。 */
function onUpdateChartStyle(msg: ChatMessage, style: ChartStyle | null): void {
  styleOverrides.value = { ...styleOverrides.value, [msg.id]: style }
}

/**
 * 带上会话级标注的图表规格：覆盖合并进 {@code style}，随分享快照与指标配置一起带走。
 * 无覆盖时原样返回（不生成新对象，避免无谓的引用变更）。
 */
function mergedChart(msg: ChatMessage): ChartSpec | null {
  const chart = msg.response?.chart ?? null
  if (!chart) return null
  const st = pruneChartStyle(styleOf(msg))
  return st ? { ...chart, style: { ...chart.style, ...st } } : chart
}

onMounted(() => {
  void chatStore.loadConversations()
  void loadFavoriteState()
  // 支持从收藏页「去问数」深链：?ds=数据源id&q=预填问题
  const ds = route.query.ds
  if (ds != null && ds !== '') dsStore.select(Number(ds))
  const q = route.query.q
  if (typeof q === 'string' && q) question.value = q
})

/** 消息变化后自动滚动到底部（含流式阶段追加）。 */
watch(
  () => {
    const last = chatStore.messages[chatStore.messages.length - 1]
    return [chatStore.messages.length, last?.status, last?.stages.length]
  },
  () => {
    void nextTick(() => {
      if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
    })
  }
)

function onSend(q: string, clarified = false): void {
  if (dsStore.currentId == null) {
    ElMessage.warning('请先在右上角选择数据源')
    return
  }
  const dsId = dsStore.currentId
  chatStore.send(dsId, q, (ok) => {
    void userStore.refreshQuota()
    if (ok) void chatStore.loadConversations()
    // 答案出完即拉本轮上下文追问（异步、不阻塞结果渲染；失败回落静态推荐）
    if (ok) {
      const last = chatStore.messages[chatStore.messages.length - 1]
      const hid = last?.response?.historyId
      if (hid != null) void loadFollowups(hid)
    }
  }, clarified)
}

/** 口径澄清候选点选（T10-2）：把候选 refineQuestion 以 clarified=true 回填重发，后端锁定口径直接执行（最多 1 轮）。 */
function onClarifySelect(opt: ClarifyOption): void {
  onSend(opt.refineQuestion, true)
}

/** 问数触限升级引导：打开官网套餐定价区（新标签），把拦截转转化。 */
function openUpgrade(): void {
  window.open('/', '_blank')
}

function onNew(): void {
  chatStore.newConversation()
  question.value = ''
}

function onSelect(convId: string): void {
  if (chatStore.conversationId === convId) return
  // 会话回落到它问数时的数据源：回放结果与后续追问都落在正确的库上
  const conv = chatStore.conversations.find((c) => c.conversationId === convId)
  if (conv?.datasourceId != null && conv.datasourceId !== dsStore.currentId) {
    dsStore.select(conv.datasourceId)
  }
  void chatStore.selectConversation(convId)
}

/** 拉取当前用户收藏，构建收藏态键集合（进入页面时调用）。 */
async function loadFavoriteState(): Promise<void> {
  try {
    const list = await favoriteApi.list()
    const set = new Set<string>()
    for (const f of list) {
      if (f.datasourceId != null) set.add(`${f.datasourceId}:${f.question}`)
    }
    favoritedKeys.value = set
  } catch {
    // 收藏态仅用于展示，拉取失败不阻断问数
  }
}

/** 该消息对应的问题在当前数据源下是否已收藏。 */
function isFavorited(msg: ChatMessage): boolean {
  if (dsStore.currentId == null) return false
  return favoritedKeys.value.has(`${dsStore.currentId}:${msg.question || ''}`)
}

async function onFavorite(msg: ChatMessage): Promise<void> {
  if (!msg.response?.result || dsStore.currentId == null) {
    ElMessage.warning('该结果暂不可收藏')
    return
  }
  const key = `${dsStore.currentId}:${msg.question || ''}`
  if (favoritedKeys.value.has(key)) {
    ElMessage.info('该问题已在收藏中，无需重复收藏')
    return
  }
  try {
    await favoriteApi.create({
      datasourceId: dsStore.currentId,
      question: msg.question || '',
      title: (msg.question || '').slice(0, 40),
      sql: msg.response.result.sql
    })
    favoritedKeys.value.add(key)
    ElMessage.success('已收藏')
  } catch {
    // 拦截器已提示
  }
}

function onShare(msg: ChatMessage): void {
  if (!msg.response?.result) {
    ElMessage.warning('该结果暂不可分享')
    return
  }
  shareTarget.value = {
    question: msg.question || '',
    sql: msg.response.result.sql ?? null,
    result: displayResult(msg),
    // 会话级图表标注一并带走，公开页与本地渲染一致
    chart: mergedChart(msg)
  }
  shareVisible.value = true
}

function onSaveMetric(msg: ChatMessage): void {
  if (!msg.response?.result?.sql || dsStore.currentId == null) {
    ElMessage.warning('该结果暂不可存为指标')
    return
  }
  metricTarget.value = {
    question: msg.question || '',
    sql: msg.response.result.sql,
    // 会话级图表标注并入 chart_spec.style，存进指标后其预览与大屏 widget 都按此渲染
    chart: mergedChart(msg),
    // 传原始结果（含后端语义层展示名），对话框内据此逐列预填；自定义映射单独传，只保留改过名的条目
    result: msg.response.result,
    columnLabels: pruneColumnLabels(msg.response.result, overridesOf(msg))
  }
  metricVisible.value = true
}

/** 已反馈的历史 id → vote（1=赞 / -1=踩），用于按钮态展示与防重复提交。 */
const feedbackState = ref<Record<number, 1 | -1>>({})
const feedbackBusy = ref<Record<number, boolean>>({})

// ===== 点踩修正采集层（T11-2）=====
// ASK/feedback-correction 门控（默认开）：开启时「不准确」先弹轻量可选表单；
// 采集全程可选——跳过/X/Esc 关层仍照常记一条点踩，绝不阻断反馈；建议只喂治理转写预填。
const correctionVisible = ref(false)
/** 当前正在采集的点踩目标 historyId；提交建议时先置 null 再接关层回调，避免二次直踩。 */
const correctionTarget = ref<number | null>(null)
const correctionForm = ref<{ expectedSql: string; note: string; reason: string }>({ expectedSql: '', note: '', reason: '' })
/** 错因四选（与后端 feedback_reason VARCHAR(32) 回齐），可空。 */
const FEEDBACK_REASONS = [
  { value: 'WRONG_NUMBER', label: '数字不对' },
  { value: 'WRONG_METRIC', label: '口径/指标不对' },
  { value: 'WRONG_FILTER', label: '筛选条件不对' },
  { value: 'OTHER', label: '其它' }
]

/** 点赞/点踩入口：赞直提；踩且采集开关开→先弹可选采集层（关层即直踩）。 */
function onFeedback(msg: ChatMessage, vote: 1 | -1): void {
  const hid = msg.response?.historyId
  if (hid == null) {
    ElMessage.warning('该结果暂不可反馈')
    return
  }
  if (vote === -1 && configStore.feedbackCorrectionEnabled) {
    correctionTarget.value = hid
    correctionForm.value = { expectedSql: '', note: '', reason: '' }
    correctionVisible.value = true
    return
  }
  void submitFeedback(hid, vote)
}

/** 采集层关闭（X/Esc/跳过）且尚未提交建议：视为一键直踩，只记 {vote:-1}。 */
function onCorrectionClosed(): void {
  const hid = correctionTarget.value
  if (hid != null) {
    correctionTarget.value = null
    void submitFeedback(hid, -1)
  }
}

/** 提交带建议的点踩：全空则退化为直踩；空白字段归 null 不传。 */
function submitCorrection(): void {
  const hid = correctionTarget.value
  if (hid == null) return
  correctionTarget.value = null
  const f = correctionForm.value
  const expectedSql = f.expectedSql.trim() || null
  const note = f.note.trim() || null
  const reason = f.reason || null
  const correction: FeedbackCorrectionPayload | undefined =
    expectedSql || note || reason ? { expectedSql, note, reason } : undefined
  correctionVisible.value = false
  void submitFeedback(hid, -1, correction)
}

/** 实际提交反馈：写入 query_history.feedback（赞且成功的记录进入少样本晋升池；踩可附建议，T11-2）。 */
async function submitFeedback(hid: number, vote: 1 | -1, correction?: FeedbackCorrectionPayload): Promise<void> {
  if (feedbackBusy.value[hid]) return
  feedbackBusy.value = { ...feedbackBusy.value, [hid]: true }
  try {
    await historyApi.feedback(hid, vote, correction)
    feedbackState.value = { ...feedbackState.value, [hid]: vote }
    ElMessage.success(vote === 1 ? '感谢反馈，已记录' : '已记录，我们会持续优化')
  } catch {
    // 拦截器已提示
  } finally {
    feedbackBusy.value = { ...feedbackBusy.value, [hid]: false }
  }
}
</script>

<template>
  <div class="chat-view">
    <!-- ============ 会话侧栏 ============ -->
    <aside class="conv-sidebar">
      <el-button type="primary" class="new-btn" @click="onNew">
        <el-icon><Plus /></el-icon> 新建会话
      </el-button>

      <div class="conv-scroll">
        <p class="conv-title">最近会话</p>
        <div v-if="chatStore.conversations.length === 0" class="conv-empty">
          暂无历史会话
        </div>
        <div
          v-for="c in chatStore.conversations"
          :key="c.conversationId"
          class="conv-item"
          :class="{ active: chatStore.conversationId === c.conversationId }"
          @click="onSelect(c.conversationId)"
        >
          <el-icon class="conv-icon"><ChatLineSquare /></el-icon>
          <div class="conv-body">
            <span class="conv-q truncate">{{ c.lastQuestion }}</span>
            <span class="conv-meta">{{ fromNow(c.updatedAt) }}</span>
          </div>
        </div>
      </div>
    </aside>

    <!-- ============ 消息主区 ============ -->
    <section class="chat-main">
      <div ref="scrollEl" class="messages">
        <!-- 空态引导 -->
        <div v-if="!chatStore.hasMessages" class="empty">
          <div class="empty-logo">
            <el-icon :size="34"><DataAnalysis /></el-icon>
          </div>
          <h2 class="empty-title grad-text">用一句话，问出你的数据</h2>
          <p class="empty-desc">
            选择数据源后直接提问，AI 会自动理解意图、生成 SQL、执行查询并推荐可视化图表。
          </p>
          <div class="empty-cards" :class="{ 'is-loading': suggestionsLoading }">
            <button
              v-for="s in suggestions"
              :key="s"
              class="suggest-card"
              @click="question = s"
            >
              <el-icon><MagicStick /></el-icon>
              <span>{{ s }}</span>
            </button>
          </div>
        </div>

        <!-- 消息流 -->
        <template v-else>
          <div v-for="msg in chatStore.messages" :key="msg.id" class="msg-row" :class="msg.role">
            <!-- 用户提问 -->
            <div v-if="msg.role === 'user'" class="bubble user-bubble">
              {{ msg.question }}
            </div>

            <!-- 助手应答 -->
            <div v-else class="assistant-block">
              <div class="assistant-head">
                <div class="ai-avatar"><el-icon :size="16"><Cpu /></el-icon></div>
                <span class="ai-name">AI 数据助理</span>
              </div>

              <!-- 分步进度（进行中） -->
              <div
                v-if="msg.status === 'pending' || msg.status === 'streaming'"
                class="stages card"
              >
                <div
                  v-for="(s, i) in msg.stages"
                  :key="i"
                  class="stage-row"
                  :class="{ latest: i === msg.stages.length - 1 && msg.status === 'streaming' }"
                >
                  <el-icon class="stage-dot" :class="{ 'is-loading': i === msg.stages.length - 1 && msg.status === 'streaming' }"><Loading v-if="i === msg.stages.length - 1 && msg.status === 'streaming'" /><Select v-else /></el-icon>
                  <span class="stage-name">{{ stageLabel(s.stage) }}</span>
                  <span v-if="s.message" class="stage-msg truncate">{{ s.message }}</span>
                </div>
                <div v-if="msg.status === 'pending' && msg.stages.length === 0" class="stage-row latest">
                  <el-icon class="stage-dot is-loading"><Loading /></el-icon>
                  <span class="stage-name">正在连接…</span>
                </div>
                <div v-if="msg.stalled" class="stage-row latest">
                  <el-icon class="stage-dot is-loading"><Loading /></el-icon>
                  <span class="stage-name">仍在处理中…</span>
                  <span class="stage-msg truncate muted">模型响应较慢，请耐心等待（连接已保活，不会中断）</span>
                </div>
              </div>

              <!-- 错误 -->
              <el-alert
                v-if="msg.status === 'error'"
                type="error"
                :closable="false"
                show-icon
                class="err-alert"
                :title="msg.error || '问数失败'"
              />

              <!-- 口径歧义澄清卡（T10-2，默认关）：clarify 非空时为“只反问、未执行”的响应（result 为空），
                   用户点选候选后以 clarified=true 回填重发，后端锁定口径直接执行（最多 1 轮） -->
              <div
                v-if="msg.response?.clarify"
                class="clarify-card"
                style="border: 1px solid var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); border-radius: 8px; padding: 10px 12px; margin-top: 8px;"
              >
                <div style="font-weight: 600; margin-bottom: 8px;">
                  您的问题可能对应多个口径，请先确认想查哪一个：
                </div>
                <div style="display: flex; flex-wrap: wrap; gap: 8px;">
                  <el-button
                    v-for="(opt, oi) in msg.response.clarify.options"
                    :key="oi"
                    size="small"
                    type="primary"
                    :plain="opt.code !== msg.response.clarify.defaultOptionCode"
                    :disabled="chatStore.sending"
                    @click="onClarifySelect(opt)"
                  >{{ opt.label }}</el-button>
                </div>
                <div class="muted" style="margin-top: 6px; font-size: 12px;">
                  点选后将按该口径自动重新提问；若要自行改述，可直接在输入框输入。
                </div>
              </div>

              <!-- 多意图拆分多段答案（T10-4，默认关；parts 非空时逐子问各渲染一个只读结果卡） -->
              <div
                v-if="msg.response?.parts?.length"
                class="multi-answer"
                style="display: flex; flex-direction: column; gap: 12px; margin-top: 8px;"
              >
                <div
                  v-for="(part, pi) in msg.response.parts"
                  :key="pi"
                  style="border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 8px 10px;"
                >
                  <div style="font-weight: 600; margin-bottom: 6px;">
                    <span style="color: var(--el-color-primary); margin-right: 6px;">{{ pi + 1 }}.</span>{{ part.subQuestion }}
                  </div>
                  <ResultPanel
                    v-if="part.answered && part.result"
                    :result="part.result"
                    :chart="part.chart ?? null"
                    :insight="part.insight ?? null"
                    :verify="part.verify ?? null"
                    :skill="part.skill ?? null"
                    :readonly="true"
                    :question="part.subQuestion"
                  />
                  <el-alert
                    v-else
                    type="info"
                    :closable="false"
                    show-icon
                    title="该子问未作答（本批耗时超预算或查询代价过高被拦截），请单独追问。"
                  />
                </div>
              </div>

              <!-- 结果面板 -->
              <ResultPanel
                v-if="msg.response?.result"
                :result="msg.response.result"
                :chart="msg.response.chart ?? null"
                :meta="msg.response.meta ?? null"
                :insight="msg.response.insight ?? null"
                :verify="msg.response.verify ?? null"
                :skill="msg.response.skill ?? null"
                :favorited="isFavorited(msg)"
                :label-overrides="overridesOf(msg)"
                :style-override="styleOf(msg)"
                :datasource-id="dsStore.currentId"
                :question="msg.question ?? null"
                :history-id="msg.response?.historyId ?? null"
                :attribution="msg.response?.attribution ?? null"
                @favorite="onFavorite(msg)"
                @share="onShare(msg)"
                @save-metric="onSaveMetric(msg)"
                @rename-column="(col: string, lb: string) => onRenameColumn(msg, col, lb)"
                @update-chart-style="(st: ChartStyle | null) => onUpdateChartStyle(msg, st)"
              />

              <!-- 反馈（点赞/点踩）：赞且成功的记录进入少样本晋升池 -->
              <div
                v-if="msg.response?.result && msg.response?.historyId != null"
                class="feedback-row"
              >
                <span class="fb-label">这次回答是否有帮助？</span>
                <el-button
                  size="small"
                  text
                  :type="feedbackState[msg.response.historyId] === 1 ? 'primary' : ''"
                  :loading="feedbackBusy[msg.response.historyId]"
                  @click="onFeedback(msg, 1)"
                >
                  <el-icon><Select /></el-icon> 有帮助
                </el-button>
                <el-button
                  size="small"
                  text
                  :type="feedbackState[msg.response.historyId] === -1 ? 'danger' : ''"
                  :loading="feedbackBusy[msg.response.historyId]"
                  @click="onFeedback(msg, -1)"
                >
                  <el-icon><CloseBold /></el-icon> 不准确
                </el-button>
              </div>

              <!-- 答案下方的推荐问题：优先展示本轮上下文追问（无/失败回落静态推荐），点击即填入输入框追问 -->
              <div
                v-if="msg.response?.result && msg.status !== 'pending' && msg.status !== 'streaming' && suggestListOf(msg).length"
                class="msg-suggest"
              >
                <span class="msg-suggest-label">你可能还想问</span>
                <button
                  v-for="s in suggestListOf(msg)"
                  :key="'sg-' + s"
                  class="suggest-chip"
                  type="button"
                  @click="question = s"
                >{{ s }}</button>
              </div>
            </div>
          </div>
        </template>
      </div>

      <!-- 问数触限升级引导（命中套餐配额上限时展示，把拦截转转化） -->
      <div v-if="chatStore.upgradeHint" class="upsell-banner">
        <el-icon class="upsell-icon"><WarningFilled /></el-icon>
        <div class="upsell-text">
          <span class="upsell-title">已达当前套餐用量上限</span>
          <span class="upsell-desc">
            升级到更高套餐可解锁更多问数额度与团队治理功能（组织成员 / 审计 / 角色权限 / 数据授权），历史数据不受影响。
          </span>
        </div>
        <div class="upsell-actions">
          <el-button type="warning" size="small" @click="openUpgrade">查看升级方案</el-button>
          <el-button text size="small" @click="chatStore.clearUpgradeHint()">稍后</el-button>
        </div>
      </div>

      <!-- 输入栏 -->
      <ChatInput
        v-model="question"
        :sending="chatStore.sending"
        :disabled="dsStore.currentId == null"
        @send="onSend"
        @stop="chatStore.abort()"
      />
    </section>

    <!-- 分享对话框 -->
    <ShareDialog
      v-model="shareVisible"
      :datasource-id="dsStore.currentId"
      :question="shareTarget?.question || ''"
      :sql="shareTarget?.sql"
      :result="shareTarget?.result || null"
      :chart="shareTarget?.chart"
    />

    <!-- 存为指标对话框 -->
    <MetricSaveDialog
      v-model="metricVisible"
      :datasource-id="dsStore.currentId"
      :question="metricTarget?.question || ''"
      :sql="metricTarget?.sql"
      :chart="metricTarget?.chart"
      :result="metricTarget?.result || null"
      :column-labels="metricTarget?.columnLabels || null"
    />

    <!-- 点踩修正采集层（T11-2）：全程可选，跳过/关层即直踩；建议仅作治理转写预填草稿，必经人工复核 -->
    <el-dialog
      v-model="correctionVisible"
      title="帮助我们把答案改对"
      width="560px"
      @closed="onCorrectionClosed"
    >
      <p class="fc-hint">可选填你认为正确的答案，帮助数据管理员更快修正口径——不填也没关系，直接关闭即正常记录点踩。</p>
      <el-form label-position="top">
        <el-form-item label="期望的 SQL（可选）">
          <el-input v-model="correctionForm.expectedSql" type="textarea" :rows="3" placeholder="SELECT ..." spellcheck="false" />
        </el-form-item>
        <el-form-item label="哪里不对（可选）">
          <el-input v-model="correctionForm.note" maxlength="500" show-word-limit placeholder="例如：退货率应剔除退款单，按订单数计算" />
        </el-form-item>
        <el-form-item label="错因分类（可选）">
          <el-select v-model="correctionForm.reason" clearable placeholder="选一个（可留空）" style="width: 100%">
            <el-option v-for="r in FEEDBACK_REASONS" :key="r.value" :value="r.value" :label="r.label" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button text @click="correctionVisible = false">跳过，直接点踩</el-button>
        <el-button type="primary" @click="submitCorrection">提交点踩（带修正建议）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.fc-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.chat-view {
  display: flex;
  height: 100%;
  width: 100%;
  overflow: hidden;
}

/* ---------- 会话侧栏 ---------- */
/* ===== 答案下方的推荐问题（与无感嵌入页同款交互） ===== */
.msg-suggest {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px dashed var(--border, #e6eaf2);
}
.msg-suggest-label {
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
  margin-right: 2px;
}
.msg-suggest .suggest-chip {
  padding: 4px 10px;
  font-size: 12px;
  border-radius: 999px;
  background: var(--bg-soft, #f7f9fc);
  border: 1px solid var(--border, #e6eaf2);
  color: var(--text-secondary, #475569);
  cursor: pointer;
  transition: all 0.15s ease;
}
.msg-suggest .suggest-chip:hover {
  border-color: var(--brand, #2563eb);
  color: var(--brand, #2563eb);
  background: var(--brand-soft, #f5f8ff);
}

.conv-sidebar {
  width: 248px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-light);
  background: linear-gradient(180deg, var(--bg-card), var(--ikb-ghost));
  display: flex;
  flex-direction: column;
  padding: 14px 12px;
}
.new-btn {
  width: 100%;
  height: 42px;
  font-weight: 600;
  background: var(--grad-btn);
  border: none;
  box-shadow: var(--shadow-brand);
}
.new-btn:hover {
  background: var(--grad-btn-hover);
}
.conv-scroll {
  flex: 1;
  margin-top: 14px;
  overflow-y: auto;
}
.conv-title {
  font-size: 11.5px;
  color: var(--text-placeholder);
  font-weight: 700;
  letter-spacing: 1px;
  margin: 0 0 8px 6px;
}
.conv-empty {
  font-size: 12.5px;
  color: var(--text-placeholder);
  text-align: center;
  padding: 24px 0;
}
.conv-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 10px 11px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background 0.18s ease;
  margin-bottom: 4px;
  border: 1px solid transparent;
}
.conv-item:hover {
  background: var(--bg-card);
  border-color: var(--border-light);
}
.conv-item.active {
  background: var(--ikb-pale);
  border-color: rgba(0, 47, 167, 0.16);
}
.conv-icon {
  color: var(--ikb-light);
  margin-top: 2px;
  flex-shrink: 0;
}
.conv-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.conv-q {
  font-size: 13px;
  color: var(--text-regular);
  font-weight: 500;
  max-width: 176px;
}
.conv-item.active .conv-q {
  color: var(--ikb-dark);
}
.conv-meta {
  font-size: 11px;
  color: var(--text-placeholder);
  margin-top: 3px;
}

/* ---------- 消息主区 ---------- */
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-page);
}

/* 问数触限升级引导 */
.upsell-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 28px 10px;
  padding: 12px 16px;
  background: linear-gradient(90deg, #fff7e6, #fffbe6);
  border: 1px solid #ffe58f;
  border-radius: var(--radius-md);
}
.upsell-icon {
  font-size: 22px;
  color: #fa8c16;
  flex-shrink: 0;
}
.upsell-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.upsell-title {
  font-size: 13.5px;
  font-weight: 700;
  color: #ad6800;
}
.upsell-desc {
  font-size: 12.5px;
  color: #876300;
  line-height: 1.5;
}
.upsell-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px 8px;
}

/* 空态 */
.empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding-bottom: 40px;
}
.empty-logo {
  width: 72px;
  height: 72px;
  border-radius: 22px;
  background: var(--grad-brand);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-brand);
  margin-bottom: 22px;
}
.empty-title {
  font-size: 26px;
  font-weight: 800;
  margin: 0 0 10px;
}
.empty-desc {
  max-width: 520px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
  margin: 0 0 28px;
}
.empty-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 260px));
  gap: 12px;
}
.empty-cards.is-loading {
  opacity: 0.55;
  pointer-events: none;
}
.suggest-card {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 14px 16px;
  background: var(--bg-card);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 13px;
  color: var(--text-regular);
  text-align: left;
  transition: all 0.2s ease;
}
.suggest-card:hover {
  border-color: var(--ikb);
  color: var(--ikb);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.suggest-card .el-icon {
  color: var(--ikb-lighter);
  flex-shrink: 0;
}

/* 消息行 */
.msg-row {
  display: flex;
  margin-bottom: 22px;
}
.msg-row.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 70%;
  padding: 11px 16px;
  border-radius: var(--radius-lg);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.user-bubble {
  background: var(--grad-btn);
  color: #fff;
  border-bottom-right-radius: 6px;
  box-shadow: var(--shadow-brand);
}
.assistant-block {
  width: 100%;
  max-width: 860px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.assistant-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-avatar {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: var(--grad-accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ai-name {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--ikb);
}

/* 分步进度 */
.stages {
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stage-row {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
  color: var(--text-secondary);
}
.stage-row.latest {
  color: var(--ikb);
  font-weight: 600;
}
.stage-dot {
  color: var(--success);
  font-size: 14px;
}
.stage-row.latest .stage-dot {
  color: var(--ikb-light);
}
.stage-name {
  flex-shrink: 0;
}
.stage-msg {
  color: var(--text-placeholder);
  font-size: 12px;
  max-width: 460px;
}
.err-alert {
  border-radius: var(--radius-md);
}
.feedback-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: -2px;
}
.fb-label {
  font-size: 12px;
  color: var(--text-placeholder);
  margin-right: 4px;
}
</style>
