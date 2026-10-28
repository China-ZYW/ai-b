import { del, get, post, put } from './request'
import type { ChartRule } from '@/types'

/**
 * 图表规则接口（配置化升级 V2，/api/chart-rule）：关键词→图型（KEYWORD）与结构阈值（THRESHOLD）配置。
 * 写入落本租户行，覆盖全局种子规则；租户隔离由服务层显式解析。
 */
export const chartRuleApi = {
  /** 生效范围内的规则（本租户行 + 全局行）。 */
  list: () => get<ChartRule[]>('/chart-rule'),
  /** 新建规则。 */
  create: (data: ChartRule) => post<ChartRule>('/chart-rule', data),
  /** 编辑规则。 */
  update: (data: ChartRule) => put<ChartRule>('/chart-rule', data),
  /** 删除规则（仅本租户行）。 */
  remove: (id: number) => del<void>(`/chart-rule/${id}`)
}
