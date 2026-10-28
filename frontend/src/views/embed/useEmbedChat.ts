/**
 * 对话问数嵌入页核心状态机（T7.3 自 EmbedChatView 逐字搬移）。
 *
 * 凭 URL 中的 token 匿名调用 /api/public/chat-embed/**，复用后端问数主链路（含只读校验、
 * 字段脱敏与质量校验）。页面刻意不带侧边栏/顶栏等外围框架，撑满 iframe 便于被嵌入。
 *
 * 单一内聚状态机：共享态与行为全部经 reactive 聚合后 provide，
 * 子组件通过 useEmbedCtx() 注入直接绑定（沿用 T7.1 reactive 聚合配方）。
 */
import { computed, inject, nextTick, provide, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { publicChatEmbedApi } from '@/api/chatEmbed'
import type { EmbedMeta, EmbedHistoryItem } from '@/api/chatEmbed'
import { exportXlsx, fileStamp, gridToExport } from '@/utils/exportFile'
import { applyEnumDicts } from '@/utils/format'
import { FALLBACK_TIPS, type Msg } from './embedTypes'

/** 裸 symbol key：断开 useEmbedChat 与 EmbedChatCtx 的类型循环（T7.1 配方）。 */
export const EmbedCtxKey = Symbol('EmbedChatCtx')

export function useEmbedChat() {
  const route = useRoute()
  const token = computed(() => String(route.params.token || ''))
  // 嵌入方传入的外部用户唯一值（?user=），用于把同一访客的问数归并到其名下（审计 + 历史回看）。
  const extUser = ref<string>('')

  const meta = ref<EmbedMeta | null>(null)
  const title = ref('智能问数')
  const ready = ref(false)
  const fatal = ref('')
  const busy = ref(false)
  const question = ref('')
  const messages = ref<Msg[]>([])
  const scroller = ref<HTMLElement | null>(null)
  const conversationId = ref<string | null>(null)
  const datasourceIds = ref<number[]>([])
  const selectedDs = ref<number | null>(null)
  const dsNames = ref<Record<string, string>>({})
  const suggestions = ref<string[]>([])
  const suggestionsLoading = ref(false)

  /** 右侧悬浮历史面板：该外部用户的历史对话列表（最新在前）与展开状态。 */
  const historyList = ref<EmbedHistoryItem[]>([])
  const historyLoading = ref(false)
  const showHistory = ref(false)

  /** 数据源下拉选项：优先用后端返回的真实名称，缺失时退化显示"数据源 {id}"。 */
  const dsOptions = computed(() =>
    datasourceIds.value.map((id) => ({
      id,
      name: dsNames.value[String(id)] || `数据源 ${id}`
    }))
  )

  /** 展示用样例问法：LLM 生成优先，空则回退硬编码。 */
  const displayTips = computed(() => (suggestions.value.length ? suggestions.value : FALLBACK_TIPS))

  const placeholder = computed(() => {
    if (!ready.value) return '正在初始化…'
    if (busy.value) return '正在查询…'
    return '输入你想问的数据…'
  })

  function fmt(v: unknown): string {
    if (v === null || v === undefined) return '-'
    if (typeof v === 'number') {
      return Number.isInteger(v) ? String(v) : v.toFixed(2)
    }
    return String(v)
  }

  /** 导出某条应答的数据（Excel；含全部行，非仅展示的前 20 行）。 */
  async function exportMsg(m: Msg, idx: number): Promise<void> {
    const all = m.exportRows?.length ? m.exportRows : m.rows
    if (!all.length) return
    const g = gridToExport(m.columns, all)
    await exportXlsx(`问数结果-${fileStamp()}-${idx + 1}`, g.columns, g.rows)
  }

  function useTip(t: string) {
    if (busy.value) return
    question.value = t
    ask()
  }

  function scrollBottom() {
    nextTick(() => {
      if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
    })
  }

  async function loadSuggestions() {
    if (suggestionsLoading.value) return
    suggestionsLoading.value = true
    try {
      // 带上当前选中的数据源：多选嵌入切换后，样例问法应随数据源变化（后端按白名单校验）。
      const list = await publicChatEmbedApi.suggestions(token.value, selectedDs.value)
      if (list && list.length) suggestions.value = list
    } catch {
      // 保持空，由 displayTips 回退硬编码，绝不阻断页面
    } finally {
      suggestionsLoading.value = false
    }
  }

  /**
   * 访客切换数据源时，若尚未开始对话（样例问法区可见），重新拉取对应数据源的推荐问法。
   * 已对话状态下样例区不可见，无需重拉，避免无谓的 LLM 调用（且切换数据源不影响已落消息）。
   */
  watch(selectedDs, () => {
    if (ready.value && !messages.value.length) {
      void loadSuggestions()
    }
  })

  async function init() {
    try {
      const m = await publicChatEmbedApi.meta(token.value)
      meta.value = m
      if (m?.name) title.value = m.name
      datasourceIds.value = m?.datasourceIds || []
      dsNames.value = m?.datasourceNames || {}
      selectedDs.value = datasourceIds.value.length ? datasourceIds.value[0] : null
      ready.value = true
      // 外部用户唯一值（嵌入方 ?user= 传入）：归并该访客历史、写入审计
      extUser.value = (route.query.user != null ? String(route.query.user) : '').trim()
      // 推荐问法异步拉取，不阻塞首屏标题/名称
      void loadSuggestions()
      // 若该嵌入带外部用户标识：初始化即拉取"该用户历史"，并默认展开右侧面板，
      // 让访客首屏即看到「对话历史」能力（无感嵌入场景下用户的会话被自动记住）。
      if (extUser.value) {
        showHistory.value = true
        void loadHistory()
      }
    } catch (e: any) {
      fatal.value = e?.message || '嵌入链接无效或已过期'
    }
  }

  /**
   * 拉取该外部用户的历史对话（最新在前）；未传 user 时静默跳过。
   * @param showLoading 是否显示"加载中"遮罩：手动点刷新=true；问数成功后静默自动刷新=false（直接替换，不闪烁）。
   * 始终以服务端返回为权威，避免本地即时插入带来的顺序错乱 / id 重复渲染异常。
   */
  async function loadHistory(showLoading = false) {
    if (!extUser.value) return
    if (showLoading) historyLoading.value = true
    try {
      const list = await publicChatEmbedApi.history(token.value, extUser.value, 50)
      historyList.value = list || []
    } catch {
      // 拉取失败保留现有列表，不破坏用户已见内容
    } finally {
      if (showLoading) historyLoading.value = false
    }
  }

  /** 时间戳归一：createdAt 可能为空或非法，统一兜为 0（排在最早）。 */
  function histTs(v?: string | null): number {
    if (!v) return 0
    const n = new Date(v).getTime()
    return Number.isNaN(n) ? 0 : n
  }

  /**
   * 把「消息级」历史聚合为「会话级」列表——一项 = 一个会话，呈现为聊天记录般的会话列表。
   * 同一 conversationId 的多轮问答合并成一条：标题取首条提问，摘要取最后一条消息，
   * 按最近活跃倒序。避免同一会话被拆成多条重复项（旧版平铺消息的做法不符合聊天习惯）。
   */
  const conversations = computed(() => {
    const map = new Map<string, EmbedHistoryItem[]>()
    for (const h of historyList.value) {
      const key = h.conversationId || '__legacy__'
      const arr = map.get(key)
      if (arr) arr.push(h)
      else map.set(key, [h])
    }
    const out = Array.from(map.entries()).map(([conversationId, items]) => {
      const sorted = [...items].sort((a, b) => histTs(a.createdAt) - histTs(b.createdAt))
      const last = sorted[sorted.length - 1]
      const firstUser = sorted.find((m) => m.role === 'USER')
      return {
        conversationId,
        title: (firstUser?.content || last?.content || '（空对话）').slice(0, 40),
        preview: (last?.content || '').slice(0, 60),
        lastAt: last?.createdAt ?? null,
        count: sorted.length
      }
    })
    return out.sort((a, b) => histTs(b.lastAt) - histTs(a.lastAt))
  })

  /** 聊天记录风格的时间：刚刚 / N 分钟前 / 今天 HH:mm / 昨天 HH:mm / MM-DD HH:mm。 */
  function relTime(v?: string | null): string {
    const t = histTs(v)
    if (!t) return ''
    const d = new Date(t)
    const pad = (n: number) => String(n).padStart(2, '0')
    const diff = Date.now() - t
    if (diff >= 0 && diff < 60_000) return '刚刚'
    if (diff >= 0 && diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    if (t >= today.getTime()) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
    const y = new Date(today)
    y.setDate(y.getDate() - 1)
    if (t >= y.getTime()) return `昨天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  }

  /** 点击历史项：续接该会话——把完整对话还原到主体，并以该 conversationId 继续多轮追问。 */
  async function openConversation(convId: string | null) {
    if (!convId || !extUser.value) return
    try {
      const turns = await publicChatEmbedApi.conversation(token.value, extUser.value, convId)
      if (turns && turns.length) {
        messages.value = turns.map((t) =>
          t.role === 'USER'
            ? ({ role: 'user', text: t.content || '', columns: [], rows: [] } as Msg)
            : ({ role: 'bot', loading: false, columns: [], rows: [], summary: t.content || '' } as Msg)
        )
        conversationId.value = convId
        showHistory.value = false
        scrollBottom()
      }
    } catch {
      // 失败不阻断：忽略即可
    }
  }

  async function ask() {
    const q = question.value.trim()
    if (!q || busy.value) return
    question.value = ''
    messages.value.push({ role: 'user', text: q, columns: [], rows: [] })
    messages.value.push({ role: 'bot', loading: true, columns: [], rows: [] })
    // 拆分后消息渲染在 EmbedMessages：必须经数组内 reactive 代理写回（同文件其他 ref 写法）。
    // 若直接 mutate push 前闭包里的原始对象，属性变更不触发渲染通知（拆分前单组件靠 busy 变化
    // 整组重渲染掩盖了这一点），气泡会停在加载态。取代理元素后行为与拆分前一致。
    const bot = messages.value[messages.value.length - 1]
    busy.value = true
    scrollBottom()
    try {
      const res = await publicChatEmbedApi.query(token.value, q, conversationId.value, selectedDs.value, extUser.value)
      if (res?.conversationId) conversationId.value = res.conversationId
      // 多意图拆分应答（T12-5 验收补网）：顶层 result 为空、内容在 parts——嵌入气泡没有
      // 多段结果卡，降级为文本：keyPoints 逐段列「子问：结论」，表格取首个已答段。
      // 复用既有渲染字段，EmbedMessages 组件零改动；组合问不落历史（historyId 为空），
      // 下方 followups/历史刷新分支自然跳过。
      if (res?.parts?.length) {
        bot.loading = false
        bot.summary = `复合问题已拆成 ${res.parts.length} 个子问分别作答：`
        bot.keyPoints = res.parts.map((p, i) => {
          const verdict = !p.answered
            ? '未作答（请单独追问）'
            : p.insight?.summary || (p.result ? `已返回 ${p.result.rowCount} 行` : '已作答')
          return `${i + 1}. ${p.subQuestion}：${verdict}`
        })
        const first = res.parts.find((p) => p.answered && p.result)
        const frows = (first?.result?.rows || []) as unknown[][]
        bot.columns = (first?.result?.columns || []) as string[]
        bot.rows = applyEnumDicts({
          columns: bot.columns,
          rows: frows,
          enumDicts: first?.result?.enumDicts || null
        }).slice(0, 20)
        bot.exportRows = frows
        bot.rowCount = first?.result?.rowCount ?? frows.length
        bot.more = frows.length > 20
        if (extUser.value && res?.conversationId) void loadHistory()
        return
      }
      const rows = (res?.result?.rows || []) as unknown[][]
      bot.loading = false
      bot.columns = (res?.result?.columns || []) as string[]
      // 界面只显示替换后的中文（语义列配了枚举字典时），导出仍走原始 rows 保留可追溯性
      bot.rows = applyEnumDicts({
        columns: bot.columns,
        rows,
        enumDicts: (res?.result?.enumDicts as Record<string, Record<string, string>>) || null
      }).slice(0, 20)
      bot.exportRows = rows
      bot.rowCount = res?.result?.rowCount ?? rows.length
      bot.more = rows.length > 20
      // AI 解读：与主站内嵌结果面板同口径——结论 + 要点 + 异常（此前只透传了 summary，要点/异常丢失）
      bot.summary = res?.insight?.summary || ''
      bot.keyPoints = (res?.insight?.keyPoints || []).filter((s) => !!s && !!s.trim())
      bot.anomalies = (res?.insight?.anomalies || []).filter((s) => !!s && !!s.trim())
      const parts: string[] = []
      if (res?.verify?.score != null) parts.push(`质量分 ${res.verify.score}`)
      if (res?.result?.executionTimeMs != null) parts.push(`${res.result.executionTimeMs} ms`)
      bot.meta = parts.join(' · ')
      // 记录本轮问数 id，并异步拉取该轮的上下文追问（"接着答案往下钻"；失败回落全局样例问法）
      if (res?.historyId != null) {
        bot.historyId = res.historyId
        void loadFollowups(bot, res.historyId)
      }
      // 问数落库后，从服务端重新拉取完整真实历史，保证"我的历史"面板与服务端一致。
      // （不再本地 unshift：避免多轮/跨会话时的顺序错乱与 id 重复导致的渲染异常；刷新按钮与自动刷新复用同一条权威路径。）
      if (extUser.value && res?.conversationId) {
        void loadHistory()
      }
    } catch (e: any) {
      bot.loading = false
      bot.error = e?.message || '查询失败，请稍后重试'
    } finally {
      busy.value = false
      scrollBottom()
    }
  }

  /** 拉取某轮问数的上下文追问并挂到对应消息上；失败/空静默（展示层回落全局样例）。 */
  async function loadFollowups(m: Msg, historyId: number): Promise<void> {
    try {
      const list = await publicChatEmbedApi.followups(token.value, historyId)
      if (list && list.length) m.followups = list
    } catch {
      // 追问是 UI 辅助，失败保持回落样例问法
    }
  }

  // reactive 聚合：模板中 ctx.xxx 自动解包；内部函数仍闭包原始 ref，行为与拆分前逐字一致。
  const ctx = reactive({
    title,
    extUser,
    ready,
    fatal,
    busy,
    question,
    messages,
    scroller,
    conversationId,
    selectedDs,
    dsOptions,
    displayTips,
    placeholder,
    historyLoading,
    showHistory,
    conversations,
    fmt,
    exportMsg,
    useTip,
    init,
    loadHistory,
    relTime,
    openConversation,
    ask
  })
  provide(EmbedCtxKey, ctx)
  return ctx
}

export type EmbedChatCtx = ReturnType<typeof useEmbedChat>

export function useEmbedCtx(): EmbedChatCtx {
  const ctx = inject<EmbedChatCtx | undefined>(EmbedCtxKey)
  if (!ctx) throw new Error('useEmbedCtx() 必须在 EmbedChatView 子树内调用')
  return ctx
}
