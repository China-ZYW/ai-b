/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
import type { PlanType } from './common'
import type { AttributionVO } from './org'
import type { DashboardDataVO } from './alert'

/* ============================ 问数 / 结果 / 图表 ============================ */

/** QueryResult：查询执行结果（columns 为列名字符串数组，rows 为二维数组）。
 *  columns 是字段身份标识（图表 xField/yFields 按它匹配）；columnLabels 仅为语义层业务别名的展示名，
 *  与 columns 等长同序，缺省表示无别名、展示时回落列名。 */
export interface QueryResult {
  sql: string
  columns: string[]
  columnLabels?: string[] | null
  rows: unknown[][]
  rowCount: number
  executionTimeMs: number
  /**
   * 「结果列名 →（码值→中文标签）」展示映射：语义列配了「枚举字典」后，表格与图表里
   * status=1 显示成「已支付」。与 columnLabels 一样<b>只影响显示</b>——导出与取值仍用原始码值。
   * 后端无列命中时不下发该字段（undefined），前端零改动。
   */
  enumDicts?: Record<string, Record<string, string>> | null
}

/** ChartType：后端枚举名。AREA=面积图、HBAR=横向条形图（配置化升级 V2）；
 *  HEATMAP/MAP/RADAR/FUNNEL/GAUGE/SCATTER 为 AI 增强升级 V3 新增。 */
export type ChartType =
  | 'LINE'
  | 'BAR'
  | 'PIE'
  | 'AREA'
  | 'HBAR'
  | 'HEATMAP'
  | 'MAP'
  | 'RADAR'
  | 'FUNNEL'
  | 'GAUGE'
  | 'SCATTER'
  | 'TABLE'

/** 数值格式：COMPACT=万亿紧凑（150万）/ THOUSAND=千分位 / PLAIN=原值。 */
export type ChartValueFormat = 'COMPACT' | 'THOUSAND' | 'PLAIN'

/** 图例位置；hidden=不展示图例（靠 tooltip 与轴标签表达）。 */
export type ChartLegendPosition = 'top' | 'bottom' | 'left' | 'right' | 'hidden'

/** ChartStyle：图表展示样式（轴名/单位/数值格式/图例）。
 *  与后端 {@code com.aibi.module.chat.chart.ChartStyle} 逐字段对齐，随 chart_spec JSON 透传落库，
 *  故问数结果、指标预览、驾驶舱 widget 与公开分享页表现一致。全部可空：空值按内置默认渲染，
 *  老数据（无 style）行为不变。 */
export interface ChartStyle {
  /** X 轴名称（空=回落维度列展示名）。 */
  xAxisName?: string | null
  /** Y 轴名称（空=回落首个度量列展示名；多度量时留空由图例表达）。 */
  yAxisName?: string | null
  /** 度量单位（元/件/%/次…），拼到轴名与 tooltip 数值之后。 */
  valueUnit?: string | null
  valueFormat?: ChartValueFormat | string | null
  /** 小数位（空=自动：整数原样、小数至多 2 位）。 */
  valueDecimals?: number | null
  legendPosition?: ChartLegendPosition | string | null
  /** X 轴标签旋转角度 0~90（空=按类别数自适应）。 */
  xLabelRotate?: number | null
}

/** ChartSpec：图表推荐规格。TABLE 时 xField/yFields/seriesField 可空。
 *  style 为展示样式，由后端自动推断（语义层单位 → 列名关键词）或用户手工设置。 */
export interface ChartSpec {
  type: ChartType
  title?: string | null
  xField?: string | null
  yFields?: string[] | null
  seriesField?: string | null
  reason?: string | null
  style?: ChartStyle | null
}

