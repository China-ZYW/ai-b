import { get, post } from './request'
import type { AuditLogVO, AuditNlResult, AuditQuery, PageResult } from '@/types'

/**
 * 平台后台 · 审计日志接口（/api/admin/audit，§9.2 治理组，只读跨租户检索 + 导出）。
 *
 * 全部需 `admin:audit:read`（后端 platformOnly 强校，权限矩阵 YNYN → SUPER_ADMIN / AUDITOR）。
 * 检索条件全部可空（空则不约束），按时间倒序；导出走独立上限（后端至多 5000 条），
 * 且每次导出后端会自审一条 AUDIT_EXPORT（§10.2「看日志的人也要被看」）。
 */
export const adminAuditApi = {
  /** 跨租户分页检索审计日志。 */
  list: (params: AuditQuery = {}) => get<PageResult<AuditLogVO>>('/admin/audit', { params }),
  /** 按同条件导出（一次性至多后端 EXPORT_MAX 条），前端据此生成 CSV。 */
  export: (params: Omit<AuditQuery, 'limit' | 'offset'> = {}) =>
    get<AuditLogVO[]>('/admin/audit/export', { params }),
  /**
   * 审计智能查询（T11-3 平台域）：一句话抽槽位后跨租户三源融合检索，命中行带租户名。
   * 需 `admin:audit:read`（platformOnly）+ 后端门控 AUDIT/nl-query 启用；LLM 解析失败返
   * degraded=true（非 500），前端据此回落人工筛选。每次查询后端自审一条 AUDIT_NL_QUERY。
   */
  nlQuery: (question: string) => post<AuditNlResult>('/admin/audit/nl-query', { question })
}
