import { del, get, post, put } from './request'
import type { RelationSuggestion, SemanticMeta, TableRelationDef } from '@/types'

/**
 * 语义层接口（配置化升级 V2，/api/semantic）：表/列业务语义维护，注入 NL2SQL Prompt/Linker/图表角色。
 * 同时包含表关联登记（关系语义）：权限点复用 semantic:meta:*，关联与别名同属语义层治理职责。
 * 租户隔离由拦截器保证。
 */
export const semanticApi = {
  /** 某数据源的全部语义行。 */
  list: (datasourceId: number) => get<SemanticMeta[]>('/semantic', { params: { datasourceId } }),
  /** 骨架初始化：为全部表/列补语义骨架行（alias=注释/原名、role=AUTO），返回新增行数。 */
  init: (datasourceId: number) => post<number>('/semantic/init', null, { params: { datasourceId } }),
  /** 指标后台自动分析 Skill：扫描库表结构，自动识别指标/维度并建指标，返回分析摘要（已改为后台异步，submitted=true 表示已提交）。 */
  autoAnalysis: (datasourceId: number) =>
    post<{ datasourceId: number; tables: number; columns: number; metrics: number; groups: number; submitted?: boolean }>(
      '/semantic/auto-analysis', null, { params: { datasourceId } }
    ),
  /**
   * AI 全库别名补全 Skill：扫描该数据源全部表/列，用大模型推断**中文业务别名**并写回语义层。
   * 只补「为空或非中文」的别名，已有中文别名不会被覆盖；后台异步，submitted=true 表示已提交。
   */
  completeAliases: (datasourceId: number) =>
    post<{
      datasourceId: number
      tables: number
      columns: number
      filled: number
      skipped: number
      tablesFailed: number
      submitted?: boolean
    }>('/semantic/alias-completion', null, { params: { datasourceId } }),
  /** 编辑语义行（别名/描述/同义词/角色/枚举/口径/启用态）。 */
  update: (data: SemanticMeta) => put<SemanticMeta>('/semantic', data),
  /** 删除语义行。 */
  remove: (id: number) => del<void>(`/semantic/${id}`),
  /**
   * 枚举值采样回填（SELECT DISTINCT ... LIMIT 20）。
   * numeric=是否为数值码列；backfilled=false 表示未回填取值清单（数值码列且未配字典），前端据此引导补录映射。
   */
  sampleEnums: (datasourceId: number, table: string, column: string) =>
    post<{ values: string[]; numeric: boolean; backfilled: boolean; reason: string }>(
      '/semantic/sample-enums', null, { params: { datasourceId, table, column } }
    ),
  /** 一键采样该数据源下全部维度类列的枚举值（跳过度量列），返回汇总。numeric=数值码列未回填数。 */
  sampleAllEnums: (datasourceId: number) =>
    post<{ total: number; sampled: number; empty: number; skipped: number; numeric: number; failed: number }>(
      '/semantic/sample-all-enums', null, { params: { datasourceId } }
    ),
  /** 某数据源的全部表关联登记（含停用行）。 */
  relations: (datasourceId: number) =>
    get<TableRelationDef[]>('/semantic/relations', { params: { datasourceId } }),
  /** 推荐清单：按库中外键与命名约定推出的连接键，采纳即升级为人工登记的权威口径。 */
  relationSuggestions: (datasourceId: number) =>
    get<RelationSuggestion[]>('/semantic/relations/suggest', { params: { datasourceId } }),
  /**
   * 全部采纳：把该数据源「尚未登记」的推荐一键落库（已登记的跳过，不覆盖人工口径）。
   * defaultType 可选，仅用于给推不出基数的推荐定档；不传则保留「待确认」。
   */
  adoptAllRelations: (datasourceId: number, defaultType?: string) =>
    post<{ adopted: number; unknownTypes: number }>(
      '/semantic/relations/adopt-all',
      null,
      { params: { datasourceId, defaultType } }
    ),
  /** 新增或更新一条关联登记（id 为空即新增）。 */
  saveRelation: (data: TableRelationDef) => post<TableRelationDef>('/semantic/relations', data),
  /** 删除一条关联登记。 */
  removeRelation: (id: number) => del<void>(`/semantic/relations/${id}`)
}