/** ChatQueryRequest：问数请求。 */
export interface ChatQueryRequest {
  datasourceId: number
  question: string
  conversationId?: string | null
  /** 期望使用的问数技能编码（可空=自动路由）；仅调试/运营用，日常留空由后端择优。 */
  skill?: string | null
  /** 本次是否为已完成口径澄清后的重发（T10-2，可空=false）：点选澄清候选后置 true，后端据此跳过反问门、锁定口径。 */
  clarified?: boolean | null
}

/**
 * SkillInfo：本次答问所用技能（V4 Skill 模式）。
 * llmFree 为 true 表示未调用大模型（直连 SQL / 指标直达 / 样例复用），也不消耗问数配额。
 */
export interface SkillInfo {
  code: string
  name: string
  llmFree: boolean
  reason?: string | null
}

/** SkillVO：技能清单项（GET /api/chat/skills）。fallback 为兜底技能（恒命中）。 */
export interface SkillVO {
  code: string
  name: string
  description: string
  order: number
  llmFree: boolean
  fallback: boolean
}

/** LineageTable：溯源来源表项（T10-3）。aliasCn/description 来自语义层，未登记时为 null，只显示物理表名。 */
export interface LineageTable {
  table: string
  aliasCn?: string | null
  description?: string | null
}

/** Lineage：结果溯源元信息（T10-3）。实际执行 SQL 涉及的来源表清单 + 执行 SQL 本身。 */
export interface Lineage {
  tables: LineageTable[]
  sql?: string | null
}

/** AnomalyMark：单处环比波动标记（T10-4b）。定位以 timePoint+colName 为准，rowIdx 仅为按时间序提示。 */
export interface AnomalyMark {
  timePoint: string
  rowIdx: number
  colIdx: number
  colName: string
  value: unknown
  prevValue: unknown
  /** 变化百分比（带符号，如 37.8 / -52.0） */
  pctChange: number
  increase: boolean
}

/** AnomalyMarks：环比/异常自动标记（T10-4b）。仅时间序列形态命中时下发。 */
export interface AnomalyMarks {
  thresholdPct: number
  timeCol: string
  /** 一句话事实结论：只报数字与方向，不做业务归因 */
  summary: string
  items: AnomalyMark[]
}

/** ResultMeta：结果完整度元信息（AI 增强升级 V3）。confidence 由 AI 质量校验分归一化(0~1)。 */
export interface ResultMeta {
  truncated: boolean
  totalRows?: number | null
  returnedRows: number
  repaired: boolean
  emptyRetried: boolean
  sqlAttempts: number
  confidence?: number | null
  crossChecked?: boolean
  /**
   * 结果溯源（T10-3）：来源表清单与执行 SQL，供面板「数据来源」块核对数字出处。
   * 开关关 / 无表 / 装配失败时后端不下发（undefined），前端整体隐藏该块。
   */
  lineage?: Lineage | null
  /**
   * 环比/异常自动标记（T10-4b）：时间序列相邻周期波动超阈值时下发，明细表格高亮 + 事实结论行。
   * 开关关 / 非时间序列 / 无命中 / 装配失败时后端不下发（undefined），前端渲染与从前一致。
   */
  marks?: AnomalyMarks | null
}

/** Insight：AI 结果解读（summary 一句话结论 + keyPoints 要点 + anomalies 异常）。 */
export interface Insight {
  summary?: string | null
  keyPoints?: string[] | null
  anomalies?: string[] | null
}

/** Verify：AI 质量校验（answered 是否回答 + score 0~100 + issues 问题点 + suggestion 建议）。 */
export interface Verify {
  answered?: boolean | null
  score?: number | null
  issues?: string[] | null
  suggestion?: string | null
}

