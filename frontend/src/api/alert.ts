import { del, get, post, put } from './request'
import type {
  AlertAttributionVO,
  AlertDetectResult,
  AlertRecordVO,
  AlertRuleForm,
  AlertRuleVO,
  PageResult
} from '@/types'

/**
 * 组织预警接口（/api/org/alerts）。仅组织管理员（OWNER/ADMIN）可访问——
 * 后端方法级权限点 alert:rule:* 、alert:record:read、alert:detect:execute（组织矩阵 YYNNNN），另加 assertManager 双重门禁。
 * 租户范围由后端强制收敛（alert_rule 为租户隔离表），前端不传 tenantId。
 */
export const alertApi = {
  /** 本组织预警规则列表。 */
  listRules: () => get<AlertRuleVO[]>('/org/alerts/rules'),
  /** 新建规则。 */
  createRule: (data: AlertRuleForm) => post<AlertRuleVO>('/org/alerts/rules', data),
  /** 编辑规则（按需传字段，空字段跳过）。 */
  updateRule: (id: number, data: AlertRuleForm) => put<AlertRuleVO>(`/org/alerts/rules/${id}`, data),
  /** 删除规则。 */
  removeRule: (id: number) => del<void>(`/org/alerts/rules/${id}`),
  /** 启用规则。 */
  enableRule: (id: number) => post<AlertRuleVO>(`/org/alerts/rules/${id}/enable`),
  /** 停用规则。 */
  disableRule: (id: number) => post<AlertRuleVO>(`/org/alerts/rules/${id}/disable`),
  /**
   * 归因试跑（P1）：用表单中的指标键与维度立即预跑一次归因，不落库、不触发告警。
   * 用于在开启「触发时自动归因」前确认维度可用、指标已沉淀口径、置信度是否足够。
   * 权限 alert:detect:execute（OWNER/ADMIN）。
   */
  previewAttribution: (data: Pick<AlertRuleForm, 'metricKey' | 'attributionDimension'>) =>
    post<AlertAttributionVO>('/org/alerts/rules/preview-attribution', data),
  /** 手动对本组织跑一次检测，返回触发条数。 */
  detect: () => post<AlertDetectResult>('/org/alerts/detect'),
  /** 触发记录分页（按时间倒序，可据 ruleId 过滤）。 */
  records: (params: { ruleId?: number; limit?: number; offset?: number }) =>
    get<PageResult<AlertRecordVO>>('/org/alerts/records', { params })
}
