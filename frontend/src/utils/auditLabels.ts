/**
 * 审计日志展示字典（动作 / 结果 / 身份类型 / 资源类型 / IP 归一）。
 *
 * <p><b>单一来源</b>：与后端 {@code com.aibi.module.audit.AuditAction} 常量、各写侧 {@code resourceType}
 * 实参对齐。此前两处视图（组织 / 平台后台）各自维护一份字典，极易与后端漂移——
 * 一旦后端新增动作码而前端漏配，{@code actionLabel} 会回退成裸码、再叠加表格里的「机器码副标题」，
 * 渲染出 {@code INVITE_CREATEINVITE_CREATE} 这类难看的重复串。集中到本模块后，两视图共用，杜绝漂移。</p>
 *
 * <p>未知码一律<b>原样展示</b>（不抛错、不占位），便于后端增量埋点后前端逐步补齐，不会出现空白。</p>
 */

/* ---------------- 动作码（与后端 AuditAction 对齐） ---------------- */
export const ACTION_OPTIONS: { value: string; label: string }[] = [
  // 认证（§10.1）
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '登出' },
  { value: 'LOGIN_FAILED', label: '登录失败' },
  // 鉴权（§10.1，每次 403 拒绝）
  { value: 'PERM_CHECK_DENIED', label: '权限拒绝' },
  { value: 'DATA_SCOPE_DENIED', label: '数据范围拒绝' },
  { value: 'ACL_DENIED', label: '资源 ACL 拒绝' },
  // 授权 / 组织 / 平台（已埋点写侧）
  { value: 'PLATFORM_ROLE_GRANT', label: '平台角色授予' },
  { value: 'PLATFORM_ROLE_REVOKE', label: '平台角色回收' },
  { value: 'SEAT_CHANGE', label: '席位 / 配额变更' },
  { value: 'TENANT_DISABLE', label: '租户停用' },
  { value: 'AUDIT_EXPORT', label: '审计导出' },
  // 成员与组织（MemberService 写侧）
  { value: 'MEMBER_REMOVE', label: '移除成员' },
  { value: 'ROLE_GRANT', label: '组织角色授予' },
  { value: 'ROLE_REVOKE', label: '组织角色回收' },
  { value: 'PASSWORD_RESET', label: '重置成员口令' },
  { value: 'OWNER_TRANSFER', label: '转让组织所有权' },
  // 邀请（InvitationService 写侧）
  { value: 'INVITE_CREATE', label: '发起邀请' },
  { value: 'INVITE_REVOKE', label: '撤销邀请' },
  { value: 'INVITE_ACCEPT', label: '接受邀请' },
  // 智能预警（AlertRuleService / AlertEngineService 写侧）
  { value: 'ALERT_RULE_CREATE', label: '预警规则创建' },
  { value: 'ALERT_RULE_UPDATE', label: '预警规则更新' },
  { value: 'ALERT_RULE_DELETE', label: '预警规则删除' },
  { value: 'ALERT_FIRED', label: '预警触发' },
  // 身份（IdentityService 写侧）
  { value: 'ORG_SWITCH', label: '组织切换' }
]

const ACTION_LABELS: Record<string, string> = Object.fromEntries(
  ACTION_OPTIONS.map((o) => [o.value, o.label])
)

/** 动作码 → 中文标签；未知码原样返回。 */
export function actionLabel(code?: string | null): string {
  if (!code) return '—'
  return ACTION_LABELS[code] || code
}

/* ---------------- 结果码 ---------------- */
export const RESULT_OPTIONS: { value: string; label: string }[] = [
  { value: 'OK', label: '成功' },
  { value: 'DENIED', label: '被拒绝' },
  { value: 'FAILED', label: '失败' }
]

/** 结果码 → 中文标签；未知码原样返回。 */
export function resultLabel(result?: string | null): string {
  if (!result) return '—'
  return RESULT_OPTIONS.find((o) => o.value === result)?.label || result
}

/** 结果码 → Element Plus Tag 色调（成功绿 / 拒绝黄 / 失败红 / 其他灰）。 */
export function resultTagType(
  result?: string | null
): 'success' | 'danger' | 'warning' | 'info' {
  switch (result) {
    case 'OK':
      return 'success'
    case 'DENIED':
      return 'warning'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

/* ---------------- 身份类型（actor_type） ---------------- */
/** 操作人身份类型 → 中文标签。 */
export function actorTypeLabel(t?: string | null): string {
  switch (t) {
    case 'USER':
      return '组织用户'
    case 'OPERATOR':
      return '平台运营'
    case 'SUPPORT':
      return '协助支持'
    case 'SYSTEM':
      return '系统'
    default:
      return t || '—'
  }
}

/* ---------------- 资源类型（resource_type） ---------------- */
const RESOURCE_TYPE_LABELS: Record<string, string> = {
  INVITATION: '邀请',
  APP_USER: '成员',
  USER_ROLE: '组织角色',
  TENANT: '租户',
  ALERT_RULE: '预警规则',
  AUTH: '认证',
  ORG: '组织身份',
  PERMISSION: '权限点',
  AUDIT: '审计日志',
  OPERATION_LOG: '操作日志'
}

/** 资源类型 → 中文标签；未知类型原样返回。 */
export function resourceTypeLabel(type?: string | null): string {
  if (!type) return '—'
  return RESOURCE_TYPE_LABELS[type] || type
}

/* ---------------- IP 归一 ---------------- */
/**
 * 审计 IP 展示归一：回环地址（IPv6 {@code ::1}/{@code 0:0:0:0:0:0:0:1}、IPv4 {@code 127.0.0.1}）
 * 统一显示为「本机」，比裸回环串美观；外部地址原样返回。空值返回「—」。
 *
 * <p>注意：后端已在落库时把回环地址归一为 {@code 127.0.0.1}，此处仅作展示层兜底，
 * 兼容历史脏数据（直接存了 {@code 0:0:0:0:0:0:0:1} 的旧记录）。</p>
 */
export function ipLabel(ip?: string | null): string {
  if (!ip) return '—'
  const t = ip.trim()
  if (t === '127.0.0.1' || t === '::1' || t === '0:0:0:0:0:0:0:1') return '本机'
  return ip
}

/* ---------------- 操作日志模块（deriveModule 由请求路径推导） ---------------- */
/**
 * 操作日志业务模块 → 中文标签（单一来源，与后端 {@code OperationLogInterceptor#deriveModule} 口径一致）。
 * 未知模块原样展示。覆盖度随后端埋点增量补齐，未列出的新模块不会出现空白。
 */
export const MODULE_LABELS: Record<string, string> = {
  metric: '指标',
  dashboard: '驾驶舱',
  chart: '图表',
  'chart-rule': '图表规则',
  semantic: '语义层',
  datasource: '数据源',
  config: '配置',
  theme: '主题',
  quota: '配额',
  history: '问数历史',
  favorite: '收藏',
  fewshot: '样例',
  chat: '智能问数',
  auth: '认证授权',
  org: '组织',
  'org:invitations': '组织·邀请',
  'org:members': '组织·成员',
  'org:roles': '组织·角色',
  admin: '平台后台',
  'admin:audit': '平台·审计',
  alert: '智能预警',
  identity: '身份',
  user: '用户',
  file: '文件',
  other: '其他'
}

/** 模块码 → 中文标签；未知码原样返回。 */
export function moduleLabel(module?: string | null): string {
  if (!module) return '其他'
  return MODULE_LABELS[module] || module
}