/** ChatQueryResponse：问数响应（结果 + 图表 + 会话/历史 id + V3 完整度元信息/AI 解读/质量校验）。 */
export interface ChatQueryResponse {
  conversationId?: string | null
  historyId?: number | null
  result: QueryResult
  chart?: ChartSpec | null
  meta?: ResultMeta | null
  insight?: Insight | null
  verify?: Verify | null
  /** 本次由哪个技能回答（V4 Skill 模式，老数据可空）。 */
  skill?: SkillInfo | null
  /**
   * 多意图拆分子答清单（T10-4，可空）：单意图路径为 null（前端无感、走现有单结果视图）；
   * 多意图路径下顶层 result/chart 为空，各子问结果在此逐段渲染为多段答案卡。
   */
  parts?: SubAnswer[] | null
  /**
   * 该次问数当时跑过的维度归因结果（历史回放时回填；未跑过归因的记录为空）。
   * 与 insight / verify 同源——后端只存不重算，回放时原样带回给归因面板展示。
   */
  attribution?: AttributionVO | null
  /**
   * 口径歧义澄清提案（T10-2，可空）：命中歧义需反问时非空，此时 result 等执行字段为空（未执行、未扣配额）。
   * 非空时前端渲染澄清候选卡，用户点选后把选项的 refineQuestion 回填输入框并带 clarified=true 重发。
   */
  clarify?: ClarifyProposal | null
}

/**
 * ClarifyOption：口径澄清的单个候选项（T10-2）。点选后把 refineQuestion 回填重发以锁定口径。
 */
export interface ClarifyOption {
  /** 候选标识（term:规范名 / metric:id），用于与 defaultOptionCode 对齐。 */
  code: string
  /** 展示文案（如“流水（口径：营收）”）。 */
  label: string
  /** 选中后回填重发的完整问句（已把歧义词替换为该口径的明确表述）。 */
  refineQuestion: string
}

/**
 * ClarifyProposal：口径歧义澄清提案（T10-2）。后端判定提问存在口径歧义时随响应返回的一份候选卡。
 * reason：MULTI_CALIBER=一词多口径 / SIMILAR_METRIC=近似指标名。
 */
export interface ClarifyProposal {
  reason: string
  options: ClarifyOption[]
  defaultOptionCode?: string | null
}

/**
 * SubAnswer：多意图拆分（T10-4）的单个原子子问作答。
 * answered=false 承载“批量超时未作答 / 代价超预算被拒”，前端显示“该子问未作答，请单独追问”。
 */
export interface SubAnswer {
  subQuestion: string
  result?: QueryResult | null
  chart?: ChartSpec | null
  insight?: Insight | null
  verify?: Verify | null
  skill?: SkillInfo | null
  answered: boolean
}

/* ============================ 历史 / 会话 ============================ */

/** HistoryVO：查询历史条目。status = OK / FAILED。 */
export interface HistoryVO {
  id: number
  conversationId?: string | null
  datasourceId?: number | null
  question: string
  sql?: string | null
  rowCount?: number | null
  elapsedMs?: number | null
  status: 'OK' | 'FAILED' | string
  /** 用户反馈：1=赞 / -1=踩 / null=未反馈（赞且 OK 进入少样本晋升池）。 */
  feedback?: number | null
  /** 问题中显式指定的图型（可空）。 */
  chartRequested?: string | null
  /** 最终推荐/使用的图型（可空）。 */
  chartUsed?: string | null
  createdAt: string
}

/** HistoryDetailVO：历史回放详情（历史元数据 + 结果快照/回放结果 + 图表规格 + AI 解读 + 质量校验）。 */
export interface HistoryDetailVO {
  history: HistoryVO
  result?: QueryResult | null
  chart?: ChartSpec | null
  /** AI 数据解读（问数当时落库；老记录或关闭解读时为空）。 */
  insight?: Insight | null
  /** AI 质量校验（问数当时落库；老记录或关闭校验时为空）。 */
  verify?: Verify | null
  /**
   * 维度波动归因结果（问数当时跑完回写落库；可空）。
   * 与 insight / verify 同一套"问数当时产物、只存不重算"口径——回放据此还原归因面板，
   * 老历史从未跑过归因则为空，前端归因区块保持未执行状态。
   */
  attribution?: AttributionVO | null
}

