/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
/* ============================ 组织成员（§9.2 组织设置 · 成员，member:*） ============================ */

/**
 * MemberVO：组织在册成员（app_user.status=1 联 user_role scope=TENANT 组织角色）。
 * orgRoles 按权限宽窄降序；owner=是否组织所有者；self=是否当前登录者本人（前端据此禁用自操作）。
 */
export interface MemberVO {
  uid: number
  username?: string | null
  nickname?: string | null
  email?: string | null
  position?: string | null
  status?: number | null
  /** 仅含内置角色授予（不含自定义角色授予行的 base 码）。 */
  orgRoles: string[]
  owner: boolean
  self: boolean
  createdAt?: string | null
  lastLoginAt?: string | null
  /** 该成员持有的自定义角色授予（可空=无）。 */
  customRoles?: MemberCustomRole[] | null
}

/** MemberCustomRole：成员持有的自定义角色授予摘要（自定义角色引擎 §2）。 */
export interface MemberCustomRole {
  id: number
  name: string
  baseRoleCode: string
}

/** MemberRoleForm：授予/回收组织角色。OWNER 不可直接授予/回收（须走转让）。 */
export interface MemberRoleForm {
  uid: number
  roleCode: string
  /** 非空表示授予/回收的是「租户自定义角色」（roleCode 写其 base 码）。 */
  customRoleId?: number | null
}

/** MemberUpdateForm：更新成员组织内展示资料；字段为 null 表示不改。 */
export interface MemberUpdateForm {
  nickname?: string | null
  position?: string | null
}

/** MemberPasswordForm：重置成员口令（6-64 位）。 */
export interface MemberPasswordForm {
  newPassword: string
}

/* ==================== 组织治理专项（P1 自定义角色 / P2 数据权限 A） ==================== */

