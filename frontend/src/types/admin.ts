/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
import type { PlanType, UserRole } from './common'
/* ============================ 超管后台（Feature 1） ============================ */

/** 租户视图对象（超管后台）。dailyLimit/totalLimit 为 -1 表示不限；status 1=正常 0=停用。 */
export interface TenantVO {
  id: number
  name: string
  plan: PlanType | string
  isolationMode?: string | null
  status?: number | null
  expireAt?: string | null
  dailyLimit?: number | null
  totalLimit?: number | null
  totalUsed?: number | null
  seatLimit?: number | null
  seatUsed?: number | null
  userCount?: number
  createdAt?: string | null
}

/** 租户创建 / 更新表单。isolationMode/dailyLimit 留空按套餐兜底；totalLimit 留空默认 -1；expireAt 留空不过期。 */
export interface TenantForm {
  name: string
  plan: PlanType | string
  isolationMode?: string | null
  status?: number | null
  expireAt?: string | null
  dailyLimit?: number | null
  totalLimit?: number | null
}

/** 席位与配额调整表单（§9.2 运营组）。seatLimit 必填；dailyLimit/totalLimit 留空(null)表示不变；各值 -1=不限。 */
export interface SeatQuotaForm {
  seatLimit: number
  dailyLimit?: number | null
  totalLimit?: number | null
}

/** 账号视图对象（超管后台，绝不含密码哈希）。status 1=正常 0=禁用。 */
export interface AdminUserVO {
  id: number
  tenantId?: number | null
  username: string
  nickname?: string | null
  email?: string | null
  role?: UserRole | string | null
  status?: number | null
  createdAt?: string | null
}

/** 账号创建 / 更新表单。创建时 password/tenantId 必填；更新时 password 留空不改，username/tenantId 不可变更。 */
export interface AdminUserForm {
  tenantId?: number | null
  username: string
  password?: string | null
  nickname?: string | null
  email?: string | null
  role?: UserRole | string | null
  status?: number | null
}

/** 重置密码表单（超管后台）。 */
export interface ResetPasswordForm {
  password: string
}

/** 大模型配置视图对象（超管后台）。tenantId 空=全局默认，非空=租户覆盖；hasApiKey 不回明文。 */
export interface LlmConfigVO {
  id: number
  tenantId?: number | null
  provider: string
  name: string
  baseUrl?: string | null
  model?: string | null
  timeoutSeconds?: number | null
  priority?: number | null
  enabled?: number | null
  hasApiKey: boolean
  createdAt?: string | null
}

/** 大模型配置创建 / 更新表单。provider=DEEPSEEK/QWEN/OPENAI_COMPATIBLE；apiKey 更新留空保持原 Key；priority 越小越优先。 */
export interface LlmConfigForm {
  tenantId?: number | null
  provider: string
  name: string
  baseUrl: string
  apiKey?: string | null
  model: string
  timeoutSeconds?: number | null
  priority?: number | null
  enabled?: number | null
}

/** 平台概览统计（超管后台首页，跨租户聚合）。 */
export interface AdminOverviewVO {
  tenantCount: number
  userCount: number
  datasourceCount: number
  llmConfigCount: number
  dashboardCount: number
  todayQueryCount: number
  totalQueryCount: number
  failedQueryCount: number
}

/** 平台概览 · 单日问数趋势点（date=yyyy-MM-dd，后端已补零对齐 14 天）。 */
export interface AdminQueryTrendPoint {
  date: string
  total: number
  failed: number
}

/** 平台概览 · 单日新增趋势点（新增账号 / 新增租户）。 */
export interface AdminSignupTrendPoint {
  date: string
  users: number
  tenants: number
}

/** 平台概览 · 通用「名称-数值」项（套餐分布、活跃租户 TOP）。 */
export interface AdminNameValue {
  name: string
  value: number
}

/** 平台概览趋势数据（图表区，跨租户聚合）。 */
export interface AdminOverviewTrendVO {
  queryTrend: AdminQueryTrendPoint[]
  signupTrend: AdminSignupTrendPoint[]
  planDistribution: AdminNameValue[]
  topTenants: AdminNameValue[]
}

