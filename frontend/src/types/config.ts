/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
/* ============================ 配置化升级 V2：配置中心 / 语义层 / 样例库 / 图表规则 / 主题 ============================ */

/** 配置分类。 */
export type ConfigCategory =
  | 'RESULT'
  | 'STYLE'
  | 'CHART'
  | 'NL2SQL'
  | 'SYSTEM'
  | 'FRESHNESS'
  | 'AI'
  | 'ASK'
  | 'AUDIT'
  | 'ATTRIBUTION'
  | 'METRIC'
  | 'COCKPIT'
  | 'THEME'

/** SysConfig：统一配置中心行（GLOBAL/TENANT 二级 scope）。 */
export interface SysConfig {
  id?: number | null
  scopeType?: 'GLOBAL' | 'TENANT' | string
  scopeId?: number | null
  category: ConfigCategory | string
  configKey: string
  valueJson?: string | null
  description?: string | null
  version?: number | null
  enabled?: number | null
  /** 平台管控（仅 GLOBAL 行有意义）：1 = 该键锁死为平台值，所有租户不可覆盖。 */
  managed?: number | null
  updatedAt?: string | null
}

/** 合并视图：category → key → 解析后的值（GET /api/config/effective）。 */
export type EffectiveConfig = Record<string, Record<string, unknown>>

/** 列角色（语义层）。 */
export type ColumnRole = 'AUTO' | 'DIMENSION' | 'MEASURE' | 'TEMPORAL'

/** SemanticMeta：语义层行（表/列的业务语义）。 */
export interface SemanticMeta {
  id?: number | null
  datasourceId?: number | null
  objectType?: 'TABLE' | 'COLUMN' | string
  tableName?: string | null
  columnName?: string | null
  aliasCn?: string | null
  description?: string | null
  synonyms?: string | null
  columnRole?: ColumnRole | string | null
  enumValues?: string | null
  /**
   * 枚举字典（码值→中文标签的 JSON 对象，如 {"1":"已支付"}）。
   * 数字编码列（status=1/2/3/4）配了字典后，Prompt 与问数结果才能知道码值含义。
   */
  enumLabels?: string | null
  sqlExpr?: string | null
  /** 度量单位（元/件/%/次）：图表轴名与数值标注据此拼接，优先于按列名关键词的自动推断。 */
  unit?: string | null
  /** 字段级权限：是否敏感列（1=需按角色可见，0/null=普通无脱敏）。 */
  sensitive?: number | null
  /** 字段级权限：允许查看的角色码（逗号分隔，如 OWNER,ADMIN,DATA_STEWARD）；空=仅 OWNER/ADMIN 可见。 */
  visibleRoles?: string | null
  enabled?: number | null
  /** AI 自动分析来源标识（AUTO 分析写入 'AI'）：前端据此展示「AI 自动识别」徽标。 */
  source?: string | null
}

/** 关联基数：多对一 / 一对一 / 多对多。 */
export type RelationType = 'MANY_TO_ONE' | 'ONE_TO_ONE' | 'MANY_TO_MANY'

/**
 * TableRelationDef：表关联登记行（语义层第三级「关系语义」）。
 *
 * 人工声明「哪两张表用哪对键 JOIN、基数是什么」，以最高可信度并入问数链路的关联图谱：
 * 库中没建外键约束时（业务库与分析型库的常态），命名推断只能兜底且推不出多对多，
 * 缺了它 LLM 只能拿同名列硬凑（orders.id = order_item.id）或逗号连接产生笛卡尔积。
 */
export interface TableRelationDef {
  id?: number | null
  datasourceId?: number | null
  /** 多方表名（持有连接键的一侧）。跨库联合分析下须带「库名.」前缀。 */
  leftTable: string
  leftColumn: string
  /** 一方表名。 */
  rightTable: string
  rightColumn: string
  relationType?: RelationType | string | null
  /** 业务口径说明（为何这样关联）。 */
  description?: string | null
  enabled?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/**
 * RelationSuggestion：系统按库中外键与命名约定推出的关联，供管理员一键采纳或纠正。
 * registeredId 非空表示已登记（前端显示「已登记」并可跳去编辑），避免同一条键被重复采纳。
 */
export interface RelationSuggestion {
  leftTable: string
  leftColumn: string
  rightTable: string
  rightColumn: string
  /** 推导出的基数；UNKNOWN 表示推不出来、需人工确认。 */
  relationType: RelationType | 'UNKNOWN' | string
  /** FOREIGN_KEY=库中外键 / INFERRED=命名推断。 */
  source: string
  registeredId?: number | null
}

/** FewShotExample：问数样例库行（curated / history_promoted）。 */
export interface FewShotExample {
  id?: number | null
  datasourceId?: number | null
  question: string
  sqlText: string
  intent?: string | null
  tags?: string | null
  source?: 'CURATED' | 'HISTORY_PROMOTED' | string | null
  enabled?: number | null
  useCount?: number | null
  updatedAt?: string | null
}

/** ChartRule：图表推荐规则（KEYWORD=关键词→图型；THRESHOLD=阈值覆盖）。 */
export interface ChartRule {
  id?: number | null
  tenantId?: number | null
  ruleType?: 'KEYWORD' | 'THRESHOLD' | string
  pattern: string
  chartType?: string | null
  priority?: number | null
  enabled?: number | null
  description?: string | null
}

/** ThemeConfig：主题令牌（tokens 为与 DashboardTheme 同构的 JSON 字符串）。 */
export interface ThemeConfig {
  id?: number | null
  tenantId?: number | null
  code: string
  name?: string | null
  dark?: number | null
  tokens: string
  builtin?: number | null
  updatedAt?: string | null
}

