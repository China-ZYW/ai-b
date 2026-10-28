import { del, get, post, put } from './request'
import type { DashboardTemplateForm, DashboardTemplateVO } from '@/types'

/**
 * 驾驶舱内置模板接口。
 * - dashboardTemplateApi：只读，所有登录用户可用（/api/dashboard-template），供“从模板新建驾驶舱”。
 * - adminDashboardTemplateApi：增改删归口超管（/api/admin/dashboard-templates），模板为全局资源共享。
 */
export const dashboardTemplateApi = {
  list: () => get<DashboardTemplateVO[]>('/dashboard-template'),
  get: (id: number) => get<DashboardTemplateVO>(`/dashboard-template/${id}`)
}

export const adminDashboardTemplateApi = {
  list: () => get<DashboardTemplateVO[]>('/admin/dashboard-templates'),
  create: (data: DashboardTemplateForm) => post<DashboardTemplateVO>('/admin/dashboard-templates', data),
  update: (id: number, data: DashboardTemplateForm) =>
    put<DashboardTemplateVO>(`/admin/dashboard-templates/${id}`, data),
  remove: (id: number) => del<void>(`/admin/dashboard-templates/${id}`)
}
