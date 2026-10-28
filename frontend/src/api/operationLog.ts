import { get } from './request'
import type { OperationLogVO, OperationQuery, PageResult } from '@/types'

/**
 * 平台后台 · 操作日志接口（/api/admin/audit/operations，§10.2 审计落地之二，只读跨租户检索 + 导出）。
 *
 * 全部需 `admin:audit:read`（后端 platformOnly 强校，权限矩阵 YNYN → SUPER_ADMIN / AUDITOR）。
 * 检索条件全部可空（空则不约束），按时间倒序；导出走后端独立上限（至多 5000 条），
 * 且每次导出后端会自审一条 AUDIT_EXPORT（资源类型 OPERATION_LOG，「看日志的人也要被看」）。
 *
 * <p>与 {@link ./adminAudit} 的区别：本接口读 `audit_log`（每一次 HTTP 操作），而非 `auth_audit_log`
 * （仅登录/权限事件）。二者字段结构不同，故单列接口与类型。
 */
export const operationLogApi = {
  /** 跨租户分页检索操作日志。 */
  list: (params: OperationQuery = {}) => get<PageResult<OperationLogVO>>('/admin/audit/operations', { params }),
  /** 按同条件导出（一次性至多后端 EXPORT_MAX 条），前端据此生成 CSV。 */
  export: (params: Omit<OperationQuery, 'limit' | 'offset'> = {}) =>
    get<OperationLogVO[]>('/admin/audit/operations/export', { params })
}
