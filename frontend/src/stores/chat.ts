import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { askStream } from '@/api/sse'
import { historyApi } from '@/api/history'
import { type ChatQueryResponse, type ConversationVO, type StageEvent, ErrCode } from '@/types'

/** 单条消息（用户提问 / 助手应答）。 */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  /** 用户消息：提问文本；助手消息：对应的提问回显（可空）。 */
  question?: string
  /** 助手消息：分步进度。 */
  stages: StageEvent[]
  /** 助手消息状态。 */
  status: 'pending' | 'streaming' | 'done' | 'error'
  /** 助手消息：最终结果（含 SQL / 列行 / 图表推荐）。 */
  response?: ChatQueryResponse
  /** 助手消息：错误信息。 */
  error?: string
  /** 助手消息：距上次进度事件已超过阈值（长问数链路）时置真，用于展示"仍在处理中"提示。 */
  stalled?: boolean
  createdAt: number
}

/**
 * 问数 store：当前会话、消息流、会话列表、SSE 发送编排。
 *
 * 多轮：conversationId 首轮由后端生成并回传，追问复用同一 id 让后端注入上下文。
 * 流式：send() 先落一条用户消息 + 一条助手占位消息，随后用 SSE 逐步更新 stages/result。
 */
export const useChatStore = defineStore('chat', () => {
  const conversationId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  const conversations = ref<ConversationVO[]>([])
  const sending = ref(false)
  /** 问数触限升级引导：命中套餐配额上限（FREE/TOTAL_LIMIT）时置位，前端据此展示升级入口。 */
  const upgradeHint = ref<{ code: number; message: string } | null>(null)
  let controller: AbortController | null = null

  /**
   * 长问数"仍在处理中"看门狗：问数链路含多次 LLM 调用（自一致性多路 / 交叉一致性 / AI 解读·校验·选图），
   * 其间可能几十秒没有任何 stage 事件。距上次事件超过阈值即置 {@code stalled}，前端提示"仍在处理中"，
   * 避免用户误判卡死而离开页面（离开会真正断开 SSE）。
   */
  const STALL_HINT_MS = 20_000
  let stallTimer: number | undefined
  let lastEventAt = 0
  function stopStallTimer(): void {
    if (stallTimer !== undefined) {
      window.clearInterval(stallTimer)
      stallTimer = undefined
    }
  }

  const hasMessages = computed(() => messages.value.length > 0)
  const lastAssistant = computed(
    () => [...messages.value].reverse().find((m) => m.role === 'assistant') ?? null
  )

  function uid(): string {
    return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  }

  /** 新建会话：清空当前线程与 conversationId。 */
  function newConversation(): void {
    abort()
    conversationId.value = null
    messages.value = []
  }

  /** 加载会话列表（侧边栏）。 */
  async function loadConversations(limit = 50): Promise<void> {
    try {
      conversations.value = await historyApi.conversations(limit)
    } catch {
      conversations.value = []
    }
  }

  /**
   * 选择一个历史会话：设置 conversationId 以便追问延续上下文，
   * 并从历史接口重建该会话的过往消息；随后并行拉取每条成功问答的回放详情
   * （结果快照/老记录后端重跑 SQL + 图表规格），还原"问数当时"的数据与图表。
   */
  async function selectConversation(convId: string): Promise<void> {
    abort()
    conversationId.value = convId
    messages.value = []
    try {
      const page = await historyApi.list({ limit: 100, offset: 0 })
      const turns = page.items.filter((h) => h.conversationId === convId)
      // 历史按时间倒序返回，反转成正序
      turns.reverse().forEach((h) => {
        messages.value.push({
          id: uid(),
          role: 'user',
          question: h.question,
          stages: [],
          status: 'done',
          createdAt: Date.now()
        })
        messages.value.push({
          id: uid(),
          role: 'assistant',
          question: h.question,
          stages: [],
          status: h.status === 'OK' ? 'done' : 'error',
          response: h.sql
            ? {
                conversationId: h.conversationId ?? convId,
                historyId: h.id,
                result: { sql: h.sql, columns: [], rows: [], rowCount: h.rowCount ?? 0, executionTimeMs: h.elapsedMs ?? 0 }
              }
            : undefined,
          error: h.status === 'OK' ? undefined : '该次问数失败',
          createdAt: Date.now()
        })
      })
      // 回放：并行补齐每条成功应答的结果行与图表（快照命中零成本；老记录后端重跑 SQL）
      const targets = messages.value.filter(
        (m) => m.role === 'assistant' && m.status === 'done' && m.response?.historyId != null
      )
      await Promise.allSettled(
        targets.map(async (m) => {
          const hid = m.response?.historyId
          if (hid == null) return
          const d = await historyApi.detailData(hid)
          if (!m.response) return
          if (d.result) m.response.result = d.result
          if (d.chart) m.response.chart = d.chart
          // 回放时一并还原 AI 解读与质量校验（历史会话缺这两块即不显示）
          if (d.insight) m.response.insight = d.insight
          if (d.verify) m.response.verify = d.verify
          // 维度波动归因：与解读/校验同一套"问数当时产物"口径，原样回填、不重跑
          if (d.attribution) m.response.attribution = d.attribution
        })
      )
    } catch {
      // 重建失败不阻断，仅保留 conversationId 以便继续追问
    }
  }

  /** 中断进行中的流式请求。 */
  function abort(): void {
    if (controller) {
      controller.abort()
      controller = null
    }
    sending.value = false
    stopStallTimer()
  }

  /**
   * 发送一个问题（流式）。
   * @param datasourceId 目标数据源
   * @param question     自然语言问题
   * @param onSettled    结束回调（成功/失败都触发），供调用方刷新配额/会话列表
   * @param clarified    口径澄清重发标志（T10-2）：点选澄清候选后传 true，后端据此跳过反问门、锁定口径直接执行
   */
  function send(
    datasourceId: number,
    question: string,
    onSettled?: (ok: boolean) => void,
    clarified = false
  ): void {
    if (sending.value) return
    upgradeHint.value = null // 新一轮问数：清除上一轮可能残留的升级引导
    // 用户消息
    messages.value.push({
      id: uid(),
      role: 'user',
      question,
      stages: [],
      status: 'done',
      createdAt: Date.now()
    })
    // 助手占位消息（推入后取回其响应式代理，SSE 回调逐步更新它）
    messages.value.push({
      id: uid(),
      role: 'assistant',
      question,
      stages: [],
      status: 'pending',
      createdAt: Date.now()
    })
    const target = messages.value[messages.value.length - 1]

    sending.value = true
    // 启动看门狗：长时间无事件即提示"仍在处理中"，避免用户误判卡死而离开（离开会断流）
    lastEventAt = Date.now()
    target.stalled = false
    stopStallTimer()
    stallTimer = window.setInterval(() => {
      if (sending.value && Date.now() - lastEventAt > STALL_HINT_MS) target.stalled = true
    }, 2000)
    controller = askStream(
      { datasourceId, question, conversationId: conversationId.value, clarified },
      {
        onStage: (e) => {
          target.status = 'streaming'
          target.stalled = false
          lastEventAt = Date.now()
          target.stages = [...target.stages, e]
        },
        onResult: (r) => {
          target.response = r
          target.status = 'done'
          target.stalled = false
          if (r.conversationId) conversationId.value = r.conversationId
        },
        onError: (e) => {
          target.status = 'error'
          target.stalled = false
          target.error = e.message || '问数失败'
          // 套餐配额触限（42901 免费日限额 / 42903 组织总限额）：置位升级引导，把拦截转转化
          if (e.code === ErrCode.FREE_LIMIT_EXCEEDED || e.code === ErrCode.TOTAL_LIMIT_EXCEEDED) {
            upgradeHint.value = { code: e.code, message: e.message || '问数已达当前套餐上限' }
          }
        },
        onDone: () => {
          sending.value = false
          target.stalled = false
          stopStallTimer()
          controller = null
          onSettled?.(target.status === 'done')
        }
      }
    )
  }

  /** 清除问数触限升级引导（用户关闭横幅或重新问数时调用）。 */
  function clearUpgradeHint(): void {
    upgradeHint.value = null
  }

  return {
    conversationId,
    messages,
    conversations,
    sending,
    hasMessages,
    lastAssistant,
    upgradeHint,
    newConversation,
    loadConversations,
    selectConversation,
    send,
    abort,
    clearUpgradeHint
  }
})