/** ConversationVO：会话摘要（左侧对话列表）。 */
export interface ConversationVO {
  conversationId: string
  datasourceId?: number | null
  lastQuestion: string
  messageCount?: number | null
  updatedAt: string
}

/* ============================ 收藏 ============================ */

export interface FavoriteRequest {
  datasourceId: number
  title?: string | null
  question: string
  sql?: string | null
}

export interface FavoriteVO {
  id: number
  datasourceId?: number | null
  title?: string | null
  question: string
  sql?: string | null
  createdAt: string
}

/* ============================ 配额 ============================ */

/**
 * QuotaStatus：limit/used/remaining 沿用为「每日」口径（-1 不限）；
 * 累计与期限通过 totalLimit/totalUsed/totalRemaining/expireAt/expired 追加。
 * 注：后端 Jackson non_null —— expireAt 为 null 时会从 JSON 省略。
 */
export interface QuotaStatus {
  plan: PlanType | string
  limit: number
  used: number
  remaining: number
  totalLimit: number
  totalUsed: number
  totalRemaining: number
  expireAt?: string | null
  expired?: boolean
}

/* ============================ 分享 ============================ */

/** ShareRequest：创建分享（提交结果快照）。 */
export interface ShareRequest {
  datasourceId: number
  question: string
  sql?: string | null
  result: QueryResult
  chart?: ChartSpec | null
  password?: string | null
  expireHours?: number | null
  watermark?: boolean | null
}

/** ShareVO：我的分享列表项（不含快照与密码）。 */
export interface ShareVO {
  id: number
  token: string
  url: string
  question: string
  expireAt?: string | null
  watermark: boolean
  hasPassword: boolean
  viewCount?: number | null
  createdAt: string
  /** 分享类型：metric=指标/结果快照分享；dashboard=驾驶舱/大屏分享。 */
  type?: 'metric' | 'dashboard'
}

/** 公开访问状态。 */
export type ShareAccessStatus = 'OK' | 'PASSWORD_REQUIRED' | 'PASSWORD_WRONG' | 'EXPIRED' | 'NOT_FOUND'

/** SharedView：公开访问页展示数据（只含快照）。 */
export interface SharedView {
  question: string
  sql?: string | null
  result: QueryResult
  chart?: ChartSpec | null
  watermark: boolean
  createdAt: string
  expireAt?: string | null
}

/** ShareAccessResponse：公开访问统一响应。 */
export interface ShareAccessResponse {
  status: ShareAccessStatus
  requiresPassword?: boolean | null
  view?: SharedView | null
}

/* ============================ SSE 事件 ============================ */

/**
 * SSE 分步进度事件（event: stage）。
 * stage 取值：start / schema / intent / generate / sql / execute / repair / chart / done。
 * 注意后端 payload 的描述字段名为 message（非 detail）。
 */
export interface StageEvent {
  stage: string
  message?: string
}

/** SSE 错误事件（event: error）。 */
export interface SseErrorEvent {
  code: number
  message: string
}

/* ============================ 大屏分享（问题2） ============================ */

/** DashboardShareForm：开启/更新/关闭大屏公开分享。enabled=false 清除令牌/密码/有效期，旧链接立即失效。 */
export interface DashboardShareForm {
  enabled: boolean
  password?: string | null
  expireHours?: number | null
}

/** DashboardShareVO：大屏分享状态（供分享对话框预填）。hasPassword 不回填明文。 */
export interface DashboardShareVO {
  enabled?: boolean | null
  token?: string | null
  url?: string | null
  expireAt?: string | null
  hasPassword?: boolean | null
}

/** DashboardAccessResponse：大屏公开访问结果（实时数据）。status 复用 ShareAccessStatus 语义。 */
export interface DashboardAccessResponse {
  status: ShareAccessStatus
  requiresPassword?: boolean | null
  data?: DashboardDataVO | null
}

