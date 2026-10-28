/**
 * 嵌入问数页私有类型与常量（T7.3 自 EmbedChatView 拆出，逐字搬移）。
 */

export interface Msg {
  role: 'user' | 'bot'
  text?: string
  loading?: boolean
  error?: string
  /** 本轮问数落库 id：用于拉取该轮的上下文追问。 */
  historyId?: number
  /** 上下文追问（"接着这轮答案往下钻"的问题；空/失败回落全局样例问法）。 */
  followups?: string[]
  /** AI 解读：一句话结论 + 要点 + 异常（与主站「AI 数据解读」同口径）。 */
  summary?: string
  keyPoints?: string[]
  anomalies?: string[]
  columns: string[]
  /** 展示用行（最多 20 行，避免 iframe 内过长）。 */
  rows: unknown[][]
  /** 导出用全量行：展示截断不影响导出（后端返回多少就导出多少）。 */
  exportRows?: unknown[][]
  rowCount?: number
  more?: boolean
  meta?: string
}

/** LLM 不可用时的最终兜底（保证页面不空白）。 */
export const FALLBACK_TIPS = [
  '最近的数据总体情况如何？',
  '按维度做一次排行',
  '看看近期的趋势变化',
  '各分类的占比分别是多少？'
]
