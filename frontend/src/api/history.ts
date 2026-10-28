import { del, get, post } from './request'
import type { ConversationVO, HistoryDetailVO, HistoryVO, PageResult } from '@/types'

/** 点踩修正建议（T11-2）：均可空，仅 vote=-1 时后端才落库；不填即退回一键直踩。 */
export interface FeedbackCorrectionPayload {
  expectedSql?: string | null
  note?: string | null
  reason?: string | null
}

/** 查询历史接口（/api/history）。全部以当前租户 + 当前用户为边界。 */
export const historyApi = {
  /** 分页查询"我的历史"，可按数据源过滤。 */
  list: (params: { datasourceId?: number; limit?: number; offset?: number } = {}) =>
    get<PageResult<HistoryVO>>('/history', { params }),
  /** 会话列表（多轮对话侧边栏）。 */
  conversations: (limit = 50) => get<ConversationVO[]>('/history/conversations', { params: { limit } }),
  /** 单条历史详情。 */
  detail: (id: number) => get<HistoryVO>(`/history/${id}`),
  /** 历史回放详情：结果快照（老记录由后端重跑 SQL 回放）+ 图表规格，不耗 LLM/配额。 */
  detailData: (id: number) => get<HistoryDetailVO>(`/history/${id}/detail`),
  /** 删除单条历史。 */
  remove: (id: number) => del<void>(`/history/${id}`),
  /** 反馈（点赞 1 / 点踩 -1）：赞且 OK 的历史进入少样本晋升池；点踩可附修正建议（T11-2，仅喂治理转写）。 */
  feedback: (id: number, vote: 1 | -1, correction?: FeedbackCorrectionPayload) =>
    post<void>(`/history/${id}/feedback`, { vote, ...(correction ?? {}) })
}
