/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
/* ============================ 用户手册 ============================ */

/** PublishedManualVO：用户阅读的已发布手册（仅发布快照，不含草稿）。 */
export interface PublishedManualVO {
  title?: string | null
  content?: string | null
  version?: number | null
  publishedAt?: string | null
}

/** ManualVO：超管编辑视图（草稿 + 已发布快照）。status = DRAFT / PUBLISHED。 */
export interface ManualVO {
  title: string
  content?: string | null
  status: 'DRAFT' | 'PUBLISHED' | string
  version?: number | null
  /** 是否已发布过（publishedContent 非空）。 */
  published?: boolean
  publishedTitle?: string | null
  publishedContent?: string | null
  updatedAt?: string | null
  publishedAt?: string | null
}

/** ManualSaveForm：保存草稿请求（仅写草稿列，不触碰发布版）。 */
export interface ManualSaveForm {
  title: string
  content?: string | null
}

/* ============================ 平台权限治理（§9.2 治理组，只读） ============================ */

/** PermissionVO：权限点字典行。level=PLATFORM/TENANT/TEAM/RESOURCE；type=MENU/BUTTON/API/DATA。 */
export interface PermissionVO {
  code: string
  name?: string | null
  module?: string | null
  level?: string | null
  type?: string | null
  menuCode?: string | null
  apiMethod?: string | null
  apiPattern?: string | null
  builtin?: number | null
  enabled?: number | null
  sort?: number | null
  description?: string | null
}

/** RoleVO：角色字典行（权限矩阵列头）。level=PLATFORM/TENANT/TEAM。 */
export interface RoleVO {
  code: string
  name?: string | null
  level?: string | null
  dataScope?: string | null
  inheritToChild?: number | null
  builtin?: number | null
  enabled?: number | null
  sort?: number | null
  description?: string | null
}

/** 角色→权限点矩阵：roleCode → 该角色持有的权限码列表。 */
export type RolePermissionMatrix = Record<string, string[]>

/** ApiMatrixVO.Row：单接口权限画像。annotated=false 且 failClosed=true 即高危漏配。 */
export interface ApiMatrixRow {
  httpMethod: string
  pattern: string
  controller?: string | null
  handler?: string | null
  permissionCodes?: string[] | null
  resourceType?: string | null
  annotated: boolean
  failClosed: boolean
}

/** ApiMatrixVO：接口权限矩阵（§6.4 扫描结果 + fail-closed 域未标注告警统计）。 */
export interface ApiMatrixVO {
  total: number
  annotated: number
  unannotatedFailClosed: number
  failClosedDomains: string[]
  endpoints: ApiMatrixRow[]
}

/* ============================ 平台角色授予/回收（§9.2 治理组，写侧） ============================ */

/** PlatformRoleHolderVO：平台角色持有者（user_role tenant_id=0/scope=PLATFORM 联 app_user）。 */
export interface PlatformRoleHolderVO {
  id: number
  uid: number
  username?: string | null
  nickname?: string | null
  email?: string | null
  roleCode: string
  grantedAt?: string | null
  expireAt?: string | null
}

/** PlatformRoleGrantForm：授予平台角色。uid 必须是平台账号（tenant_id=0），roleCode 为平台角色码。 */
export interface PlatformRoleGrantForm {
  uid: number
  roleCode: string
}

/* ============================ 审计日志（§9.2 治理组，只读跨租户检索） ============================ */

/**
 * AuditLogVO：审计日志行（后端 auth_audit_log + 批量联查出的用户名/租户名）。
 * tenantId=0 表示平台域事件（tenantName 为空，前端渲染「平台」）；detail 为脱敏 JSON 字符串。
 */
export interface AuditLogVO {
  id: number
  tenantId?: number | null
  tenantName?: string | null
  uid?: number | null
  username?: string | null
  identityId?: number | null
  actorType?: string | null
  action: string
  resourceType?: string | null
  resourceId?: string | null
  targetUid?: number | null
  targetUsername?: string | null
  result?: string | null
  detail?: string | null
  ip?: string | null
  userAgent?: string | null
  traceId?: string | null
  createdAt?: string | null
}

/**
 * AuditQuery：审计检索条件（全部过滤字段可空，空则不约束）。
 * from/to 为字符串，格式 yyyy-MM-dd HH:mm:ss（与后端 @DateTimeFormat 对齐）。
 */
export interface AuditQuery {
  tenantId?: number | null
  action?: string | null
  uid?: number | null
  result?: string | null
  from?: string | null
  to?: string | null
  limit?: number
  offset?: number
}

/* ============================ 审计智能查询（T11-3，NL → 槽位 → 三源融合） ============================ */

