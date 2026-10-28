#!/usr/bin/env node
/**
 * 写操作护栏审计（v3.21 / §38）。
 *
 * 用法：`npm run audit:guards`（或 node scripts/audit-write-guards.mjs）
 *
 * 输出一份 markdown 表格，列出 `src` 下每个含写操作的文件的护栏现状：
 * 写调用数 / 是否已采用统一护栏 / 异步开关数 / 其中缺 :loading、缺 :disabled 的个数。
 *
 * 定位：**审计报告，不是门禁**。它只报事实，不下"合格/不合格"的结论——是否必须加护栏取决于
 * 该动作的语义（详见 src/utils/actionGuard.ts 的分工说明）。真正的兜底边界在请求层
 * （src/api/request.ts 的「同请求在途去重」），本脚本用来盯住"交互反馈"这一层不被遗漏、也不被写歪。
 *
 * 判定规则（刻意保守，宁可漏报不误报）：
 * - 写调用：源码里出现 `await XxxApi.方法(` 且方法名不是明显的只读动词即计一次
 *   （`list/get/query/...` 这类排除掉，否则 `manualApi.published()` 会被误算成写操作）。
 * - 已采用统一护栏：文件里出现 `useActionGuard(` 或 `useRowBusy(`。
 * - 手写护栏：文件里出现 `submitting.value = true` 这类旧写法（用于看收敛进度）。
 * - 异步开关：`<el-switch>` 块内含 `:model-value`（值由外部数据驱动、@change 里发请求）。
 *   仅用 `v-model` 的开关是纯表单控件，不发请求，**不计入**。
 * - 缺 :loading / 缺 :disabled：该开关块内没有对应属性即计入。
 *
 * 已知覆盖范围：src 下所有 .vue，加上 src/stores 下的所有 .ts。
 * 后者是必要的——有一部分写操作不经过页面直接调 api，而是走 store（如 `DataSourceView` 的
 * `dsStore.testById`），只扫 .vue 会把这类调用整体漏掉。
 */
import { readFileSync, readdirSync, statSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, relative, sep, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = join(fileURLToPath(new URL('.', import.meta.url)), '..', 'src')
// ★T5 收尾：报告落点可用环境变量 AIBI_AUDIT_OUT 覆盖。CI 门禁（scripts/ci_check.ps1）把报告
//   写到 gitignore 的 .ci-artifacts/ 目录，避免每次跑门禁用生成结果改写 tracked 的默认报告而污染工作区；
//   缺省（本地手动跑 npm run audit:guards）仍写原位，行为与既往一致。
const OUT = process.env.AIBI_AUDIT_OUT
  ? process.env.AIBI_AUDIT_OUT
  : join(fileURLToPath(new URL('.', import.meta.url)), 'audit-write-guards.md')

/** 递归收集 .vue 与 stores 下的 .ts。 */
function walk(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, acc)
    else if (name.endsWith('.vue') || name.endsWith('.ts')) acc.push(full)
  }
  return acc
}

/** 抽掉 ``` 围栏代码块：文档注释里的示例（如 actionGuard 的 el-switch 用法）不该被算成真实用法。 */
function stripFences(text) {
  return text.replace(/```[\s\S]*?```/g, '')
}

/** 抽出所有 <el-switch ... /> 片段。 */
function switchBlocks(text) {
  const blocks = []
  const re = /<el-switch\b[\s\S]*?\/>/g
  let m
  while ((m = re.exec(text)) !== null) blocks.push(m[0])
  return blocks
}

// 匹配 `await xxxApi.方法(` 与 `await xxxStore.方法(` 两种形态 ——
// 有一部分写操作不直接调 api 而走 store（如 `dsStore.create`），只认 `Api` 会把它们整体漏掉。
// `ElMessageBox.confirm(...)` 这类既不叫 Api 也不叫 Store，天然被排除。
const WRITE_CALL = /\bawait\s+[A-Za-z_$][\w$]*(?:[Aa]pi|[Ss]tore)\s*\.\s*([A-Za-z_$][\w$]*)\s*\(/g

/** 明显只读 / 非服务端写的方法名（按前缀匹配）：不计入"写调用"。 */
const READ_METHODS = /^(list|get|query|search|published|effective|managed|detail|suggestions|skills|tree|stats|values|options|load|fetch|count|exists|check|abort)/i

const rows = []
for (const file of walk(SRC)) {
  const text = stripFences(readFileSync(file, 'utf8'))
  const writes = [...text.matchAll(WRITE_CALL)].filter((m) => !READ_METHODS.test(m[1])).length
  const switches = switchBlocks(text)
  const asyncSwitches = switches.filter((b) => b.includes(':model-value'))
  const missingLoading = asyncSwitches.filter((b) => !b.includes(':loading')).length
  const missingDisabled = asyncSwitches.filter((b) => !b.includes(':disabled')).length
  // 按标识符匹配（不要求紧跟 `(`）：`useRowBusy<K>()` 泛型实例化后 `useRowBusy` 与 `(` 之间隔着
  // `<K>`，`includes('useRowBusy(')` 会漏判；用单词边界的正则能同时覆盖带/不带泛型两种写法。
  const usesSharedGuard = /\b(useActionGuard|useRowBusy|fingerprint)\b/.test(text)
  const handRolled = /(submitting|saving|busy)\w*\.value\s*=\s*(true|false)/.test(text)

  if (writes === 0 && asyncSwitches.length === 0) continue
  rows.push({
    file: relative(SRC, file).split(sep).join('/'),
    writes,
    guard: usesSharedGuard ? '✅ 统一护栏' : handRolled ? '⚠️ 手写' : '❌ 无',
    asyncSwitch: asyncSwitches.length,
    missingLoading,
    missingDisabled
  })
}

rows.sort((a, b) => b.missingDisabled - a.missingDisabled || b.writes - a.writes || a.file.localeCompare(b.file))

const total = {
  files: rows.length,
  writes: rows.reduce((s, r) => s + r.writes, 0),
  shared: rows.filter((r) => r.guard.startsWith('✅')).length,
  hand: rows.filter((r) => r.guard.startsWith('⚠️')).length,
  none: rows.filter((r) => r.guard.startsWith('❌')).length,
  asyncSwitch: rows.reduce((s, r) => s + r.asyncSwitch, 0),
  missingLoading: rows.reduce((s, r) => s + r.missingLoading, 0),
  missingDisabled: rows.reduce((s, r) => s + r.missingDisabled, 0)
}

const lines = []
lines.push('# 写操作护栏审计（自动生成，勿手工编辑）')
lines.push('')
lines.push(`- 含写操作的前端文件：**${total.files}** 个，写调用共 **${total.writes}** 处`)
lines.push(`- 护栏采用：统一护栏 **${total.shared}** / 手写 **${total.hand}** / 无 **${total.none}**`)
lines.push(`- 异步开关（model-value 驱动、change 发请求）共 **${total.asyncSwitch}** 个；缺 loading **${total.missingLoading}**、缺 disabled **${total.missingDisabled}**`)
lines.push('')
lines.push('| 文件 | 写调用 | 护栏 | 异步开关 | 缺 :loading | 缺 :disabled |')
lines.push('|---|---:|---|---:|---:|---:|')
for (const r of rows) lines.push(`| \`${r.file}\` | ${r.writes} | ${r.guard} | ${r.asyncSwitch} | ${r.missingLoading} | ${r.missingDisabled} |`)
lines.push('')

const report = lines.join('\n')
mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, report, 'utf8')
process.stdout.write(report)
process.stdout.write(`\n报告已写入 ${OUT}\n`)
