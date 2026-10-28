import { get, post } from './request'
import type { CostPatrolResult, DistillReport, MetaPatrolResult } from '@/types'

/**
 * 待治理问题沉淀接口（T11-1，/api/chat/tuning/issues）。
 *
 * 一期：只读聚合。二期 A：转写复用既有 create。二期 B：台账处置端点 + includeHandled 过滤。
 * T11-4：元数据巡检 patrol（命中行进同一台账，/issues 读侧自动合入）。
 * T12-4：成本巡检 costPatrol（窗口内成功历史聚簇出三类成本建议，同台账同合入）。
 */
export const issueDistillApi = {
  /** 拉取待治理问题清单。datasourceId 可选；includeHandled=true 则含已处置（CONVERTED/DISMISSED）。 */
  list: (datasourceId?: number, includeHandled?: boolean) => {
    const params: Record<string, unknown> = {}
    if (datasourceId != null) params.datasourceId = datasourceId
    if (includeHandled) params.includeHandled = true
    return get<DistillReport>('/chat/tuning/issues', { params })
  },

  /** 二期 B：转写成功后记录台账（只改 governance_issue，不改数据资产）。 */
  convert: (body: { datasourceId: number; normalizedKey: string; signal: string; refType: string; refId: number }) =>
    post<void>('/chat/tuning/issues/convert', body),

  /** 二期 B：忽略某条治理建议。 */
  dismiss: (body: { datasourceId: number; normalizedKey: string; signal: string; reason?: string }) =>
    post<void>('/chat/tuning/issues/dismiss', body),

  /** 二期 B：重新打开已处置的治理建议。 */
  reopen: (body: { id: number }) =>
    post<void>('/chat/tuning/issues/reopen', body),

  /**
   * 元数据巡检（T11-4）：扫语义层与指标资产出三类缺陷，命中即时进台账。
   * datasourceId 空=本租户启用数据源前 5 个；门控 METRIC/meta-patrol 关闭时后端返业务码 40301 拒用。
   */
  patrol: (datasourceId?: number) =>
    post<MetaPatrolResult>('/chat/tuning/meta-patrol', undefined,
      datasourceId != null ? { params: { datasourceId } } : undefined),

  /**
   * 成本巡检（T12-4）：窗口内成功问数历史聚簇出物化候选/慢查询/重复问法建议，命中即时进台账。
   * datasourceId 空=本租户全部数据源（按行 ds 分组）；门控 METRIC/cost-patrol 关闭时业务码 40301 拒用。
   */
  costPatrol: (datasourceId?: number) =>
    post<CostPatrolResult>('/chat/tuning/cost-patrol', undefined,
      datasourceId != null ? { params: { datasourceId } } : undefined)
}
