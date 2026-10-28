import { del, get, post } from './request'
import type { ChatQueryResponse } from '@/types'

/** 嵌入条目（管理侧返回）。 */
export interface ChatEmbedVO {
  id: number
  name: string | null
  token: string
  datasourceId: number
  datasourceIds: number[]
  /** id → 数据源真实名称（后端已解析，列表直接展示，避免前端再拉一次数据源列表）。 */
  datasourceNames?: Record<string, string>
  enabled: number
  expireAt: string | null
  allowedOrigins: string | null
  viewCount: number
  createdAt: string
}

/** 嵌入公开元信息（第三方页面展示用，不含敏感配置）。 */
export interface EmbedMeta {
  name: string | null
  datasourceIds: number[]
  /** id → 数据源名称（真实名称，供下拉显示，非"数据源 1"）。 */
  datasourceNames?: Record<string, string>
}

/** 对话问数嵌入管理接口（/api/chat-embed，需认证：chat:embed:write）。 */
export const chatEmbedApi = {
  list: () => get<ChatEmbedVO[]>('/chat-embed'),
  create: (data: {
    name?: string
    datasourceIds: number[]
    allowedOrigins?: string
    expireAt?: string | null
  }) => post<ChatEmbedVO>('/chat-embed', data),
  remove: (id: number) => del<void>(`/chat-embed/${id}`)
}

/**
 * 对话问数嵌入公开接口（/api/public/chat-embed，免认证）。
 * anonymous=true：刻意不带登录令牌，后端 /api/public/** 已放行。
 */
/** 嵌入问数·外部用户历史条目（公开接口返回）。 */
export interface EmbedHistoryItem {
  id: number
  role: 'USER' | 'BOT'
  content: string | null
  conversationId: string | null
  createdAt: string | null
}

export const publicChatEmbedApi = {
  meta: (token: string) => get<EmbedMeta>(`/public/chat-embed/${token}/meta`, { anonymous: true }),
  /** 匿名推荐问法（LLM 依数据源语义层生成，后端带缓存与降级）。超时放宽至 3 分钟。
   *  @param datasourceId 当前选中的数据源（多选嵌入切换时带入，使样例问法随数据源变化；单数据源/未传时后端回落首个）。 */
  suggestions: (token: string, datasourceId?: number | null) =>
    get<string[]>(`/public/chat-embed/${token}/suggestions`, {
      anonymous: true,
      timeout: 180000,
      params: datasourceId != null ? { datasourceId } : {}
    }),
  /** 匿名上下文追问：依某轮问数（historyId）生成"接着答案往下钻"的追问；失败/空返回空数组。 */
  followups: (token: string, historyId: number) =>
    get<string[]>(`/public/chat-embed/${token}/followups`, {
      anonymous: true,
      params: { historyId },
      timeout: 180000
    }),
  query: (
    token: string,
    question: string,
    conversationId?: string | null,
    datasourceId?: number | null,
    extUser?: string | null
  ) =>
    post<ChatQueryResponse>(
      `/public/chat-embed/${token}/query`,
      {
        question,
        conversationId: conversationId ?? null,
        datasourceId: datasourceId ?? null,
        user: extUser && extUser.trim() ? extUser.trim() : null
      },
      // 问数走完整链路（生成 SQL → 校验 → 执行），放宽至 3 分钟避免大数据量被打断
      { anonymous: true, timeout: 180000, dedupe: false }
    ),
  /** 外部用户历史（最新在前），嵌入页初始化时右侧悬浮面板拉取；未传 user 返回空。 */
  history: (token: string, user: string, limit = 50) =>
    get<EmbedHistoryItem[]>(`/public/chat-embed/${token}/history`, {
      anonymous: true,
      params: { user: user && user.trim() ? user.trim() : '', limit }
    }),
  /** 某次会话全部轮次（最旧在前），点击历史项续接时还原完整对话。 */
  conversation: (token: string, user: string, conversationId: string) =>
    get<EmbedHistoryItem[]>(`/public/chat-embed/${token}/conversation`, {
      anonymous: true,
      params: { user: user && user.trim() ? user.trim() : '', conversationId }
    })
}
