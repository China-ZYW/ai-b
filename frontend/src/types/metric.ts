/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
import type { ChartSpec, QueryResult } from './query'

/* ============================ 指标与驾驶舱（Feature 2） ============================ */

/** 指标视图对象。chartSpec 以 JSON 对象透传（可直接喂 buildChartOption）。
 *  columnLabels 为指标级列展示名（物理列名 → 自定义名），主要给汇总/派生列取业务叫法；无自定义时为空。 */
export interface MetricVO {
  id: number
  name: string
  description?: string | null
  /** 指标展示别名（图表标题/大屏组件默认名；空=回落 name）。 */
  alias?: string | null
  /** canonical 指标口径表达式（如 SUM(amount)）；趋势/因子/归因共用，空=未沉淀。 */
  definition?: string | null
  /** 归因默认维度（物理列名）；保存指标时一并沉淀，下次打开自动执行归因。 */
  attributionDimension?: string | null
  /** 归因默认对比基准 MOM/YOY/PEAK；空=回落 MOM。 */
  baselineType?: string | null
  /** 所属分组（resource_group.id，与驾驶舱共用同一张分组表，指标域；空=未分组）。 */
  groupId?: number | null
  /** 该指标的新鲜度 SLA 覆盖（小时）；空=回落全局阈值。 */
  freshnessHours?: number | null
  datasourceId?: number | null
  question?: string | null
  sql?: string | null
  chartSpec?: ChartSpec | null
  columnLabels?: Record<string, string> | null
  createdAt?: string | null
  updatedAt?: string | null
  /** 数据最后成功刷新时间；空=从未刷新（与「已过期」区分）。 */
  refreshedAt?: string | null
  /** 是否已超 SLA（服务端判定，前端只负责标黄，不在前端另算口径）。 */
  stale?: boolean | null
  /** 是否从未刷新过（文案用「尚未取数」，同样标黄）。 */
  neverRefreshed?: boolean | null
  /** 距今小时数；-1 表示从未刷新。 */
  ageHours?: number | null
  /** 来源标记：AI=指标后台自动分析生成；空=人工维护（MANUAL）。前端据此展示 AI 自动识别标识。 */
  source?: string | null
}

/** 指标保存 / 更新表单（来源于一次问数结果的“保存为指标”）。
 *  columnLabels 优先级高于语义层别名，仅影响表头/图例文案，不影响图表字段匹配与 SQL。
 *  alias 为指标展示别名（与 name 分离），图表标题/大屏组件默认用 alias，空则回落 name。 */
export interface MetricForm {
  name: string
  description?: string | null
  alias?: string | null
  /** canonical 指标口径表达式（如 SUM(amount)）；趋势/因子/归因共用，空=未沉淀。 */
  definition?: string | null
  /** 归因默认维度（物理列名）。 */
  attributionDimension?: string | null
  /** 归因默认对比基准 MOM/YOY/PEAK。 */
  baselineType?: string | null
  /** 新鲜度 SLA 覆盖（小时）；空或 ≤0 = 回落全局阈值。 */
  freshnessHours?: number | null
  datasourceId: number
  question?: string | null
  sql: string
  chartSpec?: ChartSpec | null
  columnLabels?: Record<string, string> | null
  /** 来源标记：AI=指标后台自动分析生成；人工保存不传。 */
  source?: string | null
}

/** 指标实时刷新数据：重跑已存 SQL（不消耗 LLM、不计入配额）。 */
export interface MetricDataVO {
  metricId: number
  name?: string | null
  chartSpec?: ChartSpec | null
  result: QueryResult
}

