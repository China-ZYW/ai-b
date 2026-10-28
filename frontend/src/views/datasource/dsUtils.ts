/**
 * 数据源管理页私有纯函数与常量（T7.3 自 DataSourceView 逐字搬移）。
 * 全部显式传参，不捕获任何响应式状态，组件与 composable 均可直接使用。
 */
import { formatDateTime, fromNow } from '@/utils/format'
import type { DataSourceVO } from '@/types'

/** 文件类数据源（走文件适配器，仅需 filePath）。 */
export function isFileType(type: string): boolean {
  return isLocalFileType(type) || isObjectStorage(type)
}

/** 本地文件数据源（CSV / Excel / Parquet）：支持上传，仅需 filePath。 */
export function isLocalFileType(type: string): boolean {
  return type === 'CSV' || type === 'EXCEL' || type === 'PARQUET'
}

/** 对象存储数据源（S3 / 阿里云 OSS / MinIO，均兼容 S3 协议）：复用 host=endpoint / database=bucket /
 *  username=accessKey / password=secretKey / filePath=objectKey / jdbcParams=region，需手动填写。 */
export function isObjectStorage(type: string): boolean {
  return type === 'S3' || type === 'OSS' || type === 'MINIO'
}

/** 从 jdbcParams（k=v&k=v）解析 region；取不到返回空串（后端回落 us-east-1）。 */
export function regionOf(params?: string | null): string {
  if (!params) return ''
  for (const pair of params.split('&')) {
    const idx = pair.indexOf('=')
    if (idx > 0 && pair.substring(0, idx).trim() === 'region') {
      return pair.substring(idx + 1).trim()
    }
  }
  return ''
}

/** 数据源分类：过去type 直接摊平展示，三类混在一起；现按「文件 / 关系型 / 数仓」提为 tab。 */
export type DsCategory = 'file' | 'relational' | 'warehouse'

/**
 * 类型分组选项 + 默认端口。{@code key} 为分类标识——**唯一的类型清单来源**：
 * 「新建数据源」下拉与页面顶部分类 tab 都由它派生，避免两处各维护一份类型列表而漂移。
 */
export const TYPE_GROUPS = [
  {
    group: '关系型数据库',
    key: 'relational' as DsCategory,
    options: [
      { value: 'MYSQL', label: 'MySQL', port: 3306 },
      { value: 'MARIADB', label: 'MariaDB', port: 3306 },
      { value: 'POSTGRESQL', label: 'PostgreSQL', port: 5432 },
      { value: 'SQLSERVER', label: 'SQL Server', port: 1433 },
      { value: 'ORACLE', label: 'Oracle', port: 1521 },
      { value: 'SQLITE', label: 'SQLite', port: 0 }
    ]
  },
  {
    group: 'OLAP / 数仓',
    key: 'warehouse' as DsCategory,
    options: [
      { value: 'CLICKHOUSE', label: 'ClickHouse', port: 8123 },
      { value: 'DORIS', label: 'Doris', port: 9030 },
      { value: 'STARROCKS', label: 'StarRocks', port: 9030 },
      { value: 'HIVE', label: 'Hive', port: 10000 },
      { value: 'PRESTO', label: 'Presto', port: 8080 },
      { value: 'TRINO', label: 'Trino', port: 8080 }
    ]
  },
  {
    group: '文件',
    key: 'file' as DsCategory,
    options: [
      { value: 'CSV', label: 'CSV 文件', port: 0 },
      { value: 'EXCEL', label: 'Excel 文件', port: 0 },
      { value: 'PARQUET', label: 'Parquet 文件', port: 0 },
      { value: 'S3', label: 'S3 对象存储', port: 0 },
      { value: 'OSS', label: '阿里云 OSS', port: 0 },
      { value: 'MINIO', label: 'MinIO 对象存储', port: 0 }
    ]
  }
]

