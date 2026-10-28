import { del, get, post, put } from './request'
import type {
  AiLayoutRequest,
  AiLayoutVO,
  AutoDesignForm,
  DashboardAccessResponse,
  DashboardDataVO,
  DashboardFilter,
  DashboardForm,
  DashboardGroupForm,
  DashboardGroupVO,
  DashboardMoveForm,
  DashboardShareForm,
  DashboardShareVO,
  DashboardVO,
  FromTemplateForm,
  MaterialUploadResult
} from '@/types'

/**
 * 驾驶舱 / 大屏接口（/api/dashboard）：CRUD、布局保存、批量刷新、一键自动布局、从内置模板实例化。
 * 租户隔离。data 供大屏按 refreshSeconds 轮询，一次返回布局 + 各 widget 实时数据。
 */
export const dashboardApi = {
  list: () => get<DashboardVO[]>('/dashboard'),
  get: (id: number) => get<DashboardVO>(`/dashboard/${id}`),
  create: (data: DashboardForm) => post<DashboardVO>('/dashboard', data),
  update: (id: number, data: DashboardForm) => put<DashboardVO>(`/dashboard/${id}`, data),
  remove: (id: number) => del<void>(`/dashboard/${id}`),
  /**
   * 大屏批量刷新。filter 为可选下钻/联动筛选（{dim,value}），传入后整屏按该维度切片联动。
   * 以 JSON 字符串透传，后端 DashboardFilter 解析（非法/缺 dim 按不过滤处理）。
   */
  data: (id: number, filter?: DashboardFilter | null) =>
    get<DashboardDataVO>(`/dashboard/${id}/data`, {
      params: filter && filter.dim ? { filter: JSON.stringify(filter) } : undefined
    }),
  autoDesign: (data: AutoDesignForm) => post<DashboardVO>('/dashboard/auto-design', data),
  fromTemplate: (data: FromTemplateForm) => post<DashboardVO>('/dashboard/from-template', data),
  /** 移动驾驶舱到指定分组（groupId 空=移出分组）。 */
  moveGroup: (id: number, data: DashboardMoveForm) =>
    put<DashboardVO>(`/dashboard/${id}/group`, data),
  /** 查询大屏分享状态（问题2，供分享对话框预填）。 */
  shareStatus: (id: number) => get<DashboardShareVO>(`/dashboard/${id}/share`),
  /** 开启/更新/关闭大屏公开分享（问题2）：返回可访问的公开链接。 */
  configShare: (id: number, data: DashboardShareForm) =>
    put<DashboardShareVO>(`/dashboard/${id}/share`, data),
  /** AI 智能设计（大模型自定义布局 + 样式填充）：返回建议，不落库。失败时 source=algorithm。 */
  aiLayout: (data: AiLayoutRequest) => post<AiLayoutVO>('/dashboard/ai-layout', data),
  /** 驾驶舱 AI 自动化大屏 Skill：基于数据源指标自动编排大屏（已改为后台异步，submitted=true 表示已提交，此时无 dashboardId）。 */
  aiBuild: (datasourceId: number, intent?: string) =>
    post<{ datasourceId: number; metrics: number; widgets: number; dashboardId?: number; source?: string; summary?: string; submitted?: boolean }>(
      '/dashboard/ai', null, intent ? { params: { datasourceId, intent } } : { params: { datasourceId } }
    ),
  /** 上传大屏背景图（范围 B）：返回可公开访问的 URL，写入 background=url(...) 即可。 */
  uploadMaterial: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return post<MaterialUploadResult>('/dashboard/materials/upload', fd)
  }
}

/**
 * 驾驶舱分组接口（/api/dashboard/group）：多层级目录树。
 * 后端返回扁平列表（parentId 成树），前端 groupTree 组树渲染侧边栏。
 * 权限点复用驾驶舱既有码：读 dashboard:item:read / 写 dashboard:item:write / 删 dashboard:item:delete。
 */
export const dashboardGroupApi = {
  list: () => get<DashboardGroupVO[]>('/dashboard/group'),
  create: (data: DashboardGroupForm) => post<DashboardGroupVO>('/dashboard/group', data),
  update: (id: number, data: DashboardGroupForm) => put<DashboardGroupVO>(`/dashboard/group/${id}`, data),
  remove: (id: number) => del<void>(`/dashboard/group/${id}`)
}

/** 大屏公开访问接口（/api/public/dashboard，免认证，实时数据）。
 *  filter 为可选下钻/联动筛选（透传 JSON），公开分享页与登录态大屏保持一致的联动体验。 */
export const publicDashboardApi = {
  access: (token: string, password?: string, filter?: DashboardFilter | null) =>
    get<DashboardAccessResponse>(`/public/dashboard/${token}`, {
      params: {
        ...(password ? { password } : {}),
        ...(filter && filter.dim ? { filter: JSON.stringify(filter) } : {})
      },
      anonymous: true
    })
}
