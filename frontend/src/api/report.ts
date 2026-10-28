import { del, get, post, put } from './request'
import type { ReportUpsertCmd, ScheduledReportVO } from '@/types'

/**
 * 定时报表接口（/api/reports，T12-1）。
 *
 * 权限复用高级查询既有权限族（后端 `ReportController` 同款，不新建权限码）：
 * 查看 `history:query:read`，创建/更新/删除/立即跑 `chat:query:execute`。
 * 门控 `ASK / schedule`（默认开）：关闭后全部端点 40301，前端同步隐藏入口。
 * 归属边界：所有操作仅属主（后端 uid 比对），跨租户由拦截器硬挡，前端不传 tenantId/uid。
 */
export const reportApi = {
  /** 我的报表列表（含上轮执行状态三字段）。 */
  list: () => get<ScheduledReportVO[]>('/reports'),
  /** 创建报表任务，返回新 id。 */
  create: (data: ReportUpsertCmd) => post<number>('/reports', data),
  /** 覆盖式更新（全字段提交，启停并入；防改一半留一半同模板更新先例）。 */
  update: (id: number, data: ReportUpsertCmd) => put<null>(`/reports/${id}`, data),
  /** 删除报表任务。 */
  remove: (id: number) => del<null>(`/reports/${id}`),
  /** 手动立即执行（与调度同一条 runOnce 链路），返回本轮状态 OK/FAILED/SKIPPED。 */
  runNow: (id: number) => post<string>(`/reports/${id}/run-now`)
}
