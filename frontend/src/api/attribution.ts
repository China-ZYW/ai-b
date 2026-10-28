import { get, post } from './request'
import type { AttributionOptionVO, AttributionReq, AttributionVO } from '@/types'

/**
 * 归因分析接口（/api/attribution）：回答「指标为什么涨/跌」。
 *
 * 与问数主链路（/api/chat）分开的原因：一次归因要同时返回趋势、量价因子、维度贡献
 * 三份结构与语义都不同的产物，并把三套 SQL 分别透出供审计复算；问数技能契约只能承载单一结果集。
 * 快照页在识别到波动类问题时单独调用本接口渲染「维度归因」模块。
 *
 * 权限：两个端点均需后端权限点（归因会按维度成员展开业务表现，属敏感经营信息）。
 * 维度列表已由后端剔除语义层标记的敏感列，形成「权限 + 字段级」双重护栏。
 */
export const attributionApi = {
  /** 执行一次归因分析（指标 + 维度 + 对比基准 + 期间）。 */
  run: (data: AttributionReq) => post<AttributionVO>('/attribution/run', data),
  /** 列出某指标可用于归因的维度（已剔除敏感列）及该指标沉淀的归因默认值。 */
  dimensions: (metricId: number) =>
    get<AttributionOptionVO>('/attribution/dimensions', { params: { metricId } })
}