/**
 * AuditQuerySlots：自然语言抽取出的过滤槽位（后端已闭集清洗，可回显修改）。
 * 时间格式 yyyy-MM-dd HH:mm:ss；action 为后端 AuditAction 白名单码；sources 大写 AUDIT/OPS/QUERY。
 */
export interface AuditQuerySlots {
  timeFrom?: string | null
  timeTo?: string | null
  username?: string | null
  action?: string | null
  result?: string | null
  /** 仅回显供沿用人工筛选，不进后端精确过滤（ops 源 module 存的是 URI 派生英文码）。 */
  module?: string | null
  keyword?: string | null
  sources?: string[] | null
  /** 清洗/忽略说明（如「已忽略无法识别的动作…」「部门条件无法支持」）。 */
  note?: string | null
}

/** AuditNlRow：三源统一投影行（时间倒序，至多 200 条）。 */
export interface AuditNlRow {
  occurredAt?: string | null
  source: string
  tenantId?: number | null
  tenantName?: string | null
  username?: string | null
  summary?: string | null
  action?: string | null
  result?: string | null
  /** 仅 QUERY 源且服务端有结果快照时非空：行数×列数+首行示例（快照原文绝不下发）。 */
  resultSummary?: string | null
}

/** AuditNlResult：智能查询响应。degraded=true 表示 LLM 解析失败，slots/rows 为空，前端回落人工筛选。 */
export interface AuditNlResult {
  slots?: AuditQuerySlots | null
  degraded: boolean
  total: number
  rows?: AuditNlRow[] | null
}

/* ============================ 操作日志（audit_log 全量请求，§10.2 审计落地之二） ============================ */

/**
 * 全局操作日志只读投影（后端 OperationLogVO）。
 * 与「审计日志」(auth_audit_log，仅登录/权限事件) 不同：本结构覆盖<b>每一次 HTTP 操作</b>，
 * 含 module/uri/method/http_status/duration_ms/error_msg/request_body，用于回溯「谁在什么时刻对哪个接口做了什么」。
 * action 为冗余字段（= `${method} ${uri}`），页面优先展示 uri + method 标签。
 */
export interface OperationLogVO {
  id: number
  tenantId?: number | null
  tenantName?: string | null
  userId?: number | null
  username?: string | null
  orgRoles?: string | null
  platformRoles?: string | null
  requestId?: string | null
  /** 业务模块：org:metrics / chat / admin:audit / dashboard ...（deriveModule 由路径推导）。 */
  module?: string | null
  /** 冗余字段，= `${method} ${uri}`（如 `POST /api/metric/46/refresh`）。 */
  action?: string | null
  method?: string | null
  uri?: string | null
  queryString?: string | null
  /** 请求体（已脱敏口令/凭证，超长截断），详情抽屉展示。 */
  requestBody?: string | null
  ip?: string | null
  userAgent?: string | null
  httpStatus?: number | null
  errorMsg?: string | null
  durationMs?: number | null
  createdAt?: string | null
}

/**
 * 操作日志检索条件（全部可空，空则不约束）。from/to 格式 yyyy-MM-dd HH:mm:ss。
 * 管理端可传 tenantId 跨租户；组织端接口强制钉死当前租户、不接受该入参。
 */
export interface OperationQuery {
  tenantId?: number | null
  module?: string | null
  method?: string | null
  uri?: string | null
  username?: string | null
  from?: string | null
  to?: string | null
  limit?: number
  offset?: number
}

/* ============================ 待治理问题沉淀（T11-1，只读） ============================ */

/**
 * 治理信号类型（后端 GovernanceSignal 枚举，声明顺序即紧急度优先级，越小越紧急）。
 * 与后端保持同步：THUMBS_DOWN 用户点踩 / COST_BLOCKED 代价熔断 / FAILED_HIGH 高频失败 /
 * LOW_SCORE 低质量分 / AMBIGUOUS 反复歧义澄清；
 * T11-4 元数据巡检三值整体排在问数信号之后：META_LINEAGE_BROKEN 血缘断裂 /
 * META_SAME_NAME_DIFF_DEF 同名不同口径 / META_NO_COMMENT 注释缺失；
 * T12-3 结构变更感知：META_SCHEMA_CHANGED 重扫发现删表/删列且砸到既有模板/指标；
 * T12-4 成本巡检三值整体排最后（建议不及故障紧急）：COST_PATROL_MATERIALIZE 物化候选 /
 * COST_PATROL_SLOW_SQL 慢查询 / COST_PATROL_DUP_QUERY 重复问法。
 */
