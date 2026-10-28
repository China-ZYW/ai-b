/**
 * 高级查询 / SQL 编辑器接口（P1）+ 自动补全与图型清单（P2）。
 *
 * 与既有问数链路的关系：本模块走的是"用户手写 SQL"通道（后端 direct_sql 技能），
 * 同样经过只读校验、表级 ACL 拦截与字段脱敏，但<b>不消耗大模型、不占用问数配额</b>。
 */
// 注意：请求层响应拦截器已把 {code,data} 解包成 data，故用类型化的 get/post/del，
// 直接用 request.get<T> 拿到的是 AxiosResponse 外壳。
import { del, get, post, put } from './request'
import type { ChartSpec, QueryResult } from '@/types'

/** 补全候选（后端 CompletionItem）。 */
export interface CompletionItem {
  /** 插入文本（真正写进 SQL 的内容） */
  text: string
  /** 展示标题（可为语义层中文别名） */
  label: string
  /** 右侧说明（类型 / 表名 / 来源） */
  detail?: string | null
  /** TABLE / COLUMN / KEYWORD / SNIPPET */
  kind: string
}

/** 图型定义（后端 ChartTypeRegistry.ChartTypeDef）。 */
export interface ChartTypeDef {
  code: string
  displayName: string
}

/** 图型的字段角色（前端拖字段到 X / 系列 / Y 时提示）。 */
export interface ChartFieldRole {
  code: string
  displayName: string
  required: boolean
}

/** 保存模板入参。 */
export interface SavedQueryCreateCmd {
  name: string
  datasourceId: number
  sqlTemplate: string
  description?: string | null
  /** 构造器规格 JSON（BuilderSpec）；来自可视化构造器时非空，SQL 编辑器来源不传。 */
  spec?: string | null
}

/** 一次运行的完整产出。 */
export interface QueryRunVO {
  result: QueryResult
  chart: ChartSpec | null
  skillCode: string
  masked: boolean
  templateId: number | null
}

/** 历史项。 */
export interface QueryRecordVO {
  id: number
  datasourceId: number
  datasourceName?: string | null
  sqlText: string | null
  rowCount: number | null
  elapsedMs: number | null
  status: string
  skillCode?: string | null
  chartType?: string | null
  createdAt?: string | null
  /** 构造器规格 JSON（BuilderSpec）；来自可视化构造器运行时才有。 */
  spec?: string | null
}

/** 运行一条 SQL。 */
export function runSql(payload: {
  datasourceId: number
  sql: string
  maxRows?: number | null
  chartType?: string | null
}) {
  return post<QueryRunVO>('/query/run', payload)
}

/** 保存查询模板。 */
export function saveTemplate(payload: SavedQueryCreateCmd) {
  return post<number>('/query/templates', payload)
}

/** 覆盖式更新一个模板（改 SQL / 改名 / 换数据源 / 改说明）。 */
export function updateTemplate(id: number, payload: SavedQueryCreateCmd) {
  return put<null>(`/query/templates/${id}`, payload)
}

/** 我的模板列表。 */
export function listTemplates(datasourceId?: number | null) {
  return get<SavedQueryVO[]>('/query/templates', {
    params: { datasourceId: datasourceId ?? undefined }
  })
}

/** 运行模板。 */
export function runTemplate(id: number, params: Record<string, unknown> = {}) {
  return post<QueryRunVO>(`/query/templates/${id}/run`, { params })
}

/** 删除模板。 */
export function deleteTemplate(id: number) {
  return del<null>(`/query/templates/${id}`)
}

/**
 * 切换模板可见性（组织共享，租户管理员/属主可操作）：SHARED=本租户成员可查/可跑/可设定时，
 * PRIVATE=仅本人。后端双重鉴权（管理员判定 + 门控 ASK/shared-templates），此处仅发请求。
 */
export function setTemplateVisibility(id: number, visibility: 'SHARED' | 'PRIVATE') {
  return put<null>(`/query/templates/${id}/visibility`, { visibility })
}

