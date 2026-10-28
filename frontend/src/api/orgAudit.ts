import { get, post } from './request'
import type { AuditLogVO, AuditNlResult, AuditQuery, PageResult } from '@/types'

/**
 * 组织内 · 审计日志接口（/api/org/audit，§9.2 组织设置，只读检索本组织 + 导出）。
 *
 * 全部需 `audit:log:read`（组织矩阵 YYNNNN → 仅 OWNER/ADMIN）。与平台后台 `adminAuditApi` 的关键差异：
 * **不接受 tenantId 参数**——租户范围由后端从登录上下文强制收敛为当前组织，前端无法越权检索他组织审计。
 * 检索条件（动作/结果/操作人/时间）全部可空，按时间倒序；导出走后端独立上限并会自审一条 AUDIT_EXPORT。
 */
export const orgAuditApi = {
  /** 分页检索本组织审计日志。 */
  list: (params: Omit<AuditQuery, 'tenantId'> = {}) =>
    get<PageResult<AuditLogVO>>('/org/audit', { params }),
  /** 按同条件导出（一次性至多后端 EXPORT_MAX 条），前端据此生成 CSV。 */
  export: (params: Omit<AuditQuery, 'tenantId' | 'limit' | 'offset'> = {}) =>
    get<AuditLogVO[]>('/org/audit/export', { params }),
  /**
   * 审计智能查询（T11-3）：一句话抽槽位后三源融合检索本组织。
   * 需 `audit:log:read` + 后端门控 AUDIT/nl-query 启用；LLM 解析失败返 degraded=true（非 500），
   * 前端据此回落人工筛选。租户照旧由登录上下文钉死，请求体不带任何范围参数。
   */
  nlQuery: (question: string) => post<AuditNlResult>('/org/audit/nl-query', { question })
}
