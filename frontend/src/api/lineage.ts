import { get, post } from './request'
import type {
  DashboardLineageVO,
  DatasourceLineageVO,
  FieldImpactVO,
  FieldReferenceVO,
  LineageVO
} from '@/types'

/**
 * 数据血缘接口（/api/lineage）：指标 ↔ 字段 ↔ 表 ↔ 看板 的引用关系（需求 §3.3）。
 *
 * 血缘属元数据，但仍受权限点保护："被哪些看板引用"会暴露内部架构关系，
 * 不宜对全部角色开放。开关关闭时服务端直接拒绝，前端无需另做判断。
 */
export const lineageApi = {
  /** 数据源级血缘总览（问数调优「数据血缘」tab）：字段 → 引用指标 → 引用看板 三层聚合。 */
  datasource: (id: number) => get<DatasourceLineageVO>(`/lineage/datasource/${id}`),
  /** 指标血缘卡：来源表/字段 + 口径表达式 + 被引用看板（自下而上：影响谁）。 */
  metric: (id: number) => get<LineageVO>(`/lineage/metric/${id}`),
  /** 看板血缘卡（P1）：该看板用了哪些指标 → 这些指标来自哪些表/字段（自上而下：数据从哪来）。 */
  dashboard: (id: number) => get<DashboardLineageVO>(`/lineage/dashboard/${id}`),
  /** 字段被哪些指标引用（语义管理「被引用指标」列与浮层）。 */
  fieldReferences: (id: number) => get<FieldReferenceVO>(`/lineage/field/${id}/references`),
  /** P1「血缘变更影响提示」：改别名/口径/列角色前调用，返回会影响的指标与看板，total=0 可安全保存。 */
  impact: (datasourceId: number, tableName: string, columnName: string) =>
    get<FieldImpactVO>(
      `/lineage/impact?datasourceId=${datasourceId}&tableName=${encodeURIComponent(
        tableName
      )}&columnName=${encodeURIComponent(columnName)}`
    ),
  /** 重建某指标血缘边（口径变更后刷新；先删后插，幂等）。 */
  rebuildMetric: (id: number) => post<{ metricId: number; edges: number }>(`/lineage/metric/${id}/rebuild`),
  /** 按数据源批量重建（语义层批量标记列角色后一次性刷新）。 */
  rebuildDatasource: (id: number) =>
    post<{ datasourceId: number; edges: number }>(`/lineage/datasource/${id}/rebuild`)
}
