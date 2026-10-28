import { del, get, post, put } from './request'
import type {
  DashboardGroupForm,
  DashboardGroupVO,
  DashboardMoveForm,
  MetricDataVO,
  MetricForm,
  MetricVO
} from '@/types'

/**
 * 指标接口（/api/metric）：把问数结果保存为可复用指标 + CRUD + 实时刷新。租户隔离。
 * refresh 重跑已存 SQL 返回最新数据，不消耗 LLM、不计入问数配额。
 */
export const metricApi = {
  list: () => get<MetricVO[]>('/metric'),
  get: (id: number) => get<MetricVO>(`/metric/${id}`),
  create: (data: MetricForm) => post<MetricVO>('/metric', data),
  update: (id: number, data: MetricForm) => put<MetricVO>(`/metric/${id}`, data),
  remove: (id: number) => del<void>(`/metric/${id}`),
  refresh: (id: number) => post<MetricDataVO>(`/metric/${id}/refresh`),
  /** 移动指标到指定分组（groupId 空=移出分组）。 */
  moveGroup: (id: number, data: DashboardMoveForm) => put<MetricVO>(`/metric/${id}/group`, data)
}

/**
 * 指标库分组接口（/api/metric/group）：多层级目录树。
 *
 * <p>与驾驶舱<b>共用</b>后端 {@code resource_group} 表，靠 resource_type 分域（METRIC / DASHBOARD），
 * 两棵树彼此独立、互不越界——这就是「指标库复用分组」：一套表与服务，两个资源域各一棵树。
 * 权限点复用指标库既有码：读 metric:item:read / 写 metric:item:write / 删 metric:item:delete。
 */
export const metricGroupApi = {
  list: () => get<DashboardGroupVO[]>('/metric/group'),
  create: (data: DashboardGroupForm) => post<DashboardGroupVO>('/metric/group', data),
  update: (id: number, data: DashboardGroupForm) => put<DashboardGroupVO>(`/metric/group/${id}`, data),
  remove: (id: number) => del<void>(`/metric/group/${id}`)
}
