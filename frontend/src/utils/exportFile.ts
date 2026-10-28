/**
 * 前端「下载 / 导出」统一入口。
 *
 * <p>为什么集中一处：问数结果、驾驶舱卡片、嵌入问数、语义/归因表此前要么没有导出，要么各写一份
 * （CSV 表头兜底、空值、BOM、文件名时间戳口径不一）。这里收敛为唯一实现，各处只声明"列 + 行"。
 *
 * <p><b>xlsx（SheetJS）用动态 import</b>：只有真的点了「导出 Excel」才会加载该分块，
 * 首屏体积不受影响（与信息图组件的懒加载同思路）。
 */

/** 导出列：key 为取值字段，label 为表头显示名（中文列名/别名）。 */
export interface ExportColumn {
  key: string
  label: string
}

/** 文件名时间戳：yyyyMMdd-HHmmss（本地时区，便于人工辨认）。 */
export function fileStamp(d: Date = new Date()): string {
  const p = (n: number): string => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}` +
    `-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
  )
}

/** 单元格值：null/undefined → 空；原始类型原样（数字在 Excel 里保持数值）；其余转 JSON。 */
function cellValue(v: unknown): string | number | boolean | null {
  if (v === null || v === undefined) return null
  const t = typeof v
  if (t === 'string' || t === 'number' || t === 'boolean') return v as string | number | boolean
  return JSON.stringify(v)
}

/** 触发浏览器下载一个 Blob。 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // 下一个事件循环再释放，避免部分浏览器下载尚未开始就撤销 URL
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/** 触发浏览器下载一个 DataURL（图表导出用）。 */
export function downloadDataUrl(dataUrl: string, filename: string): void {
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/** 导出 CSV（带 UTF-8 BOM，Excel/WPS 打开不乱码）。 */
export function exportCsv(
  filename: string,
  columns: ExportColumn[],
  rows: Array<Record<string, unknown>>
): void {
  const csvCell = (v: unknown): string => {
    const s = cellValue(v)
    if (s === null) return ''
    const t = String(s)
    return /[",\n\r]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t
  }
  const lines = [columns.map((c) => csvCell(c.label)).join(',')]
  for (const r of rows) lines.push(columns.map((c) => csvCell(r[c.key])).join(','))
  const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' })
  downloadBlob(blob, filename.endsWith('.csv') ? filename : `${filename}.csv`)
}

/**
 * 导出 XLSX（SheetJS，动态加载）。
 *
 * @param filename  含/不含 .xlsx 均可
 * @param sheetName 工作表名（默认「数据」；Excel 限制 31 字符且不含 : \ / ? * [ ]，内部会自动清洗）
 */
export async function exportXlsx(
  filename: string,
  columns: ExportColumn[],
  rows: Array<Record<string, unknown>>,
  sheetName = '数据'
): Promise<void> {
  const mod = (await import('xlsx')) as unknown as Record<string, unknown>
  const XLSX = (mod.default ?? mod) as {
    utils: {
      aoa_to_sheet: (data: unknown[][]) => Record<string, unknown>
      book_new: () => unknown
      book_append_sheet: (wb: unknown, ws: unknown, name: string) => void
    }
    writeFile: (wb: unknown, filename: string) => void
  }

  const header = columns.map((c) => c.label)
  const body = rows.map((r) => columns.map((c) => cellValue(r[c.key])))
  const ws = XLSX.utils.aoa_to_sheet([header, ...body]) as { '!cols'?: Array<{ wch: number }> }
  // 列宽按内容粗估（中文按约 1.8 字符宽），避免打开后全挤在一起
  ws['!cols'] = columns.map((c, i) => {
    const sample = body.slice(0, 200).map((r) => String(r[i] ?? '').length)
    const maxLen = Math.max(String(c.label).length, ...sample)
    return { wch: Math.min(48, Math.max(8, Math.ceil(maxLen * 1.4) + 2)) }
  })

  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, safeSheetName(sheetName))
  XLSX.writeFile(wb, filename.endsWith('.xlsx') ? filename : `${filename}.xlsx`)
}

/** Excel 工作表名合法化：去非法字符、限 31 字符、空则「数据」。 */
function safeSheetName(name: string): string {
  const t = (name || '').replace(/[:\\/?*[\]]/g, '').trim().slice(0, 31)
  return t || '数据'
}

/**
 * 便捷：把「列名数组 + 二维行数组」（问数结果 / 嵌入问数的结构）转成导出入参。
 *
 * @param labels 可选的展示列名（如语义层别名 columnLabels）；缺省用物理列名
 */
export function gridToExport(
  columns: string[],
  rows: unknown[][],
  labels?: Array<string | null | undefined>
): { columns: ExportColumn[]; rows: Array<Record<string, unknown>> } {
  const cols: ExportColumn[] = columns.map((c, i) => ({
    key: `c${i}`,
    label: labels?.[i] || c || `列${i + 1}`
  }))
  const out: Array<Record<string, unknown>> = rows.map((r) => {
    const o: Record<string, unknown> = {}
    cols.forEach((col, i) => {
      o[col.key] = r?.[i]
    })
    return o
  })
  return { columns: cols, rows: out }
}

/** 导出图表 PNG（传入 BaseChart 暴露的 getDataURL）；返回是否成功。 */
export function exportChartPng(getDataUrl: () => string | null, name: string): boolean {
  const url = getDataUrl()
  if (!url) return false
  downloadDataUrl(url, `${name}-${fileStamp()}.png`)
  return true
}
