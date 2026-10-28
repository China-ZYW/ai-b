/** 通用格式化工具：时间、数字、耗时、字节等。 */

/** 后端 LocalDateTime 序列化为 "2026-09-03T12:34:56"（无时区），按本地时间解析展示。 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}`
}

/** 相对时间（几分钟前 / 几小时前 / 几天前），用于会话列表。 */
export function fromNow(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const diff = Date.now() - d.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  return formatDateTime(value)
}

/** 毫秒耗时友好展示。 */
export function formatElapsed(ms?: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

/** 大数字千分位。 */
export function formatNumber(n?: number | null): string {
  if (n == null) return '—'
  return n.toLocaleString('zh-CN')
}

/** 套餐中文名。 */
export function planLabel(plan?: string | null): string {
  switch (plan) {
    case 'FREE':
      return '免费版'
    case 'PRO':
      return '专业版'
    case 'ENTERPRISE':
      return '企业版'
    default:
      return plan || '—'
  }
}

/** 数据源类型展示名。 */
/** 文件 / 对象存储类新类型的展示名（避免 title-case 把 OSS/MinIO 显示成 Oss/Minio）。 */
const TYPE_LABELS: Record<string, string> = {
  CSV: 'CSV 文件',
  EXCEL: 'Excel 文件',
  PARQUET: 'Parquet 文件',
  S3: 'S3 对象存储',
  OSS: '阿里云 OSS',
  MINIO: 'MinIO 对象存储'
}

export function datasourceTypeLabel(type?: string | null): string {
  if (!type) return '—'
  return TYPE_LABELS[type] ?? type.charAt(0) + type.slice(1).toLowerCase()
}

/**
 * 查询结果的列展示名：自定义名（指标级 / 会话内重命名）> 语义层业务别名 > 物理列名兜底。
 *
 * 汇总/派生列（如 {@code SUM(amount) AS total_amount}）在语义层没有物理列行，只能靠 {@code overrides}
 * 取业务叫法。供明细表表头与图表坐标轴/图例/系列名共用；字段身份匹配（图表 xField/yFields、
 * 行数据 key）仍须用 columns，勿用本函数结果反查字段。
 *
 * @param overrides 物理列名 → 自定义展示名（空白值视为未配置，回落语义层别名）
 */
export function displayColumns(
  result?: { columns?: string[] | null; columnLabels?: string[] | null } | null,
  overrides?: Record<string, string> | null
): string[] {
  const cols = result?.columns ?? []
  const labels = result?.columnLabels
  const hasOverride = !!overrides && Object.keys(overrides).length > 0
  if (!hasOverride && !labels?.length) return cols
  return cols.map((c, i) => overrides?.[c]?.trim() || labels?.[i] || c)
}

/** 码值 → 中文标签的展示映射（后端 {@code enumDicts} 中某一列的值）。 */
type EnumDict = Record<string, string>

type EnumDictSource = {
  columns?: string[] | null
  rows?: unknown[][] | null
  enumDicts?: Record<string, EnumDict> | null
} | null

/**
 * 单格码值 → 中文标签：命中字典就返回中文，否则原样返回（未知码值宁显裸码，不猜）。
 *
 * 码值在库里可能是数字也可能是字符串（Integer / BigDecimal / "1"），统一按字符串比对；
 * 字典的 key 也已由后端 trim 过，这里只 trim 待查值，避免 " 1" 这类脏数据漏替换。
 */
export function enumLabelOf(value: unknown, dict?: EnumDict | null): unknown {
  if (!dict || !Object.keys(dict).length) return value
  const hit = dict[String(value)?.trim()]
  return hit ? hit : value
}

/**
 * 按枚举字典替换整份结果集的码值（<b>仅供显示</b>）。
 *
 * 语义列配了「枚举字典」后，明细表与图表里 {@code status=1} 应显示 {@code 已支付}；
 * 但导出 Excel、AI 解读与后续聚合仍应拿原始码值，避免丢掉可追溯性——所以本函数只产出一份
 * 展示用行数组，绝不改 {@code result.rows} 本身。
 *
 * <p>只在确有列命中时复制行（命中才浅拷贝单行、命中才替换该格），无命中时**原样返回入参行**，
 * 调用方可用 `out === result.rows` 判断是否发生了替换。
 *
 * @param result 结果集（只读，不被修改）
 * @returns 与 rows 等比同序的展示行；无字典或无任何列命中时返回原 rows
 */
export function applyEnumDicts(result: EnumDictSource): unknown[][] {
  const dicts = result?.enumDicts
  const raw = result?.rows
  if (!dicts || !raw) return raw ?? []
  const cols = result?.columns ?? []
  const usable = Object.keys(dicts).filter(
    (col) => dicts[col] && Object.keys(dicts[col]).length > 0 && cols.indexOf(col) >= 0
  )
  if (!usable.length) return raw

  // 列名 → 列下标：图表按下标取数，换成下标查找可避免同一列名重复匹配
  const byIndex = new Map<number, EnumDict>()
  usable.forEach((col) => byIndex.set(cols.indexOf(col), dicts[col]))

  return raw.map((row) => {
    let out: unknown[] | null = null
    byIndex.forEach((dict, ci) => {
      const label = enumLabelOf(row[ci], dict)
      if (label === row[ci]) return
      if (!out) out = row.slice()
      out[ci] = label
    })
    return out ?? row
  })
}

/**
 * 收敛自定义展示名映射：只保留“确实改了名”的条目（非空、且不等于列名与语义层默认名），
 * 避免把一堆与兜底名相同的冗余条目写进指标配置（{@code display_config} 列宽有限）。
 */
export function pruneColumnLabels(
  result?: { columns?: string[] | null; columnLabels?: string[] | null } | null,
  overrides?: Record<string, string> | null
): Record<string, string> | null {
  if (!overrides) return null
  const cols = result?.columns ?? []
  const labels = result?.columnLabels
  const kept: Record<string, string> = {}
  cols.forEach((c, i) => {
    const v = overrides[c]?.trim()
    if (v && v !== c && v !== (labels?.[i] || c)) kept[c] = v
  })
  return Object.keys(kept).length ? kept : null
}
