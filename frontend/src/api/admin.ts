import { del, get, post, put } from './request'
import type {
  AdminOverviewTrendVO,
  AdminOverviewVO,
  AdminUserForm,
  AdminUserVO,
  LlmConfigForm,
  LlmConfigVO,
  ResetPasswordForm,
  SeatQuotaForm,
  TenantForm,
  TenantVO
} from '@/types'

/**
 * 超管后台接口（/api/admin/**）。全部需 ROLE_SUPER_ADMIN（后端 SecurityConfig 保护）。
 * 跨租户管理：租户 / 账号 / 大模型配置 / 平台概览。凭证只写不读（响应仅回 hasApiKey）。
 */

/** 租户管理（/api/admin/tenants）：CRUD + 套餐/隔离/期限/配额（每日+累计）+ 席位 + 启停。 */
export const adminTenantApi = {
  list: () => get<TenantVO[]>('/admin/tenants'),
  get: (id: number) => get<TenantVO>(`/admin/tenants/${id}`),
  create: (data: TenantForm) => post<TenantVO>('/admin/tenants', data),
  update: (id: number, data: TenantForm) => put<TenantVO>(`/admin/tenants/${id}`, data),
  /** 逐租户席位/配额调整（§9.2）。 */
  updateSeats: (id: number, data: SeatQuotaForm) => put<TenantVO>(`/admin/tenants/${id}/seats`, data),
  /** 批量按套餐重置席位与每日上限；plan 省略则重置全部，返回受影响租户数。 */
  resetSeatsByPlan: (plan?: string | null) =>
    post<number>(`/admin/tenants/seats/reset-by-plan${plan ? `?plan=${plan}` : ''}`),
  setStatus: (id: number, status: number) => put<TenantVO>(`/admin/tenants/${id}/status?status=${status}`),
  remove: (id: number) => del<void>(`/admin/tenants/${id}`)
}

/** 账号管理（/api/admin/users）：跨租户 CRUD + 重置密码 + 启停。tenantId 省略=全平台。 */
export const adminUserApi = {
  list: (tenantId?: number | null) =>
    get<AdminUserVO[]>(`/admin/users${tenantId != null ? `?tenantId=${tenantId}` : ''}`),
  get: (id: number) => get<AdminUserVO>(`/admin/users/${id}`),
  create: (data: AdminUserForm) => post<AdminUserVO>('/admin/users', data),
  update: (id: number, data: AdminUserForm) => put<AdminUserVO>(`/admin/users/${id}`, data),
  resetPassword: (id: number, data: ResetPasswordForm) => post<void>(`/admin/users/${id}/reset-password`, data),
  setStatus: (id: number, status: number) => put<AdminUserVO>(`/admin/users/${id}/status?status=${status}`),
  remove: (id: number) => del<void>(`/admin/users/${id}`)
}

/** 大模型配置（/api/admin/llm-configs）：全局默认 + 租户覆盖 CRUD + 启停 + 连通性测试。 */
export const adminLlmConfigApi = {
  list: (tenantId?: number | null) =>
    get<LlmConfigVO[]>(`/admin/llm-configs${tenantId != null ? `?tenantId=${tenantId}` : ''}`),
  get: (id: number) => get<LlmConfigVO>(`/admin/llm-configs/${id}`),
  create: (data: LlmConfigForm) => post<LlmConfigVO>('/admin/llm-configs', data),
  update: (id: number, data: LlmConfigForm) => put<LlmConfigVO>(`/admin/llm-configs/${id}`, data),
  setEnabled: (id: number, enabled: number) => put<LlmConfigVO>(`/admin/llm-configs/${id}/enabled?enabled=${enabled}`),
  remove: (id: number) => del<void>(`/admin/llm-configs/${id}`),
  /** 测试表单里的配置（未保存/刚编辑），明文 Key 优先。 */
  test: (data: LlmConfigForm) => post<void>('/admin/llm-configs/test', data),
  /** 测试已保存的配置（用其已存密文 Key）。 */
  testById: (id: number) => post<void>(`/admin/llm-configs/${id}/test`)
}

/** 平台概览（/api/admin/overview）：跨租户聚合统计。 */
export const adminOverviewApi = {
  get: () => get<AdminOverviewVO>('/admin/overview'),
  /** 近 14 天趋势 + 分布数据（图表区），后端已补零对齐日期轴。 */
  getTrend: () => get<AdminOverviewTrendVO>('/admin/overview/trend')
}
