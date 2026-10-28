import type { ChatQueryResponse, SseErrorEvent, StageEvent } from '@/types'
import { getToken } from '@/utils/storage'

/**
 * 基于 fetch + ReadableStream 的 SSE 客户端。
 *
 * 为何不用原生 EventSource：后端 /api/chat/query/stream 需要 Authorization 头鉴权，
 * 而 EventSource 无法设置自定义请求头。这里改用 fetch 手动读取并解析 text/event-stream，
 * 既能携带 Bearer 令牌，又支持 AbortController 主动中断。
 *
 * 事件协议（与后端 ChatStreamController / SseProgressListener 一致）：
 *   event: stage  → {stage, message}   分步进度
 *   event: result → ChatQueryResponse  最终结果
 *   event: error  → {code, message}    失败
 */

export interface AskStreamHandlers {
  onStage?: (e: StageEvent) => void
  onResult?: (r: ChatQueryResponse) => void
  onError?: (e: SseErrorEvent) => void
  /** 流正常结束（收到 result 或连接关闭）。 */
  onDone?: () => void
}

export interface AskStreamPayload {
  datasourceId: number
  question: string
  conversationId?: string | null
  /** 期望使用的问数技能编码（可空=由后端路由择优）。 */
  skill?: string | null
  /** 口径澄清重发标志（T10-2）：点选澄清候选后置 true，后端跳过反问门、锁定口径直接执行。 */
  clarified?: boolean | null
}

/** 发起流式问数，返回可用于中断的 AbortController。 */
export function askStream(payload: AskStreamPayload, handlers: AskStreamHandlers): AbortController {
  const controller = new AbortController()
  const params = new URLSearchParams()
  params.set('datasourceId', String(payload.datasourceId))
  params.set('question', payload.question)
  if (payload.conversationId) params.set('conversationId', payload.conversationId)
  if (payload.skill) params.set('skill', payload.skill)
  if (payload.clarified) params.set('clarified', 'true')

  const token = getToken()
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (token) headers.Authorization = `Bearer ${token}`

  void run(`/api/chat/query/stream?${params.toString()}`, headers, controller.signal, handlers)
  return controller
}

async function run(
  url: string,
  headers: Record<string, string>,
  signal: AbortSignal,
  handlers: AskStreamHandlers
): Promise<void> {
  // settled 保证 onDone 只触发一次。收到 result/error 这类「逻辑终止事件」后立即复位并主动关流，
  // 不再依赖服务端/代理何时关闭 TCP —— 否则经代理时连接可能迟迟不关，导致 sending 状态长时间卡住。
  let settled = false
  const finish = (): void => {
    if (!settled) {
      settled = true
      handlers.onDone?.()
    }
  }
  try {
    const resp = await fetch(url, { headers, signal })
    if (!resp.ok || !resp.body) {
      // 401 等：交给调用方按错误处理
      handlers.onError?.({ code: resp.status === 401 ? 40001 : 50000, message: `连接失败（HTTP ${resp.status}）` })
      finish()
      return
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 事件以空行（\n\n）分隔；兼容 \r\n
      let sepIndex: { start: number; end: number } | null
      while ((sepIndex = findEventBoundary(buffer)) !== null) {
        const rawEvent = buffer.slice(0, sepIndex.start)
        buffer = buffer.slice(sepIndex.end)
        if (dispatchEvent(rawEvent, handlers)) {
          // result/error 为终止事件：先复位 sending，再主动关流释放连接
          finish()
          try {
            await reader.cancel()
          } catch {
            /* 忽略关流异常 */
          }
          return
        }
      }
    }
    // 冲刷剩余缓冲
    if (buffer.trim()) dispatchEvent(buffer, handlers)
    finish()
  } catch (err) {
    // 已正常终止后，服务端关流可能抛「terminated」——此时不再当作错误
    if (settled || (err as Error)?.name === 'AbortError') {
      finish()
      return
    }
    handlers.onError?.({ code: 50000, message: (err as Error)?.message || '流式连接异常' })
    finish()
  }
}

/** 找到第一个事件边界（连续两个换行），返回其起止下标；无则 null。 */
function findEventBoundary(buf: string): { start: number; end: number } | null {
  const idx = buf.search(/\r?\n\r?\n/)
  if (idx === -1) return null
  const match = buf.slice(idx).match(/^\r?\n\r?\n/)
  const len = match ? match[0].length : 2
  return { start: idx, end: idx + len }
}

/** 解析单个 SSE 事件块（event:/data: 行）并派发；返回是否为终止事件（result/error）。 */
function dispatchEvent(rawEvent: string, handlers: AskStreamHandlers): boolean {
  let eventName = 'message'
  const dataLines: string[] = []
  for (const line of rawEvent.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue // 空行 / 注释
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (dataLines.length === 0) return false
  const dataStr = dataLines.join('\n')

  switch (eventName) {
    case 'stage':
      handlers.onStage?.(safeParse<StageEvent>(dataStr) ?? { stage: 'unknown', message: dataStr })
      return false
    case 'result': {
      const parsed = safeParse<ChatQueryResponse>(dataStr)
      if (parsed) handlers.onResult?.(parsed)
      return true
    }
    case 'error':
      handlers.onError?.(safeParse<SseErrorEvent>(dataStr) ?? { code: 50000, message: dataStr })
      return true
    default:
      return false
  }
}

function safeParse<T>(text: string): T | null {
  try {
    return JSON.parse(text) as T
  } catch {
    return null
  }
}