export type GovernanceSignal =
  | 'THUMBS_DOWN'
  | 'COST_BLOCKED'
  | 'FAILED_HIGH'
  | 'LOW_SCORE'
  | 'AMBIGUOUS'
  | 'META_LINEAGE_BROKEN'
  | 'META_SAME_NAME_DIFF_DEF'
  | 'META_NO_COMMENT'
  | 'META_SCHEMA_CHANGED'
  | 'COST_PATROL_MATERIALIZE'
  | 'COST_PATROL_SLOW_SQL'
  | 'COST_PATROL_DUP_QUERY'

/** GovernanceIssue：一条聚合后的待治理问题（同义问法归一为同一簇）。 */
export interface GovernanceIssue {
  signal?: GovernanceSignal | null
  /** 代表性原始问法（取簇内首条）。 */
  question?: string | null
  /** 归一化后的聚合键。 */
  normalizedKey?: string | null
  /** 簇内出现次数。 */
  occurrences?: number | null
  /** 命中技能（nl2sql / metric / fewshot / direct_sql 等），可空。 */
  skill?: string | null
  /** 一条代表性失败信息（低频信号可能为空）。 */
  errorMsgSample?: string | null
  /** 建议动作（依信号类型 + 技能给出的治理提示）。 */
  suggestedAction?: string | null
  /** 代表 SQL 草稿（二期转写用）：簇内优先取同键 OK 行的 sql，无则取失败行草稿；可空。 */
  sampleSql?: string | null
  /** 该簇是否命中可信成功 SQL（true=可直接转写；false=草稿需人工校对）。 */
  hasSuccessSql?: boolean | null
  /** 跳转锚点（数据源/技能/归一键/historyId 等，供前端路由到对应治理页）。 */
  jumpAnchor?: Record<string, unknown> | null
  /** 台账行 id（二期 B，后端 attachStatus 填充）。 */
  ledgerId?: number | null
  /** 处置态 OPEN/CONVERTED/DISMISSED（二期 B，默认 OPEN）。 */
  status?: 'OPEN' | 'CONVERTED' | 'DISMISSED' | string | null
  /** 转写产出类型 METRIC/FEWSHOT（二期 B）。 */
  refType?: string | null
  /** 转写产出资源 id（二期 B）。 */
  refId?: number | null
  /** 用户点踩时提交的期望正确 SQL（T11-2，簇内首个非空）：转写预填的高价值草稿，必经人工复核；无则 null。 */
  userSuggestedSql?: string | null
  /** 用户点踩时的口径说明/错因（T11-2，同簇首个非空）；无则 null。 */
  userNote?: string | null
}

/** DistillReport：一次问题沉淀的聚合结果（问题清单 + 计数统计 + 统计起点）。 */
export interface DistillReport {
  issues?: GovernanceIssue[] | null
  stats?: DistillStats | null
  /** 聚合时间窗起点（ISO 字符串）。 */
  since?: string | null
}

/** DistillStats：各类信号命中计数，供概览展示。 */
export interface DistillStats {
  scanned?: number | null
  failedClusters?: number | null
  lowScoreClusters?: number | null
  thumbsDown?: number | null
  ambiguous?: number | null
  costBlocked?: number | null
  /** 已处置簇数（CONVERTED+DISMISSED，二期 B）。 */
  handled?: number | null
}

/**
 * MetaPatrolResult：元数据巡检一次结果（T11-4，POST /chat/tuning/meta-patrol）。
 * issues 为本次命中投影（cap 100，按信号优先级排序）；命中已全量进台账，刷新 /issues 即可看到。
 */
export interface MetaPatrolResult {
  scannedDatasources?: number | null
  /** schema 拉取失败被跳过的数据源数（静默降级不中断）。 */
  failedDatasources?: number | null
  /** 截断/降级说明（如启用数据源超 5 个只扫前 5），可空。 */
  note?: string | null
  broken?: number | null
  diffDef?: number | null
  noComment?: number | null
  issues?: GovernanceIssue[] | null
}

/**
 * CostPatrolResult：成本巡检一次结果（T12-4，POST /chat/tuning/cost-patrol）。
 * issues 为本次命中投影（含建议文案，按优先级截断 topN）；命中已全量进台账，刷新 /issues 即可看到。
 */
export interface CostPatrolResult {
  /** 窗口内取回的历史行数（聚簇前）。 */
  scannedRows?: number | null
  /** 本次生效的时间窗（天）。 */
  windowDays?: number | null
  /** 窗口起点（ISO 字符串）。 */
  since?: string | null
  /** 物化候选命中簇数。 */
  materialize?: number | null
  /** 慢查询命中簇数。 */
  slow?: number | null
  /** 重复问法命中簇数。 */
  dup?: number | null
  /** 截断/降级说明，可空。 */
  note?: string | null
  issues?: GovernanceIssue[] | null
}