/** CustomRoleVO：租户自定义角色视图（《组织治理专项设计》§2，P1）。 */
export interface CustomRoleVO {
  id: number
  name: string
  baseRoleCode: string
  dataScope?: string | null
  description?: string | null
  /** 启用态；后端可能以 boolean 或 0/1 返回，前端按真值判断。 */
  enabled?: boolean | number | null
  /** 权限覆盖行数（GRANT/DENY 合计）。 */
  overrideCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** CustomRoleForm：新建/更新自定义角色（创建时 name/baseRoleCode 必填；更新时字段可空=不改，base 不可改）。 */
export interface CustomRoleForm {
  name?: string | null
  baseRoleCode?: string | null
  description?: string | null
  /** 1=启用 0=停用；null=不改（更新）。 */
  enabled?: number | null
  dataScope?: string | null
}

/** CustomRolePermissionForm：整体替换权限覆盖（grant 仅限 base 内，否则后端拒 ROLE_GRANT_ESCALATION）。 */
export interface CustomRolePermissionForm {
  grant: string[]
  deny: string[]
}

/** CustomRoleEffectiveVO：自定义角色有效权限预览（有效 = base ∪ granted − denied）。 */
export interface CustomRoleEffectiveVO {
  id: number
  baseRoleCode: string
  basePermissions: string[]
  granted: string[]
  denied: string[]
  effective: string[]
}

/** 数据授权主体类型：USER=成员直授，ROLE=自定义角色继承。 */
export type DataAclTargetType = 'USER' | 'ROLE'

/** 数据授权种类：DATASOURCE=数据源可见性，FIELD=字段脱敏。 */
export type DataAclKind = 'DATASOURCE' | 'FIELD' | 'TABLE'

/** DataAclItemVO：一条数据授权（成员/角色的 数据源可见性或字段脱敏）。 */
export interface DataAclItemVO {
  targetType: DataAclTargetType | string
  targetId: number
  datasourceId: number
  /** 字段名；数据源可见性项为 null/空。 */
  columnName?: string | null
  /** 表名；表级授权项为表名，其余维度为 null/空。 */
  tableName?: string | null
  /** 效果：HIDDEN/ALLOW（数据源或表）或 MASK/UNMASK（字段）。表维度与数据源同用 HIDDEN/ALLOW。 */
  effect: string
  kind: DataAclKind | string
}

/** DataAclForm：设置一条数据授权（后端按typename判定维度）。 */
export interface DataAclForm {
  targetType: DataAclTargetType | string
  targetId: number
  datasourceId: number
  /** 字段名；字段脱敏维度必传（可带 tableName 表.列级）。二者皆空 = 数据源可见性维度。 */
  columnName?: string | null
  /** 表名；字段脱敏可带（表.列级，此时 columnName 优先判定为字段维度）；表级可见性维度单独传此。 */
  tableName?: string | null
  effect: string
}

/** DataAclClearForm：清除一条数据授权（恢复默认：可见 / 按语义层默认掩码）。 */
export interface DataAclClearForm {
  targetType: DataAclTargetType | string
  targetId: number
  datasourceId: number
  /** 字段名；字段脱敏维度必传（可带 tableName 表.列级）。 */
  columnName?: string | null
  /** 表名；字段脱敏可带（表.列级）；表级可见性维度传表名。 */
  tableName?: string | null
}

/** OwnerTransferForm：转让组织所有权（仅 OWNER）；targetUid 须为本组织在册成员。 */
export interface OwnerTransferForm {
  targetUid: number
}

/* ============================ 组织邀请（§9.2 组织设置 · 邀请，member:invite:*） ============================ */

/**
 * InvitationVO：邀请行（绝不含 tokenHash）。
 * status：DRAFT/SENT/PENDING/APPROVED/REJECTED/ACCEPTED/EXPIRED/REVOKED；seats=-1 表示不限。
 */
export interface InvitationVO {
  id: number
  channel?: string | null
  roleCode: string
  teamId?: number | null
  targetEmail?: string | null
  allowDomain?: string | null
  seats?: number | null
  usedCount?: number | null
  requireApproval?: number | null
  needLogin?: number | null
  status?: string | null
  expireAt?: string | null
  invitedBy?: number | null
  message?: string | null
  createdAt?: string | null
}

/** InvitationCreateForm：发起邀请；空字段由后端取默认（channel→LINK、seats→1、expireDays→TTL）。 */
export interface InvitationCreateForm {
  channel?: string | null
  roleCode: string
  teamId?: number | null
  targetEmail?: string | null
  allowDomain?: string | null
  seats?: number | null
  expireDays?: number | null
  requireApproval?: number | null
  message?: string | null
}

/** InvitationCreateResult：创建结果。token 明文仅返回一次，inviteUrl 可直接复制分发。 */
export interface InvitationCreateResult {
  invitation: InvitationVO
  token: string
  inviteUrl: string
}

/** 邀请预览可用性状态（匿名落地页 /org/join 消费，与后端 InvitationPreviewVO.STATUS_* 对齐）。 */
export type InvitationPreviewStatus = 'OK' | 'EXPIRED' | 'REVOKED' | 'EXHAUSTED' | 'INVALID'

/**
 * InvitationPreview：凭明文令牌匿名预览的邀请概要（GET /api/public/invitation/preview）。
 * 令牌无效时仅 status=INVALID，其余字段为空——不泄露组织是否存在（防枚举探测），故绝不含 tokenHash/邀请 id。
 */
export interface InvitationPreview {
  status: InvitationPreviewStatus
  tenantName?: string | null
  roleCode?: string | null
  channel?: string | null
  message?: string | null
  requireApproval?: number | null
  needLogin?: number | null
  expireAt?: string | null
}

/**
 * InvitationAcceptResult：邀请接受结果（POST /api/org/invitations/accept）。
 * 字段对齐后端 com.aibi.module.org.dto.InvitationAcceptResult；绝不回传令牌/敏感信息。
 */
export interface InvitationAcceptResult {
  /** 所加入的组织 id（即邀请所属租户）。 */
  tenantId: number
  /** 组织名称（用于「你已加入 XXX」）。 */
  tenantName?: string | null
  /** 接受后授予的组织角色码。 */
  roleCode: string
  /** 最终状态：ACCEPTED（直接生效）或 PENDING（待管理员审批）。 */
  status: string
  /** 面向用户的提示文案。 */
  message?: string | null
}

// ==================== 归因分析（P0.1 维度归因 + 量价因子） ====================

/** 可选归因维度（语义层标记为 DIMENSION 且非敏感列；敏感维度禁止归因）。 */
export interface AttributionDimension {
  /** 维度物理列名（作为请求参数回传）。 */
  column: string
  /** 维度展示名（语义层业务别名，无别名时为列名）。 */
  label: string
  /** 维度所属表。 */
  table: string
}

/** 归因可选项：维度候选 + 指标沉淀的归因默认值（P1 指标保存增强）。 */
export interface AttributionOptionVO {
  /** 可用维度（已由后端剔除敏感列）。 */
  dimensions: AttributionDimension[]
  /** 指标沉淀的默认归因维度；未沉淀时为 null。 */
  defaultDimension?: string | null
  /** 指标沉淀的默认对比基准 MOM/YOY/PEAK；未沉淀时为 null，前端回落 MOM。 */
  defaultBaseline?: string | null
  /** 指标是否已沉淀 canonical 口径；false 时应提示先去调优中心补口径。 */
  hasDefinition: boolean
}

/**
 * 归因请求（POST /api/attribution/run）。
 * 以指标为中心而非以 SQL 为中心：口径统一取自指标 canonical 表达式，杜绝趋势/因子/归因三处口径漂移。
 */
export interface AttributionReq {
  /** 指标 id（口径的唯一来源）。 */
  metricId: number
  /** 归因维度列名（单维）。 */
  dimension: string
  /** 对比基准：MOM=环比上期 / YOY=同比去年同期 / PEAK=对比历史高点。 */
  baseline?: string | null
  /** 本期期间（month 粒度 `2026-08`；day 粒度 `2026-08-15`），留空取数据最新一期。 */
  period?: string | null
  /** 时间粒度：month / day。 */
  granularity?: string | null
  /** 量字段表达式（量价拆解用，如 `qty`）；留空时由语义层推断。 */
  volumeExpr?: string | null
  /**
   * 发起归因的问数历史 id（可空）。非空时后端把归因结果回写到该条 query_history 的 attribution_json，
   * 历史回放即可还原归因面板（与 insight / verify 同口径：只存不重算）。
   * 指标库等无历史上下文的场景留空。
   */
  historyId?: number | null
  /** 明细返回条数（按贡献绝对值降序，上限 50）。 */
  topN?: number | null
  /** 是否已完成单轮澄清（§3.2 最多 1 轮）：为 true 时后端不再返回 CLARIFY 门控。 */
  clarified?: boolean | null
}

/** 单维度成员贡献明细：贡献 = 本期 − 基期，占比以总变化为分母（总变化为 0 时为 null）。 */
export interface ContributionItem {
  member: string
  label: string
  cur: number
  base: number
  delta: number
  /** 贡献占总变化的比例（0~1）；总变化为 0 时为 null，此时不应展示占比。 */
  share: number | null
  curShare: number | null
  baseShare: number | null
}

/** 量价因子瀑布段：BASE=基期水位 / VOLUME=量 / PRICE=价 / CROSS=交叉 / TOTAL=本期水位。 */
export interface FactorItem {
  name: string
  value: number
  kind: string
  desc: string
}

/** 趋势序列单个数据点（按期聚合的度量值），用于趋势折线图。 */
export interface AttributionTrendPoint {
  /** 时间列原值（如 2026-09 或 2026-09-19）。 */
  period: string
  /** 该期度量合计。 */
  value: number
}

/** 归因结果（一次「为什么波动」的完整答案：三份产物 + 三套可独立审计的 SQL）。 */
export interface AttributionVO {
  metricId?: number | null
  metricName?: string | null
  dimension?: string | null
  dimensionLabel?: string | null
  /** 实际生效的对比基准。 */
  baseline?: string | null
  currentPeriod?: string | null
  basePeriod?: string | null
  curTotal?: number | null
  baseTotal?: number | null
  totalDelta?: number | null
  /** 维度成员贡献明细（按贡献绝对值降序）。 */
  items?: ContributionItem[] | null
  /** 量价因子瀑布段；量字段缺失时为空。 */
  factors?: FactorItem[] | null
  /** 趋势序列（按期聚合的度量值，ASC 排序），用于趋势折线图。 */
  trend?: AttributionTrendPoint[] | null
  /** 度量是否可加（false 时归因结论不可信，前端应显著提示）。 */
  additive?: boolean
  /** 趋势 SQL / 量价因子 SQL / 维度归因 SQL：三套分离，可分别查看与复制。 */
  trendSql?: string | null
  factorSql?: string | null
  attributionSql?: string | null
  /** 质量校验命中项（缺基准 / 维度稀疏 / 非可加 等）。 */
  issues?: string[] | null
  /** 归因解读文案（由结构化结果确定性生成，不参与计算）。 */
  insight?: string | null
  costMs?: number | null
  /** 归因场景置信度（0~1），由基准明确性、维度基数、稀疏度等可观测信号扣分得出。 */
  confidence?: number | null
  /** 门控：DIRECT=直出 / CLARIFY=需单轮澄清 / BLOCK=禁止自动归因（§3.2）。 */
  gate?: string | null
  /** 当前身份是否允许查看原始 SQL（§4）；false 时后端不下发 SQL 字段（非前端隐藏）。 */
  sqlVisible?: boolean | null
}

/** 字段级血缘项（血缘卡内最小单元）。 */
export interface LineageFieldItem {
  fieldId?: number | null
  column?: string | null
  label?: string | null
  role?: string | null
  sensitive?: number | null
}

/** 来源表 + 该表被引用的字段。 */
export interface LineageSourceItem {
  table?: string | null
  fields?: LineageFieldItem[] | null
}

/** 引用方（看板/指标）摘要。 */
export interface LineageRefItem {
  id?: number | null
  name?: string | null
  type?: string | null
  isScreen?: number | null
}

/** 指标血缘卡（§3.3）：来源表/字段 + 口径表达式 + 被引用看板。 */
export interface LineageVO {
  metricId?: number | null
  metricName?: string | null
  definition?: string | null
  sources?: LineageSourceItem[] | null
  referencedBy?: LineageRefItem[] | null
  dashboardCount?: number | null
}

/** 字段被引用情况（语义管理「被引用指标」列数据源）。 */
export interface FieldReferenceVO {
  fieldId?: number | null
  table?: string | null
  column?: string | null
  label?: string | null
  metrics?: LineageRefItem[] | null
  count?: number | null
}

/** 看板血缘卡（P1）：看板 → 引用的指标 → 指标追溯到的来源表/字段（自上而下）。 */
export interface DashboardLineageVO {
  dashboardId?: number | null
  dashboardName?: string | null
  /** 是否大屏（1=大屏）。 */
  isScreen?: number | null
  /** 该看板引用的指标。 */
  metrics?: LineageRefItem[] | null
  /** 这些指标追溯到的来源表与字段（按表聚合，跨指标去重）。 */
  sources?: LineageSourceItem[] | null
  /** 引用指标数。 */
  metricCount?: number | null
}

/** 语义字段变更影响面（P1「血缘变更影响提示」）：改别名/口径/列角色前评估。 */
export interface FieldImpactVO {
  fieldId?: number | null
  tableName?: string | null
  columnName?: string | null
  fieldLabel?: string | null
  /** 受影响的指标（直接或间接引用了该字段的口径）。 */
  metrics?: LineageRefItem[] | null
  /** 受影响的看板（引用了上述指标）。 */
  dashboards?: LineageRefItem[] | null
  /** 影响条目总数（指标 + 看板），为 0 表示可安全保存。 */
  total?: number | null
}

/** 数据源级血缘总览（问数调优「数据血缘」tab）：字段 → 引用指标 → 引用看板 三层。 */
export interface DatasourceLineageVO {
  datasourceId?: number | null
  /** 参与血缘的 COLUMN 字段数。 */
  fieldCount?: number | null
  fields?: DatasourceFieldLineageItem[] | null
}

/** 单个字段的血缘：被哪些指标引用。 */
export interface DatasourceFieldLineageItem {
  fieldId?: number | null
  table?: string | null
  column?: string | null
  label?: string | null
  role?: string | null
  sensitive?: number | null
  metrics?: DatasourceMetricRefItem[] | null
}

/** 引用该字段的指标，及其被哪些看板引用。 */
export interface DatasourceMetricRefItem {
  metricId?: number | null
  metricName?: string | null
  dashboards?: LineageRefItem[] | null
}
