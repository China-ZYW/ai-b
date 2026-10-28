/** 定时报表域类型（T12-1）。消费方一律从 @/types 导入（index.ts 再导出本文件）。 */

/** 预置频率闭集（后端 ReportService.FREQS 同步）：日 / 周 / 小时。 */
export type ReportFreq = 'DAILY' | 'WEEKLY' | 'HOURLY'
/** 推送渠道闭集（后端 ReportService.CHANNELS 同步）。 */
export type ReportChannel = 'INAPP' | 'EMAIL' | 'BOTH'
/** 单轮执行结果三态。 */
export type ReportRunStatus = 'OK' | 'FAILED' | 'SKIPPED'

/** 定时报表列表项（后端 ReportDtos.ReportVO）。 */
export interface ScheduledReportVO {
  id: number
  name: string
  templateId: number
  /** 来源模板名；模板已删除时为「（模板已删除）」占位。 */
  templateName?: string | null
  /** 模板参数固定值 JSON。 */
  paramsJson?: string | null
  freq: ReportFreq
  /** HH:mm（DAILY/WEEKLY 用；HOURLY 为空）。 */
  runTime?: string | null
  /** ISO 周几集 "1,3,5"（WEEKLY 用）。 */
  weekdays?: string | null
  channel: ReportChannel
  /** 站内信接收人 user id 逗号分隔（空=只发创建人）。 */
  inappUids?: string | null
  emailTargets?: string | null
  enabled: boolean
  lastRunAt?: string | null
  lastStatus?: ReportRunStatus | null
  /** 上轮失败/注记摘要（不含收件人）。 */
  lastError?: string | null
  createdAt?: string | null
}

/** 创建/覆盖式更新共用入参（后端 ReportDtos.ReportUpsertCmd；PUT 全字段覆盖，启停并入）。 */
export interface ReportUpsertCmd {
  name: string
  templateId: number
  paramsJson?: string | null
  freq: ReportFreq
  runTime?: string | null
  weekdays?: string | null
  channel: ReportChannel
  inappUids?: string | null
  emailTargets?: string | null
  enabled?: boolean | null
}