export function defaultPort(type: string): number | null {
  for (const g of TYPE_GROUPS) {
    const hit = g.options.find((o) => o.value === type)
    if (hit) return hit.port || null
  }
  return null
}

/* ---------------- 分类 tab：文件 → 关系型 → 数仓 ---------------- */
/** 由 TYPE_GROUPS 反查「类型 → 分类」：新增类型只需在分组里加一行，tab 自动跟进。 */
export const TYPE_CATEGORY = new Map<string, DsCategory>(
  TYPE_GROUPS.flatMap((g) => g.options.map((o) => [o.value, g.key] as [string, DsCategory]))
)

export function categoryOf(type?: string | null): DsCategory | null {
  return type ? (TYPE_CATEGORY.get(type) ?? null) : null
}

/**
 * tab 顺序即业务口径：**文件 → 关系型 → 数仓**（文件最常用故置首；与「新建」下拉的分组顺序无关，
 * 下拉关注的是产品线，tab 关注的是用户先要什么）。
 */
export const CATEGORY_ORDER: DsCategory[] = ['file', 'relational', 'warehouse']
export const CATEGORY_LABEL: Record<DsCategory, string> = {
  file: '文件',
  relational: '关系型',
  warehouse: '数仓'
}

/** 连接摘要（卡片展示）。 */
export function summary(d: DataSourceVO): string {
  if (isObjectStorage(d.type)) return `${d.host || '—'} / ${d.database || '—'} / ${d.filePath || '—'}`
  if (isFileType(d.type)) return d.filePath || '—'
  const hostPort = d.host ? `${d.host}${d.port ? ':' + d.port : ''}` : '—'
  return d.database ? `${hostPort} / ${d.database}` : hostPort
}

/**
 * 卡片上的导入范围标签。
 * CSV 只有一个数据表，讲工作表反而多余，故只对 Excel 区分「全部 / N 张」。
 */
export function sheetSummaryOf(d: DataSourceVO): string {
  const base =
    d.type === 'EXCEL' ? (d.sheetNames?.length ? `${d.sheetNames.length} 张工作表` : '全部工作表') : '单表'
  return d.sheetMerge ? `${base} · 同构合并` : base
}

/** 表结构同步时间：未同步过显示「未同步」，否则显示相对时间（悬浮看绝对时间）。 */
export function syncLabel(d: DataSourceVO): string {
  return d.lastSchemaSyncAt ? `同步 ${fromNow(d.lastSchemaSyncAt)}` : '未同步'
}

/** 卡片悬浮提示用的绝对时间（原模板直接调用 formatDateTime）。 */
export { formatDateTime }

/** 字节数友好显示。 */
export function formatSize(bytes: number): string {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/* ---------------- 新建 / 编辑表单 ---------------- */
export interface DsForm {
  name: string
  type: string
  host: string
  port: number | null
  database: string
  username: string
  password: string
  filePath: string
  jdbcParams: string
  /** 对象存储类的区域（region）；为空回落后端默认 us-east-1。提交时拼进 jdbcParams（region=xxx）。 */
  region: string
  /** 勾选的工作表名；空数组 = 导入全部（与后端同义，编辑旧数据源时直接回显）。 */
  sheetNames: string[]
  /** 合并列结构完全一致的工作表。 */
  sheetMerge: boolean
  /** 数据库类额外参与问数的库/模式名；空数组 = 只扫主库。 */
  extraCatalogs: string[]
}

export const emptyForm = (): DsForm => ({
  name: '',
  type: 'MYSQL',
  host: '',
  port: 3306,
  database: '',
  username: '',
  password: '',
  filePath: '',
  jdbcParams: '',
  region: '',
  sheetNames: [],
  sheetMerge: false,
  extraCatalogs: []
})

/** 客户端上传大小上限（后端亦校验，此处给即时反馈）。 */
export const MAX_UPLOAD_SIZE = 20 * 1024 * 1024
