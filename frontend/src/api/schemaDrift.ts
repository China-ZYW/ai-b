import { post } from './request'

/** 语义层孤儿行（表/列已不在物理结构中）。 */
export interface OrphanSemantic {
  id: number
  tableName: string
  columnName: string
  aliasCn?: string | null
  tableMissing: boolean
  columnMissing: boolean
  reason: string
}

/** 悬空关联（左/右表或列已不存在）。 */
export interface DanglingRelation {
  id: number
  leftTable: string
  leftColumn?: string | null
  rightTable: string
  rightColumn?: string | null
  reason: string
}

/** 悬空字段/表级权限（fail-open 降级点）。 */
export interface DanglingAclItem {
  id: number
  /** USER=成员直授 / ROLE=角色继承。 */
  target: 'USER' | 'ROLE'
  /** FIELD=字段脱敏 / TABLE=表级可见性。 */
  kind: 'FIELD' | 'TABLE'
  refTable?: string | null
  refColumn?: string | null
  effect: string
  principalId: number
}

/** 指标 SQL / 图表规格里的坏引用（表或列已不存在）。 */
export interface BrokenMetricRef {
  table: string
  column?: string | null
  tableMissing: boolean
}

export interface BrokenMetric {
  id: number
  name?: string | null
  reason: string
  refs: BrokenMetricRef[]
}

/** 受影响的驾驶舱（widget 引用了坏引用 / 已不存在的指标）。 */
export interface AffectedDashboard {
  id: number
  name?: string | null
  brokenMetricIds: number[]
  missingMetricIds: number[]
}

/** 结构对账报告（五类失配清单）。 */
export interface DriftReport {
  datasourceId: number
  schemaTableCount: number
  schemaColumnCount: number
  orphanSemantics: OrphanSemantic[]
  danglingRelations: DanglingRelation[]
  danglingAcls: DanglingAclItem[]
  brokenMetrics: BrokenMetric[]
  affectedDashboards: AffectedDashboard[]
}

/** 一键清理请求（仅携带可删除的孤儿元数据 id 清单）。 */
export interface DriftPruneRequest {
  datasourceId: number
  semanticIds?: number[]
  relationIds?: number[]
  userFieldAclIds?: number[]
  userTableAclIds?: number[]
  roleFieldAclIds?: number[]
  roleTableAclIds?: number[]
}

/** 结构变更感知：一条受影响资产（T12-3，后端 ImpactBrief 投影，不含资产全文）。 */
export interface WatchImpact {
  assetType: 'SAVED_QUERY' | 'METRIC'
  assetId: number
  name?: string | null
  brokenRefs: string[]
}

/** 结构变更感知一次结果：首轮只建基准；删表/删列才反查影响并台账+站内信。 */
export interface SchemaWatchResult {
  datasourceId: number
  baselineCreated: boolean
  changeSummaries: string[]
  impacts: WatchImpact[]
  notifiedUsers: number
  note?: string | null
}

/**
 * 结构对账接口（/api/semantic/drift）：检测下游元数据与物理表结构之间的漂移，并支持一键清理孤儿。
 * 检测复用 semantic:meta:read，清理复用 semantic:meta:delete（与语义层治理同属一个权限域）。
 * T12-3 感知变更（watch）：与上轮快照 diff 出删表/删列 → 反查受影响模板/指标并预警，同复用 read 权限。
 */
export const schemaDriftApi = {
  /** 对指定数据源做全量结构对账，返回五类失配清单（只读）。 */
  check: (datasourceId: number) =>
    post<DriftReport>('/semantic/drift/check', null, { params: { datasourceId } }),
  /** 一键清理勾选的孤儿元数据（语义行 / 关联 / 四类权限），返回各表删除计数。 */
  prune: (data: DriftPruneRequest) => post<Record<string, number>>('/semantic/drift/prune', data),
  /** 结构变更感知（T12-3）：重扫与上轮快照 diff，删表/删列反查影响并预警；首轮只建基准。 */
  watch: (datasourceId: number) =>
    post<SchemaWatchResult>('/semantic/drift/watch', null, { params: { datasourceId } })
}