// ==================== 可视化查询构造器 ====================

/** 构造器：输出列。agg 为空表示原始列（有聚合时自动进 GROUP BY）。 */
export interface BuilderSelect {
  table: string
  column: string
  agg?: string
  alias?: string | null
}

/** 构造器：一条 JOIN（只给关系标识，连接列由服务端按图谱解析）。 */
export interface BuilderJoin {
  relationKey: string
}

/** 构造器：筛选条件。values 为空表示运算符本身不取值（IS NULL）。 */
export interface BuilderFilter {
  table: string
  column: string
  op: string
  values: string[]
}

/** 构造器：排序。 */
export interface BuilderSort {
  table: string
  column: string
  desc?: boolean | null
}

/** 构造器完整规格。 */
export interface BuilderSpec {
  mainTable: string
  joins: BuilderJoin[]
  selects: BuilderSelect[]
  filters: BuilderFilter[]
  sorts: BuilderSort[]
  limit?: number | null
  offset?: number | null
}

/** 构造器可用的一条关联（来自服务端清单，只能选不能改）。 */
export interface BuilderRelationVO {
  id: number
  key: string
  table: string
  column: string
  refTable: string
  refColumn: string
  source: string | null
  cardinality: string | null
}

/** 构造器生成的 SQL（预览）。 */
export interface BuilderSqlVO {
  sql: string
  paramCount: number
}

/** 构造器「预览 / 运行」共用入参。 */
export interface BuilderRunRequest {
  datasourceId: number
  spec: BuilderSpec
  maxRows?: number | null
  chartType?: string | null
}

/** 构造器：某张表可用的关联清单。 */
export function builderRelations(datasourceId: number, table: string) {
  return get<BuilderRelationVO[]>('/query/builder/relations', { params: { datasourceId, table } })
}

/** 构造器：只生成 SQL、不执行（预览）。 */
export function builderPreview(payload: BuilderRunRequest) {
  return post<BuilderSqlVO>('/query/builder/preview', payload)
}

/** 构造器：生成 SQL 并执行。 */
export function builderRun(payload: BuilderRunRequest) {
  return post<QueryRunVO>('/query/builder/run', payload)
}

/** 我的执行历史。 */
export function queryHistory(params: { datasourceId?: number | null; limit?: number | null } = {}) {
  return get<QueryRecordVO[]>('/query/history', {
    params: { datasourceId: params.datasourceId ?? undefined, limit: params.limit ?? undefined }
  })
}

/** SQL 补全。 */
export function completeSql(params: {
  datasourceId: number
  prefix?: string
  tableName?: string | null
  limit?: number
}) {
  return get<CompletionItem[]>('/completion/schema', { params })
}

/** 图型清单（前端图表选择器下拉来源，避免硬编码）。 */
export function chartTypes() {
  return get<ChartTypeDef[]>('/completion/chart-types')
}

/** 某图型需要的字段角色。 */
export function chartFieldRoles(code: string) {
  return get<ChartFieldRole[]>(`/completion/chart-types/${encodeURIComponent(code)}/field-roles`)
}

/** 已注册的补全来源（排错用）。 */
export function completionProviders() {
  return get<string[]>('/completion/providers')
}

/** 模板列表项（前端内部使用）。 */
export interface SavedQueryVO {
  id: number
  name: string
  description?: string | null
  datasourceId: number
  datasourceName?: string | null
  sqlTemplate: string
  paramNames: string[]
  updatedAt?: string | null
  /** 构造器规格 JSON（BuilderSpec）；非空表示此模板来自可视化构造器。 */
  spec?: string | null
  /** 可见性：PRIVATE=仅本人 / SHARED=组织共享（存量 NULL 按 PRIVATE，后端已归一）。 */
  visibility?: string | null
  /** 属主用户 id（判断「是不是我的模板」：非属主行隐藏编辑/删除，共享行仍可跑/可定时）。 */
  ownerUid?: number | null
}
