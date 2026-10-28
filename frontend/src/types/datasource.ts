/** 全局类型定义·域段拆分产物（原 types/index.ts，T7 2026-10-24）。消费方一律从 @/types 导入。 */
/* ============================ 数据源 ============================ */

/** 支持的数据源类型（后端 DataSourceType 枚举名）。 */
export type DataSourceType =
  | 'MYSQL' | 'MARIADB' | 'POSTGRESQL' | 'SQLSERVER' | 'ORACLE' | 'SQLITE'
  | 'CLICKHOUSE' | 'DORIS' | 'STARROCKS' | 'HIVE' | 'PRESTO' | 'TRINO'
  | 'CSV' | 'EXCEL' | 'PARQUET'
  | 'S3' | 'OSS' | 'MINIO'

/** DataSourceVO：数据源视图（绝不含密码或密文）。 */
export interface DataSourceVO {
  id: number
  name: string
  type: DataSourceType | string
  host?: string | null
  port?: number | null
  database?: string | null
  username?: string | null
  filePath?: string | null
  hasPassword: boolean
  /** 文件类数据源已勾选的工作表名；空/null = 导入全部（编辑时据此回显勾选态）。 */
  sheetNames?: string[] | null
  /** 文件类数据源是否合并列结构完全一致的工作表。 */
  sheetMerge?: boolean
  /**
   * 数据库类额外参与扫表的库/模式名（同一服务器、同一账号）；空/null = 只扫主库。
   * 额外库的表名带「库.」前缀以支持跨库 join；主库表名不带前缀，既有指标 SQL 不受影响。
   */
  extraCatalogs?: string[] | null
  /**
   * 额外连接参数（k=v&k=v 形式）。对象存储类复用此字段传 region（如 region=us-east-1），
   * 其余类型传 JDBC 参数（如 useSSL=false&serverTimezone=UTC）。
   */
  jdbcParams?: string | null
  /**
   * 最近一次表结构同步时间（手动「立即同步」或后台定时重扫写入）。
   * null/空 = 尚未同步过（缓存里可能是首次加载时的快照）。
   */
  lastSchemaSyncAt?: string | null
}

/** DataSourceRequest：创建 / 更新请求。password 更新时留空表示保持原凭证。 */
export interface DataSourceRequest {
  name: string
  type: string
  host?: string | null
  port?: number | null
  database?: string | null
  username?: string | null
  password?: string | null
  filePath?: string | null
  jdbcParams?: string | null
  /** 文件类数据源勾选的工作表名；空/null = 导入全部。按名而非下标传，改 sheet 顺序不会错选。 */
  sheetNames?: string[] | null
  /** 是否把列结构完全一致的工作表合并为一张表（如 12 个月度 sheet 同构）。 */
  sheetMerge?: boolean
  /** 数据库类额外参与扫表的库/模式名；空/null = 只扫主库。库名写错或无权限只跳过该库，不影响主库问数。 */
  extraCatalogs?: string[] | null
}

/** TestConnectionResult：连通性测试结果。 */
export interface TestConnectionResult {
  success: boolean
  message: string
  elapsedMs: number
  /**
   * 同一服务器/连接上可选作「额外库」的库/模式名（已剔除系统库并排序）。
   * 文件类数据源、连接失败或驱动不支持时为空；为空时前端回落到手填库名。
   */
  catalogs?: string[] | null
}

/** FileSheetPreview：上传文件中单个工作表的预览（CSV 恒为一条，名为 data）。 */
export interface FileSheetPreview {
  name: string
  /** 在工作簿中的下标，仅用于展示排序；回传选择时用 name。 */
  index: number
  columns: string[]
  previewRows: string[][]
  truncated: boolean
  /** false = 该工作表首行没有表头（封面页/说明页/空表），应置灰并提示已跳过。 */
  importable: boolean
}

/** FileUploadResult：文件上传结果（问题3）。filePath 回填表单，sheets 供勾选导入范围，
 *  顶层 columns/previewRows 为首个可导入工作表的预览（单表场景的兼容入口）。 */
export interface FileUploadResult {
  filePath: string
  originalName: string
  size: number
  type: DataSourceType | string
  columns: string[]
  previewRows: string[][]
  truncated: boolean
  sheets: FileSheetPreview[]
}

/** 表结构元数据 TableMeta（与后端 {@code com.aibi.module.datasource.model.TableMeta} 逐字段对齐）。 */
export interface TableMeta {
  name: string
  comment?: string | null
  columns: ColumnMeta[]
  /** 主键列名（扫描时由 JDBC 元数据采集；无约束的库为空数组）。 */
  primaryKeys?: string[]
  /** 外键（本表列 → 引用表.列）：多表问数的关联关系提示与选表关系扩散均由它推导。 */
  foreignKeys?: FkMeta[]
}
export interface ColumnMeta {
  name: string
  type: string
  comment?: string | null
}
/** 外键元数据 FkMeta（与后端 {@code TableMeta.FkMeta} 对齐）。 */
export interface FkMeta {
  column: string
  refTable: string
  refColumn: string
}

