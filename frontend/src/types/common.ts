/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
/* ============================ 统一响应壳 ============================ */

/** 后端统一响应 R<T>。code=0 成功，非 0 为业务错误码。 */
export interface R<T> {
  code: number
  message: string
  data: T
  traceId?: string | null
}

/** 通用分页结果 PageResult<T>。 */
export interface PageResult<T> {
  items: T[]
  total: number
  limit: number
  offset: number
}

/** 业务错误码（见后端 ErrorCode）。 */
export const ErrCode = {
  SUCCESS: 0,
  UNAUTHORIZED: 40001,
  PARAM_ERROR: 40002,
  UNSUPPORTED_FILE_TYPE: 40003,
  FILE_TOO_LARGE: 40004,
  FORBIDDEN: 40301,
  TENANT_MISSING: 40303,
  TENANT_EXPIRED: 40304,
  TENANT_DISABLED: 40305,
  TEAM_MISSING: 40306,
  SESSION_REVOKED: 40311,
  DATASOURCE_NOT_FOUND: 40401,
  FREE_LIMIT_EXCEEDED: 42901,
  TOO_MANY_REQUESTS: 42902,
  TOTAL_LIMIT_EXCEEDED: 42903,
  SYSTEM_ERROR: 50000,
  DATASOURCE_CONNECT_FAILED: 50001,
  SQL_GENERATE_FAILED: 50002,
  QUERY_TIMEOUT: 50003,
  LLM_CONFIG_INVALID: 50004,
  FILE_UPLOAD_FAILED: 50005,
  /** T10-1 执行前代价熔断：后端已拦截高代价查询，message 携带可执行的拆分建议。 */
  QUERY_COST_TOO_HIGH: 50006
} as const

/** 业务异常（axios 拦截器 reject 时抛出）。 */
export interface BizError {
  code: number
  message: string
  traceId?: string | null
}

/* ============================ 认证 / 用户 ============================ */

export type PlanType = 'FREE' | 'PRO' | 'ENTERPRISE'

/** 平台角色（后端 app_user.role）：SUPER_ADMIN=超级管理员（tenant_id=0），USER=普通租户用户。 */
export type UserRole = 'SUPER_ADMIN' | 'USER'

/** UserVO：对外安全的用户资料（不含密码哈希）。 */
export interface UserVO {
  id: number
  tenantId: number
  username: string
  nickname?: string | null
  email?: string | null
  plan: PlanType | string
  role?: UserRole | string | null
  /** 账号级界面主题偏好：light/dark/system（空=跟随系统）。 */
  uiTheme?: 'light' | 'dark' | 'system' | null
}

/** AuthResponse：令牌 + 用户资料。 */
export interface AuthResponse {
  token: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

export interface LoginRequest {
  username: string
  password: string
  /** 目标组织（多组织身份登录时指定；为空则按默认/首个组织，向后兼容单组织登录）。 */
  tenantId?: number | null
}

export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
  email?: string
  /** 邀请令牌：携带时走「注册即加入组织」链路，否则自助开通新工作区。 */
  inviteToken?: string
}

/** MyTenantVO：当前自然人可切换的组织视图（组织切换器数据源）。 */
export interface MyTenantVO {
  tenantId: number
  tenantName?: string | null
  /** 在该组织的组织角色码（逗号分隔，如 OWNER/ADMIN/ANALYST）。 */
  orgRoles?: string | null
  /** 是否为当前生效组织。 */
  current?: boolean | null
}

export interface UpdateProfileRequest {
  nickname?: string
  email?: string
  /** 界面主题偏好（light/dark/system）；不传表示不修改。 */
  uiTheme?: 'light' | 'dark' | 'system'
}

/* ============================ 权限下发（P4，见《角色权限体系与组织协同设计》7.1） ============================ */

/**
 * 有效数据范围名（后端 {@code DataScope} 枚举）。
 * ALL=全平台 / TENANT=本组织 / TEAM_AND_CHILD=本团队及子团队 / TEAM=本团队 / SELF=仅本人 / CUSTOM=自定义。
 */
export type DataScope = 'ALL' | 'TENANT' | 'TEAM_AND_CHILD' | 'TEAM' | 'SELF' | 'CUSTOM'

/**
 * {@code GET /api/auth/permissions} 的响应体，与后端 {@code com.aibi.module.rbac.dto.PermissionsView} 逐字段对齐。
 *
 * 前端权限的单一事实源：路由守卫、动态菜单、按钮显隐都以 {@code permissions} 为准，
 * {@code menus} 仅为便利字段（后端按 {@code permission.type='MENU'} 反查 menu_code 得出）。
 * 后端 Jackson non_null：可空字段可能从 JSON 省略，故一律标注可选。
 */
export interface PermissionsView {
  /** 当前上下文有效权限码集合（已按 ASSIST 剥离、按角色并集展开）。 */
  permissions: string[]
  /** MENU 型权限点对应的前端路由 name 集合。 */
  menus: string[]
  dataScope: DataScope | string
  platformRoles: string[]
  orgRoles: string[]
  teamRoles: string[]
  /** 当前团队 id，可空（尚未选定团队）。 */
  currentTeamId?: number | null
  /** 所属团队 id 集合（请求期零查询路径下可能为空，团队列表由 P2 团队接口补全）。 */
  teamIds?: number[] | null
  /** 是否平台域身份（tenantId==0）。 */
  platform: boolean
  /** 是否组织所有者。 */
  owner: boolean
  /** 是否代客协助影子令牌（前端据此展示红色横幅并隐藏全部写按钮）。 */
  assist: boolean
  /** 席位上限（-1=不限）；平台身份为 null。 */
  seatLimit?: number | null
  seatUsed?: number | null
  seatRemaining?: number | null
}

